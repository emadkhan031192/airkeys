package com.airkeys

/** Pure, unit-testable layouts for the in-app Smart Keyboard. */
enum class SmartLayout { ENGLISH, URDU, SYMBOLS }

object KeyboardLayouts {
    val ENGLISH_ROWS: List<List<String>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    val URDU_ROWS: List<List<String>> = listOf(
        listOf("۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹", "۰"),
        listOf("ا", "ب", "پ", "ت", "ٹ", "ث", "ج", "چ", "ح", "خ"),
        listOf("د", "ڈ", "ذ", "ر", "ڑ", "ز", "ژ", "س", "ش", "ص"),
        listOf("ض", "ط", "ظ", "ع", "غ", "ف", "ق", "ک", "گ", "ل"),
        listOf("م", "ن", "و", "ہ", "ھ", "ی", "ے"),
    )
    val SYMBOL_ROWS: List<List<String>> = listOf(
        listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
        listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":"),
        listOf("'", "\"", ",", ".", "?", "/", "\\", "|", "<", ">"),
    )
    val EMOJI_ROW: List<String> = listOf("😀", "😂", "❤️", "👍", "🎉", "🔥", "🙏", "👋")

    fun rowsFor(layout: SmartLayout): List<List<String>> = when (layout) {
        SmartLayout.ENGLISH -> ENGLISH_ROWS
        SmartLayout.URDU -> URDU_ROWS
        SmartLayout.SYMBOLS -> SYMBOL_ROWS
    }

    /** One-shot shift for Latin letters; Urdu/symbol keys pass through. */
    fun applyShift(key: String, shift: Boolean): String {
        if (!shift || key.length != 1) return key
        val c = key[0]
        return if (c in 'a'..'z') c.uppercaseChar().toString() else key
    }
}
