// ---------------------------------------------------------------------
//
// XTextureExtractor
//
// Copyright (C) 2018 Wayne Piekarski
// wayne@tinmith.net http://tinmith.net/wayne
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// ---------------------------------------------------------------------

package net.waynepiekarski.xtextureextractor

import android.graphics.Bitmap
import android.util.Log

import java.net.*
import kotlin.concurrent.thread
import java.io.*
import android.graphics.BitmapFactory
import android.widget.Toast


class TCPBitmapClient (private var address: InetAddress, private var port: Int, private var callback: OnTCPBitmapEvent) {
    private lateinit var socket: Socket
    @Volatile private var cancelled = false
    private lateinit var bufferedWriter: BufferedWriter
    private lateinit var outputStreamWriter: OutputStreamWriter
    private lateinit var dataInputStream: DataInputStream

    interface OnTCPBitmapEvent {
        fun onReceiveTCPBitmap(windowId: Int, image: Bitmap, tcpRef: TCPBitmapClient)
        fun onReceiveTCPHeader(header: ByteArray, tcpRef: TCPBitmapClient)
        fun onConnectTCP(tcpRef: TCPBitmapClient)
        fun onDisconnectTCP(reason: String?, tcpRef: TCPBitmapClient)
    }

    fun stopListener() {
        // Stop the loop from running any more
        cancelled = true

        // Call close on the top level buffers to cause any pending read to fail, ending the loop
        closeBuffers()

        // The socketThread loop will now clean up everything
    }

    fun writeln(str: String) {
        if (cancelled) {
            Log.d(Const.TAG, "Skipping write to cancelled socket: [$str]")
            return
        }
        Log.d(Const.TAG, "Writing to TCP socket: [$str]")
        try {
            bufferedWriter.write(str + "\n")
            bufferedWriter.flush()
        } catch (e: IOException) {
            Log.d(Const.TAG, "Failed to write [$str] to TCP socket with exception $e")
            stopListener()
        }
    }

    private fun closeBuffers() {
        // Call close on the top level buffers which will propagate to the original socket
        // and cause any pending reads and writes to fail
        if (::bufferedWriter.isInitialized) {
            try {
                Log.d(Const.TAG, "Closing bufferedWriter")
                bufferedWriter.close()
            } catch (e: IOException) {
                Log.d(Const.TAG, "Closing bufferedWriter in stopListener caused IOException, this is probably ok")
            }
        }
        if (::dataInputStream.isInitialized) {
            try {
                Log.d(Const.TAG, "Closing dataInputStream")
                dataInputStream.close()
            } catch (e: IOException) {
                Log.d(Const.TAG, "Closing dataInputStream in stopListener caused IOException, this is probably ok")
            }
        }
    }

    // In a separate function so we can "return" any time to bail out
    private fun socketThread() {
        try {
            socket = Socket(address, port)
        } catch (e: Exception) {
            Log.e(Const.TAG, "Failed to connect to $address:$port with exception $e")
            MainActivity.doUiThread { callback.onDisconnectTCP(null,this) }
            return
        }

        // Wrap the socket up so we can work with it - no exceptions should be thrown here
        try {
            outputStreamWriter = OutputStreamWriter(socket.getOutputStream())
            bufferedWriter = BufferedWriter(outputStreamWriter)
            dataInputStream = DataInputStream(socket.getInputStream())
        } catch (e: IOException) {
            Log.e(Const.TAG, "Exception while opening socket buffers $e")
            closeBuffers()
            MainActivity.doUiThread { callback.onDisconnectTCP(null,this) }
            return
        }

        // Connection should be established, everything is ready to read and write
        MainActivity.doUiThread { callback.onConnectTCP(this) }

        // There is always a header at the start before the PNG stream, read that first
        var header = ByteArray(Const.TCP_INTRO_HEADER)
        try {
            dataInputStream.readFully(header)
        } catch (e: IOException) {
            Log.d(Const.TAG, "Failed to receive initial header, connection has failed")
            cancelled = true
        }
        if (!cancelled) {
            MainActivity.doUiThread { callback.onReceiveTCPHeader(header, this) }
        }

        // Start reading from the socket, any writes happen from another thread
        var reason: String? = null
        while (!cancelled) {
            // Each window transmission starts with !_X_ where X is a binary byte 0x00 to 0xFF
            var windowId: Int = -1
            try {
                val a = dataInputStream.readByte().toChar()
                val b = dataInputStream.readByte().toChar()
                windowId = dataInputStream.readByte().toInt()
                val d = dataInputStream.readByte().toChar()
                if ((a != '!') || (b != '_') || (d != '_')) {
                    reason = "Image header invalid ![$a] _[$b] _[$d]"
                    cancelled = true
                    break
                }
            } catch (e: IOException) {
                Log.d(Const.TAG, "Failed to receive window header, connection has failed")
                cancelled = true
            }

            // Read the raw PNG data
            var bitmap: Bitmap? = null
            try {
                bitmap = BitmapFactory.decodeStream(dataInputStream)
            } catch (e: IOException) {
                Log.d(Const.TAG, "Exception during socket bitmap decode $e")
                bitmap = null
            }
            if (bitmap == null) {
                Log.d(Const.TAG, "Bitmap decode returned null, connection has failed")
                cancelled = true
                reason = "Bitmap decode failure"
            } else {
                MainActivity.doUiThread { callback.onReceiveTCPBitmap(windowId, bitmap, this) }
            }
        }

        // Close any outer buffers we own, which will propagate to the original socket
        closeBuffers()

        // The connection is gone, tell the listener in case they need to update the UI
        MainActivity.doUiThread { callback.onDisconnectTCP(reason, this) }
    }

    // Constructor starts a new thread to handle the blocking outbound connection
    init {
        Log.d(Const.TAG, "Created thread to connect to $address:$port")
        thread(start = true) {
            socketThread()
        }
    }
}
