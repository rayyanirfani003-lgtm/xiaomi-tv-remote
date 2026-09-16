package com.tvremote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WifiRemoteManager(private val activity: MainActivity) {

    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private val executor = Executors.newCachedThreadPool()
    private val isConnected = AtomicBoolean(false)
    private val singleThread = Executors.newSingleThreadExecutor()
    private var shellStream: ShellStream? = null

    companion object {
        private const val TAG = "WifiRemoteManager"
        private const val ADB_PORT = 5555
        private const val SOCKET_TIMEOUT = 5000
    }

    fun connect(ip: String, callback: (Boolean) -> Unit) {
        singleThread.execute {
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, ADB_PORT), SOCKET_TIMEOUT)

                outputStream = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
                inputStream = DataInputStream(BufferedInputStream(socket!!.getInputStream()))

                // Read CNXN banner
                val banner = readAdbMessage()
                Log.d(TAG, "Device banner: $banner")

                // Open shell stream
                val shellStream = ShellStream(outputStream!!, inputStream!!)
                shellStream.open()

                isConnected.set(true)
                this.shellStream = shellStream
                Log.d(TAG, "ADB shell ready at $ip:$ADB_PORT")
                callback(true)

            } catch (e: Exception) {
                Log.e(TAG, "ADB connection error: ${e.message}")
                close()
                callback(false)
            }
        }
    }

    fun disconnect() {
        isConnected.set(false)
        singleThread.execute {
            close()
        }
    }

    fun sendCommand(command: String) {
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected to ADB")
            return
        }
        singleThread.execute {
            try {
                shellStream?.write(command)
                Log.d(TAG, "ADB command: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Send command failed: ${e.message}")
                isConnected.set(false)
            }
        }
    }

    fun scanNetwork(callback: (String) -> Unit) {
        executor.execute {
            try {
                val wifiManager = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ip = wifiInfo.ipAddress

                if (ip == 0) {
                    Log.w(TAG, "Not connected to WiFi")
                    return@execute
                }

                val subnet = ip and 0xffffff00.toInt()
                val subnetStr = "${subnet and 0xff}.${(subnet shr 8) and 0xff}.${(subnet shr 16) and 0xff}"

                Log.d(TAG, "Scanning subnet: $subnetStr.0/24")

                for (i in 1..254) {
                    val hostIp = "$subnetStr.$i"
                    executor.execute {
                        try {
                            val testSocket = Socket()
                            testSocket.connect(InetSocketAddress(hostIp, ADB_PORT), 1500)
                            testSocket.close()

                            Log.d(TAG, "Found ADB device at: $hostIp")
                            callback(hostIp)
                        } catch (e: Exception) {
                            // Not reachable
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan error: ${e.message}")
            }
        }
    }

    private fun sendAdbMessage(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val lengthHex = String.format("%04x", bytes.size)
        outputStream?.write(lengthHex.toByteArray(Charsets.UTF_8))
        outputStream?.write(bytes)
        outputStream?.flush()
    }

    private fun readAdbMessage(): String {
        val lengthBytes = ByteArray(4)
        inputStream?.readFully(lengthBytes)
        val lengthHex = String(lengthBytes, Charsets.UTF_8)
        val length = lengthHex.toInt(16)
        if (length <= 0) return ""
        val payload = ByteArray(length)
        inputStream?.readFully(payload)
        return String(payload, Charsets.UTF_8)
    }

    private fun close() {
        try {
            shellStream?.close()
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
        }
    }

    /**
     * Handles ADB shell protocol: opens a local stream for shell commands
     */
    private class ShellStream(
        private val output: DataOutputStream,
        private val input: DataInputStream
    ) {
        private val localId = 1

        fun open() {
            // Send "shell:" service
            sendCommand("shell:")
            val response = readResponse()
            if (response != "OKAY") {
                throw IOException("Shell open failed: $response")
            }
        }

        fun write(command: String) {
            val payload = (command + "\n").toByteArray(Charsets.UTF_8)
            // Write length-prefixed payload
            val lengthHex = String.format("%04x", payload.size)
            output.write(lengthHex.toByteArray(Charsets.UTF_8))
            output.write(payload)
            output.flush()
        }

        fun close() {
            try {
                // Send exit command
                write("exit\n")
                Thread.sleep(100)
            } catch (e: Exception) {
                // ignore
            }
        }

        private fun sendCommand(message: String) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            val lengthHex = String.format("%04x", bytes.size)
            output.write(lengthHex.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }

        private fun readResponse(): String {
            val lengthBytes = ByteArray(4)
            input.readFully(lengthBytes)
            val lengthHex = String(lengthBytes, Charsets.UTF_8)
            val length = lengthHex.toInt(16)
            if (length <= 0) return ""
            val payload = ByteArray(length)
            input.readFully(payload)
            return String(payload, Charsets.UTF_8)
        }
    }
}
