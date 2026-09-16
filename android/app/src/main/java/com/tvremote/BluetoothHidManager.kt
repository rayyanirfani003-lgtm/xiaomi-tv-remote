package com.tvremote

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

class BluetoothHidManager(
    private val activity: MainActivity,
    private val statusCallback: (Boolean, String?) -> Unit
) {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private val executor = Executors.newCachedThreadPool()
    private var callback: BluetoothHidDevice.Callback? = null

    companion object {
        private const val TAG = "BluetoothHidManager"

        // Standard HID Keyboard Descriptor (Boot Protocol)
        private val KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05, 0x01,  // Usage Page (Generic Desktop Ctrls)
            0x09, 0x06,  // Usage (Keyboard)
            0xA1.toByte(), 0x01,  // Collection (Application)
            0x05, 0x07,  //   Usage Page (Kbrd/Keypad)
            0x19, 0xE0.toByte(),  //   Usage Minimum (0xE0)
            0x29, 0xE7.toByte(),  //   Usage Maximum (0xE7)
            0x15, 0x00,  //   Logical Minimum (0)
            0x25, 0x01,  //   Logical Maximum (1)
            0x75, 0x01,  //   Report Size (1)
            0x95.toByte(), 0x08,  //   Report Count (8)
            0x81.toByte(), 0x02,  //   Input (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position)
            0x95.toByte(), 0x01,  //   Report Count (1)
            0x75, 0x08,  //   Report Size (8)
            0x81.toByte(), 0x01,  //   Input (Const,Array,Abs,No Wrap,Linear,Preferred State,No Null Position)
            0x95.toByte(), 0x05,  //   Report Count (5)
            0x75, 0x01,  //   Report Size (1)
            0x05, 0x08,  //   Usage Page (LEDs)
            0x19, 0x01,  //   Usage Minimum (Num Lock)
            0x29, 0x05,  //   Usage Maximum (Kana)
            0x91.toByte(), 0x02,  //   Output (Data,Var,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
            0x95.toByte(), 0x01,  //   Report Count (1)
            0x75, 0x03,  //   Report Size (3)
            0x91.toByte(), 0x01,  //   Output (Const,Array,Abs,No Wrap,Linear,Preferred State,No Null Position,Non-volatile)
            0x95.toByte(), 0x06,  //   Report Count (6)
            0x75, 0x08,  //   Report Size (8)
            0x15, 0x00,  //   Logical Minimum (0)
            0x25, 0x65,  //   Logical Maximum (101)
            0x05, 0x07,  //   Usage Page (Kbrd/Keypad)
            0x19, 0x00,  //   Usage Minimum (0x00)
            0x29, 0x65,  //   Usage Maximum (0x65)
            0x81.toByte(), 0x00,  //   Input (Data,Array,Abs,No Wrap,Linear,Preferred State,No Null Position)
            0xC0.toByte()  // End Collection
        )
    }

    fun init() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled, requesting enable...")
            return
        }

        registerHidApp()
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val serviceListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as? BluetoothHidDevice
                    Log.d(TAG, "HID service connected")

                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "TV Remote",
                        "Xiaomi TV Bluetooth Remote",
                        "com.tvremote",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        KEYBOARD_DESCRIPTOR
                    )

                    callback = object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                            handleConnectionStateChanged(device, state)
                        }

                        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                            Log.d(TAG, "HID app registered: $registered")
                            if (registered) {
                                tryConnectToPaired()
                            }
                        }
                    }

                    try {
                        hidDevice?.registerApp(sdp, null, null, executor, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to register HID app: ${e.message}")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = null
                    Log.d(TAG, "HID service disconnected")
                }
            }
        }

        bluetoothAdapter?.getProfileProxy(activity, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionStateChanged(device: BluetoothDevice?, state: Int) {
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                connectedDevice = device
                val name = device?.name ?: "Unknown"
                Log.d(TAG, "BT HID Connected to: $name")
                statusCallback(true, name)
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                connectedDevice = null
                Log.d(TAG, "BT HID Disconnected")
                statusCallback(false, null)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryConnectToPaired() {
        val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            Log.d(TAG, "No paired devices found")
            return
        }

        Log.d(TAG, "Found ${pairedDevices.size} paired device(s):")
        pairedDevices.forEach { device ->
            val name = device.name ?: "Unknown"
            val address = device.address
            Log.d(TAG, "  - $name ($address)")
        }

        // Try to connect to ALL paired devices (Xiaomi TV may have different name)
        pairedDevices.forEach { device ->
            val name = device.name ?: "Unknown"
            try {
                Log.d(TAG, "Trying to connect to: $name (${device.address})")
                hidDevice?.connect(device)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $name: ${e.message}")
            }
        }
        Log.d(TAG, "No device accepted HID connection from ${pairedDevices.size} paired devices")
    }

    @SuppressLint("MissingPermission")
    fun sendKey(keycode: Int) {
        val device = connectedDevice
        val hid = hidDevice

        if (device == null || hid == null) {
            Log.w(TAG, "Cannot send BT key: not connected")
            return
        }

        try {
            // Build keyboard report: [modifiers, reserved, keycode1, keycode2, ...]
            val report = ByteArray(8)
            report[2] = (keycode and 0xFF).toByte()

            hid.sendReport(device, 0, report)

            // Send key release after short delay
            Thread.sleep(50)

            val releaseReport = ByteArray(8) // All zeros = release
            hid.sendReport(device, 0, releaseReport)

            Log.d(TAG, "BT key sent: $keycode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send BT key: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        hidDevice?.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connectedDevice?.let {
            hidDevice?.disconnect(it)
        }
    }

    fun cleanup() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        }
    }
}
