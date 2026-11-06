package com.example.autotype

/**
 * Converts a character into a USB HID keyboard (modifier, keycode) pair.
 * Layout assumed on the receiving device: English (US).
 */
object HidKeyMap {

    const val MOD_NONE = 0x00
    const val MOD_SHIFT = 0x02   // Left Shift

    data class Key(val modifier: Int, val code: Int)

    private val map = HashMap<Char, Key>()

    init {
        // Letters
        for (i in 0 until 26) {
            map['a' + i] = Key(MOD_NONE, 0x04 + i)
            map['A' + i] = Key(MOD_SHIFT, 0x04 + i)
        }
        // Digits 1..9 then 0
        val digits = "1234567890"
        val shifted = "!@#$%^&*()"
        for (i in digits.indices) {
            map[digits[i]] = Key(MOD_NONE, 0x1E + i)
            map[shifted[i]] = Key(MOD_SHIFT, 0x1E + i)
        }
        // Whitespace / control
        map['\n'] = Key(MOD_NONE, 0x28)
        map['\t'] = Key(MOD_NONE, 0x2B)
        map[' '] = Key(MOD_NONE, 0x2C)

        // Punctuation: unshifted, shifted
        fun pair(plain: Char, shift: Char, code: Int) {
            map[plain] = Key(MOD_NONE, code)
            map[shift] = Key(MOD_SHIFT, code)
        }
        pair('-', '_', 0x2D)
        pair('=', '+', 0x2E)
        pair('[', '{', 0x2F)
        pair(']', '}', 0x30)
        pair('\\', '|', 0x31)
        pair(';', ':', 0x33)
        pair('\'', '"', 0x34)
        pair('`', '~', 0x35)
        pair(',', '<', 0x36)
        pair('.', '>', 0x37)
        pair('/', '?', 0x38)
    }

    /** Returns null for characters that cannot be typed (e.g. Hindi, emoji). */
    fun lookup(c: Char): Key? = map[c]
}
