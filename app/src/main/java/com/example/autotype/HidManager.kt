package com.example.autotype

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.Executors

/**
 * Makes this phone appear as a Bluetooth HID keyboard and sends key reports.
 * One shared instance (object) is used by both the Activity and the Service.
 */
@SuppressLint("MissingPermission") // Permission is requested in MainActivity before use.
object HidManager {

    private const val REPORT_ID = 1

    // Standard keyboard report descriptor (Report ID 1, 8-byte input report).
    private val DESCRIPTOR = byteArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), REPORT_ID.toByte(), //   Report ID (1)
        0x05, 0x07,             //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),    //   Usage Minimum (224)
        0x29, 0xE7.toByte(),    //   Usage Maximum (231)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x08,    //   Report Count (8)
        0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute) - modifiers
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x75, 0x08,             //   Report Size (8)
        0x81.toByte(), 0x01,    //   Input (Constant) - reserved byte
        0x95.toByte(), 0x05,    //   Report Count (5)
        0x75, 0x01,             //   Report Size (1)
        0x05, 0x08,             //   Usage Page (LEDs)
        0x19, 0x01,             //   Usage Minimum (1)
        0x29, 0x05,             //   Usage Maximum (5)
        0x91.toByte(), 0x02,    //   Output (Data, Variable, Absolute) - LEDs
        0x95.toByte(), 0x01,    //   Report Count (1)
        0x75, 0x03,             //   Report Size (3)
        0x91.toByte(), 0x01,    //   Output (Constant) - LED padding
        0x95.toByte(), 0x06,    //   Report Count (6)
        0x75, 0x08,             //   Report Size (8)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x05, 0x07,             //   Usage Page (Key Codes)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x81.toByte(), 0x00,    //   Input (Data, Array) - 6 key slots
        0xC0.toByte()           // End Collection
    )

    interface Listener {
        fun onStatus(message: String)
    }

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var listener: Listener? = null
    private val executor = Executors.newSingleThreadExecutor()

    val isConnected: Boolean get() = hid != null && host != null

    fun setListener(l: Listener?) {
        listener = l
    }

    private fun notify(msg: String) {
        listener?.onStatus(msg)
    }

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /** Paired devices the user can pick as the target (PC, TV, tablet...). */
    fun pairedDevices(context: Context): List<BluetoothDevice> =
        adapter(context)?.bondedDevices?.toList() ?: emptyList()

    /** Step 1: acquire the HID profile and register this phone as a keyboard. */
    fun start(context: Context) {
        val adapter = adapter(context)
        if (adapter == null || !adapter.isEnabled) {
            notify("Bluetooth is off. Turn it on first")
            return
        }
        if (registered) {
            notify("Keyboard is already registered")
            return
        }
        adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val device = proxy as BluetoothHidDevice
                hid = device
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "AutoType Keyboard",
                    "Phone as keyboard",
                    "AutoType",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                    DESCRIPTOR
                )
                val ok = device.registerApp(sdp, null, null, executor, callback)
                if (!ok) notify("Could not register (this phone may not support HID)")
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hid = null
                    host = null
                    registered = false
                    notify("HID service stopped")
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    /** Step 2: connect to a paired device. */
    fun connect(device: BluetoothDevice) {
        val h = hid
        if (h == null || !registered) {
            notify("First tap 'Make phone a keyboard'")
            return
        }
        notify("Connecting to ${device.name ?: device.address}…")
        h.connect(device)
    }

    fun stop() {
        hid?.let {
            host?.let { d -> it.disconnect(d) }
            it.unregisterApp()
        }
        host = null
        registered = false
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registeredNow: Boolean) {
            registered = registeredNow
            notify(if (registeredNow) "Keyboard is ready. Now choose a device and tap Connect" else "Keyboard is not registered")
        }

        // The host (PC/TV) may ask for the current report. If we never answer,
        // many hosts drop the connection, so always reply.
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, ByteArray(8))
        }

        // Host sets e.g. keyboard LEDs (Caps Lock). Acknowledge it.
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        // Host switches between boot/report protocol. Acknowledge it.
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    notify("Connected: ${device.name ?: device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    notify("Connection lost")
                }
            }
        }
    }

    /** Sends one key press followed by a release. Returns false if it could not be sent. */
    fun typeChar(c: Char, keyDelayMs: Long): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        val key = HidKeyMap.lookup(c) ?: return true // unsupported char: skip silently

        val down = ByteArray(8)
        down[0] = key.modifier.toByte()
        down[2] = key.code.toByte()
        if (!h.sendReport(d, REPORT_ID, down)) return false

        // Short hold so the host registers the press.
        Thread.sleep(8)

        h.sendReport(d, REPORT_ID, ByteArray(8)) // release all keys
        if (keyDelayMs > 0) Thread.sleep(keyDelayMs)
        return true
    }
}
