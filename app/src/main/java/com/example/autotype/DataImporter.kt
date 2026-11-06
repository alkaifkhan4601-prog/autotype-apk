package com.example.autotype

import android.util.Xml
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Reads a table (rows of cells) from an .xlsx or .csv file.
 * One row = one form, one column = one field.
 */
object DataImporter {

    fun parse(bytes: ByteArray): List<List<String>> {
        if (bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()
        ) {
            throw IllegalArgumentException("यह पुरानी .xls फ़ाइल है। Excel में Save As करके .xlsx या CSV चुनें")
        }
        val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val rows = if (isZip) parseXlsx(bytes) else parseCsv(String(bytes, Charsets.UTF_8))
        if (rows.isEmpty()) throw IllegalArgumentException("फ़ाइल में कोई डेटा नहीं मिला")
        return rows
    }

    // ---------------- CSV ----------------

    private fun parseCsv(input: String): List<List<String>> {
        val text = input.removePrefix("﻿")
        val firstLine = text.lineSequence().firstOrNull() ?: ""
        val delimiter = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','

        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when {
                    c == '"' -> inQuotes = true
                    c == delimiter -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                    }
                    c == '\n' || c == '\r' -> {
                        if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                        row.add(cell.toString())
                        cell.setLength(0)
                        addRow(rows, row)
                        row = ArrayList()
                    }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            addRow(rows, row)
        }
        return rows
    }

    private fun addRow(rows: ArrayList<List<String>>, row: List<String>) {
        if (row.any { it.isNotBlank() }) rows.add(row)
    }

    // ---------------- XLSX ----------------

    private fun parseXlsx(bytes: ByteArray): List<List<String>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "xl/sharedStrings.xml" ||
                    (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                ) {
                    files[name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }

        val sheetName = files.keys
            .filter { it.startsWith("xl/worksheets/") }
            .minByOrNull { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
            ?: throw IllegalArgumentException("xlsx में कोई शीट नहीं मिली")

        val shared = files["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        return parseSheet(files.getValue(sheetName), shared)
    }

    private fun newParser(data: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), null)
        return p
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val p = newParser(data)
        val list = ArrayList<String>()
        var sb = StringBuilder()
        var inPhonetic = false
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inPhonetic = true
                    "t" -> {
                        val t = p.nextText()
                        if (!inPhonetic) sb.append(t)
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (p.name) {
                    "rPh" -> inPhonetic = false
                    "si" -> list.add(sb.toString())
                }
            }
            event = p.next()
        }
        return list
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): List<List<String>> {
        val p = newParser(data)
        val rows = ArrayList<List<String>>()
        var current: ArrayList<String>? = null
        var type = ""
        var col = 0
        var nextCol = 0
        var value: String? = null

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> {
                        current = ArrayList()
                        nextCol = 0
                    }
                    "c" -> {
                        type = p.getAttributeValue(null, "t") ?: ""
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) columnIndex(ref) else nextCol
                        value = null
                    }
                    "v" -> value = p.nextText()
                    "t" -> if (type == "inlineStr") value = (value ?: "") + p.nextText()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (p.name) {
                    "c" -> {
                        val row = current
                        if (row != null) {
                            while (row.size < col) row.add("")
                            val text = when (type) {
                                "s" -> shared.getOrElse(value?.toIntOrNull() ?: -1) { "" }
                                "b" -> if (value == "1") "TRUE" else "FALSE"
                                "inlineStr", "str" -> value ?: ""
                                else -> cleanNumber(value ?: "")
                            }
                            row.add(text)
                            nextCol = col + 1
                        }
                    }
                    "row" -> {
                        val row = current
                        if (row != null) addRow(rows, row)
                        current = null
                    }
                }
            }
            event = p.next()
        }
        return rows
    }

    /** "C5" -> 2, "AA1" -> 26 */
    private fun columnIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    /** Excel stores 5 as "5" but sometimes 9.8765E9; turn those into plain digits. */
    private fun cleanNumber(v: String): String {
        if (v.isEmpty()) return v
        return try {
            if (v.contains('E') || v.contains('e') || v.endsWith(".0")) {
                BigDecimal(v).stripTrailingZeros().toPlainString()
            } else v
        } catch (e: NumberFormatException) {
            v
        }
    }
}
