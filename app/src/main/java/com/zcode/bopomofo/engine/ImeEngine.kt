package com.zcode.bopomofo.engine

enum class InputMode { BOPOMOFO, ALPHA }

/** 鍵盤輸入事件，軟鍵盤與實體鍵盤共用 */
sealed interface KeyInput {
    /** 47 個字元鍵之一，[base] 為未按 Shift 時的 ASCII 字元 */
    data class Printable(val base: Char, val shift: Boolean = false) : KeyInput
    /** 數字鍵盤：一律輸入字元本身（不當注音、聲調或中文標點） */
    data class Numpad(val char: Char) : KeyInput
    data class Space(val shift: Boolean = false) : KeyInput
    object Backspace : KeyInput
    object Enter : KeyInput
    object Left : KeyInput
    object Right : KeyInput
    object Up : KeyInput
    object Down : KeyInput
    /** 單按 Shift：切換 ㄅ/a */
    object ToggleAlpha : KeyInput
    /** Ctrl+Alt+,（或螢幕鍵盤的 Ctrl+Alt 鍵）：切換符號模式，下一鍵輸入符號鍵盤上的符號 */
    object TogglePunct : KeyInput
}

enum class Result {
    HANDLED,
    /** 未輸入狀態下的控制鍵，由宿主直接作用於目標（移動游標、消字、換行） */
    PASS,
}

class CandidateState(val cellIndex: Int, val items: List<String>, var index: Int) {
    val page get() = index / PAGE_SIZE
    val pageCount get() = (items.size + PAGE_SIZE - 1) / PAGE_SIZE
    fun pageItems(): List<String> = items.subList(page * PAGE_SIZE, minOf(items.size, (page + 1) * PAGE_SIZE))

    companion object {
        const val PAGE_SIZE = 9
    }
}

/**
 * 輸入法狀態機：
 * 未輸入 → (注音/聲調) → 輸入詞（內含湊字、候選字、標點模式）→ Enter 送出。
 */
class ImeEngine(dict: Dictionary, private val commit: (String) -> Unit) {
    /** 字典可在背景載入完成後替換；載入前打好的注音以新字典重查，不清掉輸入詞 */
    var dict: Dictionary = dict
        set(value) {
            field = value
            candidates = null
            if (syllable.failed) completeSyllable()
            reconvert()
        }

    var mode = InputMode.BOPOMOFO
        private set
    var fullWidth = false
        private set
    var punctMode = false
        private set

    val cells = mutableListOf<Cell>()
    /** 游標位置 0..cells.size；小於 size 時指著 cells[cursor] */
    var cursor = 0
        private set
    val syllable = Syllable()
    var candidates: CandidateState? = null
        private set

    val isComposing get() = cells.isNotEmpty() || !syllable.isEmpty

    fun handle(input: KeyInput): Result {
        when (input) {
            KeyInput.ToggleAlpha -> {
                mode = if (mode == InputMode.BOPOMOFO) InputMode.ALPHA else InputMode.BOPOMOFO
                return Result.HANDLED
            }
            is KeyInput.Space -> if (input.shift) {
                fullWidth = !fullWidth
                return Result.HANDLED
            }
            else -> {}
        }
        candidates?.let { return handleCandidates(it, input) }
        if (!syllable.isEmpty) return handleSyllable(input)
        if (cells.isEmpty()) return handleIdle(input)
        return handleComposition(input)
    }

    /** 點選候選列第 [slot] 個（0 起算）；超出範圍不反應 */
    fun pickCandidate(slot: Int) {
        val c = candidates ?: return
        val pos = c.page * CandidateState.PAGE_SIZE + slot
        if (slot in 0 until CandidateState.PAGE_SIZE && pos < c.items.size) select(c, pos)
    }

    fun reset() {
        cells.clear()
        cursor = 0
        syllable.clear()
        candidates = null
        punctMode = false
    }

    /** 送出輸入詞並回到未輸入；未完成的注音捨棄 */
    fun commitAll() {
        val text = cells.joinToString("") { it.text }
        reset()
        if (text.isNotEmpty()) commit(text)
    }

    // ---- 未輸入：字元直接送出 ----

    private fun handleIdle(input: KeyInput): Result {
        when (input) {
            is KeyInput.Printable -> when {
                punctMode -> { commit(Keys.SYMBOLS.getValue(input.base)); punctMode = false }
                startsSyllable(input) -> typeSyllable(input.base)
                else -> commit(charFor(input))
            }
            is KeyInput.Numpad -> commit(width(input.char))
            is KeyInput.Space -> if (punctMode) {
                commit("\u3000")
                punctMode = false
            } else {
                commit(width(' '))
            }
            KeyInput.TogglePunct -> punctMode = !punctMode
            KeyInput.Backspace, KeyInput.Enter, KeyInput.Left, KeyInput.Right, KeyInput.Up, KeyInput.Down -> return Result.PASS
            KeyInput.ToggleAlpha -> {}
        }
        return Result.HANDLED
    }

    // ---- 湊字：上下左右、Enter 無反應；英文、數字等字元捨棄湊字後照常輸入 ----

    private fun handleSyllable(input: KeyInput): Result {
        when (input) {
            is KeyInput.Printable -> if (startsSyllable(input)) typeSyllable(input.base) else return dropSyllableAndHandle(input)
            is KeyInput.Numpad -> return dropSyllableAndHandle(input)
            is KeyInput.Space -> { syllable.setTone(Keys.TONE1); completeSyllable() }
            KeyInput.Backspace -> syllable.backspace()
            else -> {}
        }
        return Result.HANDLED
    }

    /** 英文、數字等字元：捨棄湊字，該鍵改依未輸入（輸入詞中則插在游標處）處理 */
    private fun dropSyllableAndHandle(input: KeyInput): Result {
        syllable.clear()
        return if (cells.isEmpty()) handleIdle(input) else handleComposition(input)
    }

    private fun startsSyllable(input: KeyInput.Printable) =
        mode == InputMode.BOPOMOFO && !input.shift &&
            (Keys.ZHUYIN.containsKey(input.base) || Keys.TONES.containsKey(input.base))

    private fun typeSyllable(base: Char) {
        val tone = Keys.TONES[base]
        if (tone != null) {
            syllable.setTone(tone)
            completeSyllable()
        } else {
            syllable.addSymbol(Keys.ZHUYIN.getValue(base))
        }
    }

    private fun completeSyllable() {
        val reading = syllable.reading()
        val text = dict.bestChar(reading)
        if (text == null) {
            syllable.failed = true
            return
        }
        cells.add(cursor, Cell(reading, text, fixed = false))
        cursor++
        syllable.clear()
        reconvert()
    }

    // ---- 輸入詞 ----

    private fun handleComposition(input: KeyInput): Result {
        when (input) {
            is KeyInput.Printable -> when {
                punctMode -> { insertLiteral(Keys.SYMBOLS.getValue(input.base)); punctMode = false }
                startsSyllable(input) -> typeSyllable(input.base)
                else -> insertLiteral(charFor(input))
            }
            is KeyInput.Numpad -> insertLiteral(width(input.char))
            is KeyInput.Space -> if (punctMode) {
                insertLiteral("\u3000")
                punctMode = false
            } else {
                insertLiteral(width(' '))
            }
            KeyInput.Backspace -> if (cursor > 0) {
                cells.removeAt(cursor - 1)
                cursor--
                if (cells.isEmpty()) reset() else reconvert()
            }
            KeyInput.Enter -> commitAll()
            KeyInput.Left -> cursor = maxOf(0, cursor - 1)
            KeyInput.Right -> cursor = minOf(cells.size, cursor + 1)
            KeyInput.Up -> cursor = 0
            KeyInput.Down -> openCandidates()
            KeyInput.TogglePunct -> punctMode = !punctMode
            KeyInput.ToggleAlpha -> {}
        }
        return Result.HANDLED
    }

    private fun insertLiteral(text: String) {
        cells.add(cursor, Cell(null, text, fixed = true))
        cursor++
        reconvert()
    }

    /** 候選字對象：游標指著的字；游標在最後時為最後一個字（同微軟新注音） */
    private fun openCandidates() {
        val index = if (cursor < cells.size) cursor else cells.size - 1
        val cell = cells.getOrNull(index) ?: return
        val reading = cell.reading ?: return
        val items = dict.candidates(reading)
        if (items.isEmpty()) return
        candidates = CandidateState(index, items, maxOf(0, items.indexOf(cell.text)))
    }

    // ---- 候選字 ----

    private fun handleCandidates(c: CandidateState, input: KeyInput): Result {
        val n = c.items.size
        when (input) {
            KeyInput.Left -> c.index = (c.index - 1 + n) % n
            KeyInput.Right -> c.index = (c.index + 1) % n
            KeyInput.Up, KeyInput.Down -> {
                val delta = if (input == KeyInput.Up) -1 else 1
                val page = (c.page + delta + c.pageCount) % c.pageCount
                c.index = minOf(page * CandidateState.PAGE_SIZE + c.index % CandidateState.PAGE_SIZE, n - 1)
            }
            // 空白：下一頁（環形），游標回該頁第一個
            is KeyInput.Space -> c.index = (c.page + 1) % c.pageCount * CandidateState.PAGE_SIZE
            KeyInput.Enter -> select(c, c.index)
            is KeyInput.Printable -> if (!input.shift && input.base in '1'..'9') pickCandidate(input.base - '1')
            is KeyInput.Numpad -> if (input.char in '1'..'9') pickCandidate(input.char - '1')
            else -> {}
        }
        return Result.HANDLED
    }

    /** 選定的字固定不再被系統變動（即使之後湊成詞）；游標跳到該字之後 */
    private fun select(c: CandidateState, pos: Int) {
        val cell = cells[c.cellIndex]
        cell.text = c.items[pos]
        cell.fixed = true
        dict.use(cell.reading!!, cell.text)
        candidates = null
        cursor = c.cellIndex + 1
        reconvert()
    }

    private fun reconvert() = Converter.convert(cells, dict)

    // ---- 字元 ----

    private fun charFor(input: KeyInput.Printable): String {
        if (mode == InputMode.BOPOMOFO && !input.shift) Keys.PUNCT[input.base]?.let { return it }
        return width(if (input.shift) Keys.shifted(input.base) else input.base)
    }

    private fun width(c: Char): String = (if (fullWidth) Keys.toFullWidth(c) else c).toString()
}
