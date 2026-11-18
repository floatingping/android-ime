package com.zcode.bopomofo.engine

import kotlin.math.ln

/** 輸入詞中的一格：中文字（有讀音）或英文/標點等原樣字元（無讀音） */
class Cell(val reading: String?, var text: String, var fixed: Boolean)

/**
 * 依詞典為未被使用者挑過的中文字重新選字。
 * 連續中文字為一段，段內以動態規劃找最高分的詞/字切分；
 * 被挑過的字（fixed）不會變動，含該字的詞必須與其相符。
 */
object Converter {
    private const val TOTAL = 1e7

    fun convert(cells: List<Cell>, dict: Dictionary) {
        var i = 0
        while (i < cells.size) {
            if (cells[i].reading == null) { i++; continue }
            var j = i
            while (j < cells.size && cells[j].reading != null) j++
            convertRun(cells.subList(i, j), dict)
            i = j
        }
    }

    private fun convertRun(run: List<Cell>, dict: Dictionary) {
        val n = run.size
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val pick = arrayOfNulls<String>(n + 1)
        val from = IntArray(n + 1)
        best[0] = 0.0
        for (end in 1..n) {
            for (len in 1..minOf(dict.maxWordLength, end)) {
                val start = end - len
                if (best[start] == Double.NEGATIVE_INFINITY) continue
                val (text, score) = segment(run.subList(start, end), dict) ?: continue
                val total = best[start] + score
                if (total > best[end]) {
                    best[end] = total
                    pick[end] = text
                    from[end] = start
                }
            }
        }
        var end = n
        while (end > 0) {
            val start = from[end]
            val text = pick[end]!!
            for (k in start until end) {
                if (!run[k].fixed) run[k].text = text[k - start].toString()
            }
            end = start
        }
    }

    private fun segment(seg: List<Cell>, dict: Dictionary): Pair<String, Double>? {
        if (seg.size == 1) {
            val cell = seg[0]
            val reading = cell.reading!!
            if (cell.fixed) return cell.text to score(dict.charFreq(reading, cell.text))
            val text = dict.bestChar(reading) ?: cell.text
            return text to score(dict.topFreq(reading))
        }
        val entry = dict.words(seg.map { it.reading!! }).firstOrNull { e ->
            seg.indices.all { k -> !seg[k].fixed || seg[k].text == e.text[k].toString() }
        } ?: return null
        return entry.text to score(entry.freq)
    }

    private fun score(freq: Int) = ln(maxOf(freq, 1) / TOTAL)
}
