package com.example.autotype

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission") // Checked via ensurePermissions() before every Bluetooth call.
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private lateinit var inputText: EditText
    private lateinit var inputKeyDelay: EditText
    private lateinit var inputRepeat: EditText
    private lateinit var inputInterval: EditText
    private lateinit var inputStartDelay: EditText

    private var devices: List<BluetoothDevice> = emptyList()
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        spinner = findViewById(R.id.deviceSpinner)
        inputText = findViewById(R.id.inputText)
        inputKeyDelay = findViewById(R.id.inputKeyDelay)
        inputRepeat = findViewById(R.id.inputRepeat)
        inputInterval = findViewById(R.id.inputInterval)
        inputStartDelay = findViewById(R.id.inputStartDelay)

        findViewById<Button>(R.id.btnStartKeyboard).setOnClickListener {
            ensurePermissions { HidManager.start(this) }
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            ensurePermissions { refreshDevices() }
        }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            ensurePermissions { connectSelected() }
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startTyping() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTyping() }

        HidManager.setListener(object : HidManager.Listener {
            override fun onStatus(message: String) = showStatus(message)
        })
        TypingService.statusCallback = { showStatus(it) }

        ensurePermissions { refreshDevices() }
    }

    override fun onDestroy() {
        HidManager.setListener(null)
        TypingService.statusCallback = null
        super.onDestroy()
    }

    private fun showStatus(message: String) {
        runOnUiThread { statusText.text = "स्थिति: $message" }
    }

    private fun refreshDevices() {
        devices = HidManager.pairedDevices(this)
        val names = devices.map { it.name ?: it.address }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        if (devices.isEmpty()) {
            showStatus("कोई पेयर डिवाइस नहीं मिला, पहले फ़ोन की Bluetooth सेटिंग में PC/TV से पेयर करें")
        }
    }

    private fun connectSelected() {
        val index = spinner.selectedItemPosition
        if (index < 0 || index >= devices.size) {
            Toast.makeText(this, "पहले कोई डिवाइस चुनें", Toast.LENGTH_SHORT).show()
            return
        }
        HidManager.connect(devices[index])
    }

    private fun startTyping() {
        if (!HidManager.isConnected) {
            Toast.makeText(this, "पहले डिवाइस से जुड़ें", Toast.LENGTH_SHORT).show()
            return
        }
        val text = inputText.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "टेक्स्ट खाली है", Toast.LENGTH_SHORT).show()
            return
        }
        if (TypingService.running) {
            Toast.makeText(this, "टाइपिंग पहले से चल रही है", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, TypingService::class.java).apply {
            putExtra(TypingService.EXTRA_TEXT, text)
            putExtra(TypingService.EXTRA_KEY_DELAY_MS, inputKeyDelay.text.toString().toLongOrNull() ?: 50L)
            putExtra(TypingService.EXTRA_REPEAT, inputRepeat.text.toString().toIntOrNull() ?: 1)
            putExtra(TypingService.EXTRA_INTERVAL_SEC, inputInterval.text.toString().toLongOrNull() ?: 0L)
            putExtra(TypingService.EXTRA_START_DELAY_SEC, inputStartDelay.text.toString().toLongOrNull() ?: 0L)
        }
        startForegroundService(intent)
    }

    private fun stopTyping() {
        startService(Intent(this, TypingService::class.java).setAction(TypingService.ACTION_STOP))
    }

    // ---- Permissions ----

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun ensurePermissions(action: () -> Unit) {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        val btGranted = permissions.indices.none {
            permissions[it] == Manifest.permission.BLUETOOTH_CONNECT &&
                grantResults[it] != PackageManager.PERMISSION_GRANTED
        }
        if (btGranted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, "Bluetooth की अनुमति ज़रूरी है", Toast.LENGTH_LONG).show()
        }
        pendingAction = null
    }
}
