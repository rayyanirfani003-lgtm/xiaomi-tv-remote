package com.tvremote

import android.app.Activity
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import java.io.*
import java.net.*
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var wifiManager: WifiRemoteManager
    private lateinit var btManager: BluetoothHidManager

    companion object {
        private const val TAG = "TVRemote"
        private const val REQUEST_ENABLE_BT = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()

        wifiManager = WifiRemoteManager(this)
        btManager = BluetoothHidManager(this) { connected, name ->
            runOnUiThread {
                val js = if (connected) {
                    "window.tvRemote && window.tvRemote.updateConnectionStatus(true);"
                } else {
                    "window.tvRemote && window.tvRemote.updateConnectionStatus(false);"
                }
                webView.evaluateJavascript(js, null)
                updateBtDeviceName(name)
            }
        }
        btManager.init()
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = WebChromeClient()
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    private fun updateBtDeviceName(name: String?) {
        val safeName = name ?: "Tidak terhubung"
        val js = "document.getElementById('bt-device-name') && (document.getElementById('bt-device-name').textContent = '$safeName');"
        webView.evaluateJavascript(js, null)
    }

    fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun logToJS(message: String) {
        val jsSafe = message.replace("'", "\\'").replace("\"", "\\\"")
        val js = "window.tvRemote && window.tvRemote.log('$jsSafe', 'info');"
        webView.evaluateJavascript(js, null)
    }

    fun setIP(ip: String) {
        val js = "document.getElementById('tv-ip') && (document.getElementById('tv-ip').value = '$ip');"
        webView.evaluateJavascript(js, null)
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun sendAdbCommand(keyCode: String) {
            val mapped = mapToAndroidKey(keyCode)
            wifiManager.sendCommand("input keyevent $mapped")
        }

        @JavascriptInterface
        fun sendBluetoothKey(keyCode: String) {
            val mapped = mapToAndroidKey(keyCode).toIntOrNull() ?: return
            btManager.sendKey(mapped)
        }

        @JavascriptInterface
        fun connectAdb(ip: String) {
            wifiManager.connect(ip) { success ->
                runOnUiThread {
                    val js = if (success) {
                        "window.tvRemote && window.tvRemote.updateConnectionStatus(true);"
                    } else {
                        "window.tvRemote && window.tvRemote.updateConnectionStatus(false);"
                    }
                    webView.evaluateJavascript(js, null)
                }
            }
        }

        @JavascriptInterface
        fun disconnectAdb() {
            wifiManager.disconnect()
            runOnUiThread {
                val js = "window.tvRemote && window.tvRemote.updateConnectionStatus(false);"
                webView.evaluateJavascript(js, null)
            }
        }

        @JavascriptInterface
        fun scanNetwork() {
            wifiManager.scanNetwork { ip ->
                runOnUiThread {
                    val js = "document.getElementById('tv-ip') && (document.getElementById('tv-ip').value = '$ip');"
                    webView.evaluateJavascript(js, null)
                }
            }
        }

        @JavascriptInterface
        fun startBluetoothPairing() {
            runOnUiThread {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open Bluetooth settings: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun launchApp(packageName: String) {
            wifiManager.sendCommand("am start -n $packageName")
        }

        @JavascriptInterface
        fun openTVSettings() {
            wifiManager.sendCommand("am start -a android.settings.SETTINGS")
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mapToAndroidKey(keyCode: String): String {
        return when (keyCode) {
            "KEYCODE_POWER" -> "26"
            "KEYCODE_DPAD_UP" -> "19"
            "KEYCODE_DPAD_DOWN" -> "20"
            "KEYCODE_DPAD_LEFT" -> "21"
            "KEYCODE_DPAD_RIGHT" -> "22"
            "KEYCODE_DPAD_CENTER" -> "23"
            "KEYCODE_BACK" -> "4"
            "KEYCODE_HOME" -> "3"
            "KEYCODE_MENU" -> "82"
            "KEYCODE_VOLUME_UP" -> "24"
            "KEYCODE_VOLUME_DOWN" -> "25"
            "KEYCODE_MUTE" -> "164"
            "KEYCODE_CHANNEL_UP" -> "166"
            "KEYCODE_CHANNEL_DOWN" -> "167"
            "KEYCODE_TV_INPUT" -> "178"
            "KEYCODE_SETTINGS" -> "176"
            "KEYCODE_GUIDE" -> "172"
            "KEYCODE_INFO" -> "165"
            "KEYCODE_0" -> "7"
            "KEYCODE_1" -> "8"
            "KEYCODE_2" -> "9"
            "KEYCODE_3" -> "10"
            "KEYCODE_4" -> "11"
            "KEYCODE_5" -> "12"
            "KEYCODE_6" -> "13"
            "KEYCODE_7" -> "14"
            "KEYCODE_8" -> "15"
            "KEYCODE_9" -> "16"
            "KEYCODE_PROG_RED" -> "183"
            "KEYCODE_PROG_GREEN" -> "184"
            "KEYCODE_PROG_YELLOW" -> "185"
            "KEYCODE_PROG_BLUE" -> "186"
            "KEYCODE_PICTURE_MODE" -> "177"
            "KEYCODE_SOUND_MODE" -> "306"
            "KEYCODE_TIMER" -> "189"
            else -> keyCode
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiManager.disconnect()
        btManager.cleanup()
    }
}
