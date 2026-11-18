package com.zcode.bopomofo.engine

/** 湊字中的注音音節：聲母、介音、韻母各一格，以聲調結尾。 */
class Syllable {
    var initial: Char? = null
        private set
    var medial: Char? = null
        private set
    var final: Char? = null
        private set
    var tone: Char? = null
        private set
    /** 已輸入聲調但字典查無此音 → 紅線 */
    var failed = false

    val isEmpty get() = initial == null && medial == null && final == null && tone == null
    val hasTone get() = tone != null

    fun addSymbol(c: Char) {
        when {
            INITIALS.indexOf(c) >= 0 -> initial = c
            MEDIALS.indexOf(c) >= 0 -> medial = c
            FINALS.indexOf(c) >= 0 -> final = c
            else -> throw IllegalArgumentException("not a bopomofo symbol: $c")
        }
        tone = null
        failed = false
    }

    fun setTone(t: Char) {
        tone = t
        failed = false
    }

    fun backspace() {
        when {
            tone != null -> tone = null
            final != null -> final = null
            medial != null -> medial = null
            else -> initial = null
        }
        failed = false
    }

    fun clear() {
        initial = null; medial = null; final = null; tone = null; failed = false
    }

    /** 查字典用的讀音，一聲不標 */
    fun reading(): String = symbols() + (tone?.takeIf { it != Keys.TONE1 } ?: "")

    /** 顯示用，一聲顯示 ˉ */
    fun display(): String = symbols() + (tone ?: "")

    private fun symbols() = listOfNotNull(initial, medial, final).joinToString("")

    companion object {
        const val INITIALS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ"
        const val MEDIALS = "ㄧㄨㄩ"
        const val FINALS = "ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
    }
}
