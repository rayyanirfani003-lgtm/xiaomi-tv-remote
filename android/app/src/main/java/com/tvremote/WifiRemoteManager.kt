package com.tvremote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.*
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

class WifiRemoteManager(private val activity: MainActivity) {
    private var socket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    private val executor = Executors.newCachedThreadPool()
    private val isConnected = AtomicBoolean(false)
    private val singleThread = Executors.newSingleThreadExecutor()

    private var localId = 1
    private var remoteId = 1

    companion object {
        private const val TAG = "WifiRemoteManager"
        private const val ADB_PORT = 5555
        private const val SOCKET_TIMEOUT = 10000
        private const val MAX_PAYLOAD = 1024 * 1024

        // ADB protocol commands (little-endian magic)
        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLSE = 0x45534c43
        private const val CMD_WRTE = 0x45545257

        private const val ADB_VERSION = 0x01000000
        private const val RSA_KEY_SIZE = 2048
    }

    // RSA keys
    private var privateKey: PrivateKey? = null
    private var publicKey: PublicKey? = null
    private var publicKeyAdbString: String = ""

    private val prefs: SharedPreferences
        get() = activity.getSharedPreferences("adb_prefs", Context.MODE_PRIVATE)

    init {
        loadOrGenerateKeys()
    }

    private fun loadOrGenerateKeys() {
        val privateKeyString = prefs.getString("adb_private_key", null)
        val publicKeyString = prefs.getString("adb_public_key", null)

        if (privateKeyString != null && publicKeyString != null) {
            try {
                val keyFactory = KeyFactory.getInstance("RSA")
                val privateKeyBytes = Base64.decode(privateKeyString, Base64.NO_WRAP)
                val publicKeyBytes = Base64.decode(publicKeyString, Base64.NO_WRAP)

                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
                publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))

                publicKeyAdbString = convertToAdbPublicKeyString(publicKeyBytes)
                Log.d(TAG, "Loaded existing RSA keys")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load keys: ${e.message}")
            }
        }

        // Generate new keys
        try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(RSA_KEY_SIZE)
            val keyPair = keyGen.generateKeyPair()

            privateKey = keyPair.private
            publicKey = keyPair.public

            // Save keys
            prefs.edit()
                .putString("adb_private_key", Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP))
                .putString("adb_public_key", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
                .apply()

            publicKeyAdbString = convertToAdbPublicKeyString(keyPair.public.encoded)
            Log.d(TAG, "Generated new RSA keys")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate keys: ${e.message}")
        }
    }

    private fun convertToAdbPublicKeyString(x509Encoded: ByteArray): String {
        try {
            val keyFactory = KeyFactory.getInstance("RSA")
            val rsaPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(x509Encoded)) as RSAPublicKey

            val modulus = rsaPublicKey.modulus
            val exponent = rsaPublicKey.publicExponent

            val nwords = (modulus.bitLength() + 31) / 32

            // n0inv = -1/n[0] mod 2^32
            val n0 = modulus.mod(BigInteger.ONE.shiftLeft(32))
            val n0inv = n0.modInverse(BigInteger.ONE.shiftLeft(32)).negate().mod(BigInteger.ONE.shiftLeft(32))

            // R^2 = (2^(nwords*32))^2 mod n
            val r = BigInteger.ONE.shiftLeft(nwords * 32)
            val r2 = r.multiply(r).mod(modulus)

            val buffer = ByteArrayOutputStream()
            val out = DataOutputStream(buffer)

            // nwords (little-endian)
            out.writeInt(Integer.reverseBytes(nwords))
            // n0inv (little-endian)
            out.writeInt(Integer.reverseBytes(n0inv.toInt()))

            // modulus (little-endian 32-bit words)
            val modulusBytes = modulus.toByteArray()
            val paddedModulus = ByteArray(nwords * 4)
            val srcPos = maxOf(0, modulusBytes.size - nwords * 4)
            val destPos = maxOf(0, nwords * 4 - modulusBytes.size)
            val len = minOf(modulusBytes.size, nwords * 4)
            System.arraycopy(modulusBytes, srcPos, paddedModulus, destPos, len)

            for (i in nwords - 1 downTo 0) {
                val word = ((paddedModulus[i * 4].toInt() and 0xff) shl 24) or
                        ((paddedModulus[i * 4 + 1].toInt() and 0xff) shl 16) or
                        ((paddedModulus[i * 4 + 2].toInt() and 0xff) shl 8) or
                        (paddedModulus[i * 4 + 3].toInt() and 0xff)
                out.writeInt(Integer.reverseBytes(word))
            }

            // R^2 (little-endian 32-bit words)
            val r2Bytes = r2.toByteArray()
            val paddedR2 = ByteArray(nwords * 4)
            val srcPos2 = maxOf(0, r2Bytes.size - nwords * 4)
            val destPos2 = maxOf(0, nwords * 4 - r2Bytes.size)
            val len2 = minOf(r2Bytes.size, nwords * 4)
            System.arraycopy(r2Bytes, srcPos2, paddedR2, destPos2, len2)

            for (i in nwords - 1 downTo 0) {
                val word = ((paddedR2[i * 4].toInt() and 0xff) shl 24) or
                        ((paddedR2[i * 4 + 1].toInt() and 0xff) shl 16) or
                        ((paddedR2[i * 4 + 2].toInt() and 0xff) shl 8) or
                        (paddedR2[i * 4 + 3].toInt() and 0xff)
                out.writeInt(Integer.reverseBytes(word))
            }

            // exponent (little-endian)
            out.writeInt(Integer.reverseBytes(exponent.toInt()))

            out.flush()

            val keyBase64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
            return "$keyBase64 adb@android\0"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert public key: ${e.message}")
            return ""
        }
    }

    fun connect(ip: String, callback: (Boolean) -> Unit) {
        singleThread.execute {
            try {
                Log.d(TAG, "Connecting to $ip:$ADB_PORT")
                activity.runOnUiThread { activity.logToJS("Menghubungkan ke $ip:$ADB_PORT...") }

                socket = Socket()
                socket?.connect(InetSocketAddress(ip, ADB_PORT), SOCKET_TIMEOUT)
                socket?.soTimeout = SOCKET_TIMEOUT

                outputStream = DataOutputStream(BufferedOutputStream(socket!!.getOutputStream()))
                inputStream = DataInputStream(BufferedInputStream(socket!!.getInputStream()))

                // Send CNXN message
                val deviceInfo = "host::\0"
                sendMessage(CMD_CNXN, ADB_VERSION, MAX_PAYLOAD, deviceInfo)

                // Read response
                val response = readMessage()

                when (response.command) {
                    CMD_CNXN -> {
                        Log.d(TAG, "Connected without auth")
                        activity.runOnUiThread { activity.logToJS("✅ Terhubung (tanpa auth)") }
                        isConnected.set(true)
                        callback(true)
                    }
                    CMD_AUTH -> {
                        Log.d(TAG, "Auth required, type=${response.arg0}")
                        activity.runOnUiThread { activity.logToJS("🔐 Autentikasi diperlukan...") }
                        handleAuth(response, callback)
                    }
                    else -> {
                        Log.e(TAG, "Unexpected command: ${Integer.toHexString(response.command)}")
                        activity.runOnUiThread { activity.logToJS("❌ Respon tidak terduga dari TV") }
                        close()
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                activity.runOnUiThread { activity.logToJS("❌ Gagal konek: ${e.message}") }
                close()
                callback(false)
            }
        }
    }

    private fun handleAuth(authMessage: AdbMessage, callback: (Boolean) -> Unit) {
        try {
            // Sign token with RSA private key
            val signature = signData(authMessage.data)

            // Send AUTH with signature (type 2)
            sendMessage(CMD_AUTH, 2, 0, signature)

            val response = readMessage()

            when (response.command) {
                CMD_CNXN -> {
                    Log.d(TAG, "Auth successful (signature)")
                    activity.runOnUiThread { activity.logToJS("✅ Autentikasi berhasil") }
                    isConnected.set(true)
                    callback(true)
                }
                CMD_AUTH -> {
                    // Need to send public key
                    Log.d(TAG, "Sending public key")
                    activity.runOnUiThread { activity.logToJS("🔑 Mengirim public key...") }
                    val publicKeyBytes = publicKeyAdbString.toByteArray(Charsets.UTF_8)
                    sendMessage(CMD_AUTH, 3, 0, publicKeyBytes)

                    val response2 = readMessage()
                    if (response2.command == CMD_CNXN) {
                        Log.d(TAG, "Auth successful (public key)")
                        activity.runOnUiThread { activity.logToJS("✅ Autentikasi berhasil") }
                        isConnected.set(true)
                        callback(true)
                    } else {
                        Log.e(TAG, "Auth failed after public key")
                        activity.runOnUiThread { activity.logToJS("❌ Autentikasi gagal. Hubungkan dari komputer dulu: adb connect <IP_TV>") }
                        close()
                        callback(false)
                    }
                }
                else -> {
                    Log.e(TAG, "Auth failed")
                    activity.runOnUiThread { activity.logToJS("❌ Autentikasi gagal") }
                    close()
                    callback(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth error: ${e.message}")
            activity.runOnUiThread { activity.logToJS("❌ Error autentikasi: ${e.message}") }
            close()
            callback(false)
        }
    }

    private fun signData(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    fun sendCommand(command: String) {
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected")
            return
        }

        singleThread.execute {
            try {
                val shellCmd = "shell:$command"
                sendMessage(CMD_OPEN, localId, 0, shellCmd)

                val response = readMessage()
                if (response.command == CMD_OKAY) {
                    remoteId = response.arg0
                    Log.d(TAG, "Command sent: $command")
                }

                // Close the stream
                sendMessage(CMD_CLSE, localId, remoteId, "")

            } catch (e: Exception) {
                Log.e(TAG, "Send command failed: ${e.message}")
                isConnected.set(false)
                activity.runOnUiThread { activity.logToJS("❌ Gagal kirim perintah: ${e.message}") }
            }
        }
    }

    fun disconnect() {
        isConnected.set(false)
        singleThread.execute {
            close()
        }
    }

    fun scanNetwork(callback: (String) -> Unit) {
        executor.execute {
            try {
                val wifiManager = activity.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
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

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: String) {
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        sendMessage(command, arg0, arg1, dataBytes)
    }

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val out = outputStream ?: return

        val payload = if (data.isNotEmpty()) data else ByteArray(0)
        val crc = crc32(payload)
        val magic = command xor 0xffffffff.toInt()

        val header = ByteArray(24)
        writeIntLE(header, 0, command)
        writeIntLE(header, 4, arg0)
        writeIntLE(header, 8, arg1)
        writeIntLE(header, 12, payload.size)
        writeIntLE(header, 16, crc)
        writeIntLE(header, 20, magic)

        out.write(header)
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    private fun readMessage(): AdbMessage {
        val input = inputStream ?: throw IOException("Not connected")

        val header = ByteArray(24)
        input.readFully(header)

        val command = readIntLE(header, 0)
        val arg0 = readIntLE(header, 4)
        val arg1 = readIntLE(header, 8)
        val dataLength = readIntLE(header, 12)
        val dataCrc = readIntLE(header, 16)
        val magic = readIntLE(header, 20)

        // Verify magic
        val expectedMagic = command xor 0xffffffff.toInt()
        if (magic != expectedMagic) {
            throw IOException("Bad magic: expected ${Integer.toHexString(expectedMagic)}, got ${Integer.toHexString(magic)}")
        }

        val data = if (dataLength > 0) {
            val buffer = ByteArray(dataLength)
            input.readFully(buffer)
            buffer
        } else {
            ByteArray(0)
        }

        return AdbMessage(command, arg0, arg1, data)
    }

    private fun close() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}")
        }
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xff).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xff).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xff).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun readIntLE(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xff) or
                ((buffer[offset + 1].toInt() and 0xff) shl 8) or
                ((buffer[offset + 2].toInt() and 0xff) shl 16) or
                ((buffer[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun crc32(data: ByteArray): Int {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    private data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AdbMessage) return false
            return command == other.command && arg0 == other.arg0 && arg1 == other.arg1
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + arg0
            result = 31 * result + arg1
            return result
        }
    }
}
