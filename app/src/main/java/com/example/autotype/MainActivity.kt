package com.example.autotype

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

@SuppressLint("MissingPermission") // Checked via ensurePermissions() before every Bluetooth call.
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var spinner: Spinner
    private lateinit var inputText: EditText
    private lateinit var inputKeyDelay: EditText
    private lateinit var inputRepeat: EditText
    private lateinit var inputInterval: EditText
    private lateinit var inputStartDelay: EditText

    private lateinit var formSpinner: Spinner
    private lateinit var formInfo: TextView
    private lateinit var checkHeader: CheckBox

    private var devices: List<BluetoothDevice> = emptyList()
    private var pendingAction: (() -> Unit)? = null

    // Imported table: allRows includes the header row (if any); forms is what the user picks from.
    private var allRows: List<List<String>> = emptyList()
    private var forms: List<List<String>> = emptyList()

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

        formSpinner = findViewById(R.id.formSpinner)
        formInfo = findViewById(R.id.formInfo)
        checkHeader = findViewById(R.id.checkHeader)

        loadSavedRows()
        rebuildForms()
        checkHeader.setOnCheckedChangeListener { _, _ ->
            saveRows()
            rebuildForms()
        }
        formSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fillFromForm(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener { pickFile() }

        ensurePermissions { refreshDevices() }
    }

    // ---- Excel / CSV forms ----

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 200 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("फ़ाइल नहीं खुली")
                val rows = DataImporter.parse(bytes)
                runOnUiThread {
                    allRows = rows
                    saveRows()
                    rebuildForms()
                    showStatus("फ़ाइल लोड हो गई: ${rows.size} पंक्तियाँ")
                }
            } catch (e: Exception) {
                showStatus("फ़ाइल पढ़ने में दिक्कत: ${e.message}")
            }
        }.start()
    }

    private fun rebuildForms() {
        val hasHeader = checkHeader.isChecked && allRows.isNotEmpty()
        val header = if (hasHeader) allRows[0] else null
        forms = if (hasHeader) allRows.drop(1) else allRows

        val labels = forms.mapIndexed { i, row ->
            "${i + 1}) " + row.filter { it.isNotBlank() }.take(2).joinToString(" | ").take(40)
        }
        formSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        formInfo.text = if (allRows.isEmpty()) {
            "अभी कोई फ़ाइल नहीं चुनी"
        } else {
            val fieldCount = forms.maxOfOrNull { it.size } ?: 0
            "कुल ${forms.size} फ़ॉर्म, हर फ़ॉर्म में ज़्यादा से ज़्यादा $fieldCount फ़ील्ड" +
                (header?.let { "\nफ़ील्ड के नाम: " + it.joinToString(", ") } ?: "")
        }
    }

    /** Puts the chosen form's fields in the text box, one field per line, with form mode on. */
    private fun fillFromForm(index: Int) {
        if (index !in forms.indices) return
        val fields = forms[index]
            .map { it.replace("\r", " ").replace("\n", " ").trim() }
            .dropLastWhile { it.isEmpty() }
        inputText.setText(fields.joinToString("\n"))
        findViewById<CheckBox>(R.id.checkFormMode).isChecked = true

        val unsupported = fields.joinToString("").count { HidKeyMap.lookup(it) == null }
        if (unsupported > 0) {
            showStatus("ध्यान दें: इस फ़ॉर्म में $unsupported अक्षर (हिंदी या ख़ास चिह्न) टाइप नहीं होंगे")
        }
    }

    private fun saveRows() {
        val arr = JSONArray()
        allRows.forEach { arr.put(JSONArray(it)) }
        getSharedPreferences("forms", MODE_PRIVATE).edit()
            .putString("rows", arr.toString())
            .putBoolean("header", checkHeader.isChecked)
            .apply()
    }

    private fun loadSavedRows() {
        val prefs = getSharedPreferences("forms", MODE_PRIVATE)
        checkHeader.isChecked = prefs.getBoolean("header", true)
        val saved = prefs.getString("rows", null) ?: return
        try {
            val arr = JSONArray(saved)
            allRows = (0 until arr.length()).map { i ->
                val r = arr.getJSONArray(i)
                (0 until r.length()).map { j -> r.getString(j) }
            }
        } catch (e: Exception) {
            allRows = emptyList()
        }
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
        val raw = inputText.text.toString()
        val formMode = findViewById<CheckBox>(R.id.checkFormMode).isChecked
        val enterAtEnd = findViewById<CheckBox>(R.id.checkEnterAtEnd).isChecked

        // Form mode: each line is one field. Tab moves to the next field.
        val text = if (formMode) {
            raw.replace("\r", "")
                .split("\n")
                .joinToString("\t") + (if (enterAtEnd) "\n" else "")
        } else {
            raw + (if (enterAtEnd) "\n" else "")
        }
        if (raw.isEmpty()) {
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
