package com.zcode.bopomofo.engine

import java.io.Reader

data class Entry(val text: String, val freq: Int)

/**
 * 字典（單字 → 注音含聲調）與詞典（詞 → 注音、詞頻），加上使用者挑字的 LRU。
 *
 * 字典格式：`字 讀音 頻率`，例如 `你 ㄋㄧˇ 9000`
 * 詞典格式：`詞 頻率 讀音1 讀音2 …`，例如 `天氣 3000 ㄊㄧㄢ ㄑㄧˋ`
 * 欄位以單一空白分隔，`#` 開頭為註解；輕聲可寫 ˙ 或 ·。
 * 詞典有十幾萬行，解析刻意不用正規表示式與 split，以免在手機上過慢。
 */
class Dictionary {
    private val chars = HashMap<String, MutableList<Entry>>(32_768)
    private val words = HashMap<String, MutableList<Entry>>(262_144)
    private val lru = HashMap<String, ArrayDeque<String>>()
    var maxWordLength = 1
        private set

    fun loadChars(reader: Reader) {
        forEachLine(reader) { line ->
            val a = line.indexOf(' ')
            if (a <= 0) return@forEachLine
            val b = line.indexOf(' ', a + 1)
            val reading = normalize(if (b < 0) line.substring(a + 1) else line.substring(a + 1, b))
            val freq = if (b < 0) 1 else parseInt(line, b + 1, line.length)
            chars.getOrPut(reading) { ArrayList(4) }.add(Entry(line.substring(0, a), freq))
        }
        chars.values.forEach(::sortByFreq)
    }

    fun loadWords(reader: Reader) {
        forEachLine(reader) { line ->
            val a = line.indexOf(' ')
            val b = if (a > 0) line.indexOf(' ', a + 1) else -1
            if (b < 0) return@forEachLine
            val key = normalize(line.substring(b + 1))
            var n = 1
            for (i in key.indices) if (key[i] == ' ') n++
            if (a != n) return@forEachLine // 詞的字數須等於讀音數
            words.getOrPut(key) { ArrayList(2) }.add(Entry(line.substring(0, a), parseInt(line, a + 1, b)))
            if (n > maxWordLength) maxWordLength = n
        }
        words.values.forEach(::sortByFreq)
    }

    private inline fun forEachLine(reader: Reader, action: (String) -> Unit) {
        val text = reader.readText()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            var stop = end
            if (stop > start && text[stop - 1] == '\r') stop--
            if (stop > start && text[start] != '#') action(text.substring(start, stop))
            start = end + 1
        }
    }

    private fun parseInt(s: String, from: Int, to: Int): Int {
        var v = 0
        for (i in from until to) {
            val d = s[i] - '0'
            if (d !in 0..9) return maxOf(v, 1)
            v = v * 10 + d
        }
        return maxOf(v, 1)
    }

    /** 產生字典的腳本已依頻率排好，這裡只在未排序時才排 */
    private fun sortByFreq(list: MutableList<Entry>) {
        for (i in 1 until list.size) {
            if (list[i].freq > list[i - 1].freq) {
                list.sortByDescending { it.freq }
                return
            }
        }
    }

    fun hasReading(reading: String) = chars.containsKey(reading)

    /**
     * 候選字：LRU 近期挑過的在前，其餘依頻率。
     * 單一注音符號＋空白（如 ㄇ、ㄨ、ㄛ）預設為符號本身，方便打「你好ㄇ」「ㄨㄛ很好」這類注音文。
     */
    fun candidates(reading: String): List<String> {
        val all = chars[reading]?.map { it.text } ?: return emptyList()
        val recent = lru[reading]?.filter { it in all } ?: emptyList()
        val rest = all.filter { it !in recent }.toMutableList()
        if (reading.length == 1 && rest.remove(reading)) rest.add(0, reading)
        return recent + rest
    }

    fun bestChar(reading: String): String? = candidates(reading).firstOrNull()

    fun charFreq(reading: String, text: String): Int =
        chars[reading]?.firstOrNull { it.text == text }?.freq ?: 1

    fun topFreq(reading: String): Int = chars[reading]?.firstOrNull()?.freq ?: 1

    fun words(readings: List<String>): List<Entry> = words[readings.joinToString(" ")] ?: emptyList()

    fun use(reading: String, text: String) {
        val q = lru.getOrPut(reading) { ArrayDeque() }
        q.remove(text)
        q.addFirst(text)
        while (q.size > LRU_SIZE) q.removeLast()
    }

    /** 每行：讀音 TAB 候選1 空白 候選2 …（候選可為多字，如「ㄨㄛ」） */
    fun exportLru(): String =
        lru.entries.joinToString("\n") { (r, q) -> r + "\t" + q.joinToString(" ") }

    fun importLru(data: String) {
        data.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val items = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }.take(LRU_SIZE)
            lru[line.substring(0, tab)] = ArrayDeque(items)
        }
    }

    private fun normalize(reading: String) =
        if (reading.indexOf('·') < 0 && reading.indexOf('ˉ') < 0) reading
        else reading.replace('·', '˙').replace("ˉ", "")

    companion object {
        /** 每個讀音記住最近挑過的 10 個字 */
        const val LRU_SIZE = 10
    }
}
