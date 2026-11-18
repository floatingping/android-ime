package com.zcode.bopomofo.engine

/** 48 鍵（47 個字元鍵 + 空白）的鍵位定義：大千注音、Shift 字元、中文標點。 */
object Keys {
    val ROWS = listOf("`1234567890-=", "qwertyuiop[]\\", "asdfghjkl;'", "zxcvbnm,./")
    const val BASE = "`1234567890-=qwertyuiop[]\\asdfghjkl;'zxcvbnm,./"
    const val SHIFTED = "~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?"

    fun isKey(c: Char) = BASE.indexOf(c) >= 0
    fun shifted(c: Char): Char = SHIFTED[BASE.indexOf(c)]

    /** 大千鍵盤：聲母、介音、韻母 */
    val ZHUYIN: Map<Char, Char> = mapOf(
        '1' to 'ㄅ', 'q' to 'ㄆ', 'a' to 'ㄇ', 'z' to 'ㄈ',
        '2' to 'ㄉ', 'w' to 'ㄊ', 's' to 'ㄋ', 'x' to 'ㄌ',
        'e' to 'ㄍ', 'd' to 'ㄎ', 'c' to 'ㄏ',
        'r' to 'ㄐ', 'f' to 'ㄑ', 'v' to 'ㄒ',
        '5' to 'ㄓ', 't' to 'ㄔ', 'g' to 'ㄕ', 'b' to 'ㄖ',
        'y' to 'ㄗ', 'h' to 'ㄘ', 'n' to 'ㄙ',
        'u' to 'ㄧ', 'j' to 'ㄨ', 'm' to 'ㄩ',
        '8' to 'ㄚ', 'i' to 'ㄛ', 'k' to 'ㄜ', ',' to 'ㄝ',
        '9' to 'ㄞ', 'o' to 'ㄟ', 'l' to 'ㄠ', '.' to 'ㄡ',
        '0' to 'ㄢ', 'p' to 'ㄣ', ';' to 'ㄤ', '/' to 'ㄥ', '-' to 'ㄦ',
    )

    /** 聲調鍵；一聲由空白鍵輸入 */
    val TONES: Map<Char, Char> = mapOf('3' to 'ˇ', '4' to 'ˋ', '6' to 'ˊ', '7' to '˙')
    const val TONE1 = 'ˉ'

    /** ㄅ模式下非注音、聲調鍵直接輸出的標點 */
    val PUNCT: Map<Char, String> = mapOf(
        '`' to "～", '=' to "＝", '[' to "「", ']' to "」", '\\' to "＼", '\'' to "、",
    )

    /** Ctrl+Alt+, 符號模式下各鍵輸出，同微軟新注音的符號鍵盤（輸入法整合器） */
    val SYMBOLS: Map<Char, String> = run {
        val keys = BASE
        val values = listOf(
            "€", "┌", "┬", "┐", "〝", "〞", "‘", "’", "“", "”", "『", "』", "「",
            "├", "┼", "┤", "※", "〈", "〉", "《", "》", "【", "】", "〔", "〕", "」",
            "└", "┴", "┘", "○", "●", "↑", "↓", "！", "：", "；", "、",
            "─", "│", "◎", "§", "←", "→", "。", "，", "‧", "？",
        )
        require(values.size == keys.length)
        keys.indices.associate { keys[it] to values[it] }
    }

    fun toFullWidth(c: Char): Char = when (c) {
        ' ' -> '\u3000'
        in '!'..'~' -> c + 0xFEE0
        else -> c
    }
}
