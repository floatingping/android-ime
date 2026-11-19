package com.zcode.bopomofo.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.zcode.bopomofo.engine.ImeEngine
import com.zcode.bopomofo.engine.InputMode
import com.zcode.bopomofo.engine.KeyInput
import com.zcode.bopomofo.engine.Keys

/**
 * 輸入法面板：狀態列（ㄅ/a、半/全、鍵盤開關）、候選字列、48 鍵 + 控制鍵。
 * 輸入詞直接顯示在目標欄位；目標無法顯示組字時（TYPE_NULL）才在狀態列下加輸入詞列。
 * 鍵盤收合時只保留上方各列。
 */
@SuppressLint("ViewConstructor")
class ImePanelView(
    context: Context,
    private val engine: ImeEngine,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onKey(input: KeyInput)
        fun onCandidate(slot: Int)
    }

    enum class Type { PRINTABLE, SHIFT, CTRL_ALT, SPACE, LEFT, UP, DOWN, RIGHT, BACKSPACE, ENTER }

    class Key(val type: Type, val base: Char = ' ') {
        val rect = RectF()
    }

    var expanded = true
        private set

    /** 字典仍在背景載入 */
    var loading = false

    /** 在面板上顯示輸入詞列 */
    var showComposition = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val headerH = dp(36f)
    private val compH = dp(48f)
    private val compRowH get() = if (showComposition) compH else 0f
    private val candH = dp(48f)
    private val rowH = dp(54f)
    private val keyGap = dp(3f)

    private val keys: List<Key> = buildList {
        Keys.ROWS.forEach { row -> row.forEach { add(Key(Type.PRINTABLE, it)) } }
        listOf(Type.SHIFT, Type.CTRL_ALT, Type.SPACE, Type.LEFT, Type.UP, Type.DOWN, Type.RIGHT, Type.BACKSPACE, Type.ENTER)
            .forEach { add(Key(it)) }
    }
    private val bottomWeights = floatArrayOf(1.5f, 1.5f, 3f, 1f, 1f, 1f, 1f, 1.5f, 1.5f)

    private val toggleRect = RectF()
    private val modeChipRect = RectF()
    private val widthChipRect = RectF()
    private val candidateRects = mutableListOf<RectF>()

    private val pointers = HashMap<Int, Key>()
    private var shiftHeld = false
    private var shiftUsed = false

    private val handler = Handler(Looper.getMainLooper())
    private var repeatKey: Key? = null
    private val repeater = object : Runnable {
        override fun run() {
            val key = repeatKey ?: return
            fire(key)
            handler.postDelayed(this, 50)
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val leftText = Paint(Paint.ANTI_ALIAS_FLAG)

    fun toggleExpanded() {
        expanded = !expanded
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = headerH + compRowH + candH + if (expanded) rowH * 5 + keyGap else 0f
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys(w.toFloat())
        post { logKeyPositions() }
    }

    private fun layoutKeys(w: Float) {
        toggleRect.set(w - dp(72f), 0f, w, headerH)
        modeChipRect.set(dp(8f), dp(4f), dp(48f), headerH - dp(4f))
        widthChipRect.set(dp(54f), dp(4f), dp(94f), headerH - dp(4f))
        val top = headerH + compRowH + candH
        var index = 0
        Keys.ROWS.forEachIndexed { r, row ->
            val kw = w / row.length
            row.indices.forEach { i ->
                keys[index++].rect.set(i * kw, top + r * rowH, (i + 1) * kw, top + (r + 1) * rowH)
            }
        }
        val unit = w / bottomWeights.sum()
        var x = 0f
        bottomWeights.forEach { weight ->
            keys[index++].rect.set(x, top + 4 * rowH, x + weight * unit, top + 5 * rowH)
            x += weight * unit
        }
    }

    /** 除錯/自動化測試用：輸出每個鍵中心的螢幕座標 */
    private fun logKeyPositions() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        keys.forEach { k ->
            val name = if (k.type == Type.PRINTABLE) k.base.toString() else k.type.name
            Log.d(TAG, "KEY $name ${(loc[0] + k.rect.centerX()).toInt()} ${(loc[1] + k.rect.centerY()).toInt()}")
        }
        Log.d(TAG, "KEY TOGGLE ${(loc[0] + toggleRect.centerX()).toInt()} ${(loc[1] + toggleRect.centerY()).toInt()}")
    }

    // ---- 繪製 ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(COLOR_BG)
        drawHeader(canvas)
        if (showComposition) drawComposition(canvas)
        drawCandidates(canvas)
        if (expanded) keys.forEach { drawKey(canvas, it) }
    }

    private fun drawHeader(canvas: Canvas) {
        fill.color = COLOR_HEADER
        canvas.drawRect(0f, 0f, width.toFloat(), headerH, fill)
        drawChip(canvas, modeChipRect, if (engine.mode == InputMode.BOPOMOFO) "ㄅ" else "a", COLOR_ACCENT)
        drawChip(canvas, widthChipRect, if (engine.fullWidth) "全" else "半", COLOR_ACCENT)
        if (engine.punctMode) {
            drawChip(canvas, RectF(dp(100f), dp(4f), dp(160f), headerH - dp(4f)), "標點", COLOR_PUNCT)
        }
        text.color = Color.DKGRAY
        text.textSize = sp(18f)
        canvas.drawText(if (expanded) "⌨ ▼" else "⌨ ▲", toggleRect.centerX(), baseline(toggleRect, text), text)
    }

    private fun drawChip(canvas: Canvas, r: RectF, label: String, color: Int) {
        fill.color = color
        canvas.drawRoundRect(r, dp(6f), dp(6f), fill)
        text.color = Color.WHITE
        text.textSize = sp(16f)
        canvas.drawText(label, r.centerX(), baseline(r, text), text)
    }

    private class Seg(val text: String, val kind: Int)

    private fun drawComposition(canvas: Canvas) {
        val top = headerH
        fill.color = Color.WHITE
        canvas.drawRect(0f, top, width.toFloat(), top + compH, fill)
        if (!engine.isComposing) return

        val segs = mutableListOf<Seg>()
        val cells = engine.cells
        val syllable = engine.syllable
        cells.forEachIndexed { i, cell ->
            if (i == engine.cursor && !syllable.isEmpty) segs += Seg(syllable.display(), if (syllable.failed) SEG_FAILED else SEG_SYLLABLE)
            val selected = i == engine.cursor || i == engine.candidates?.cellIndex
            segs += Seg(cell.text, if (selected) SEG_CURSOR_CELL else SEG_CELL)
        }
        if (engine.cursor == cells.size) {
            if (!syllable.isEmpty) segs += Seg(syllable.display(), if (syllable.failed) SEG_FAILED else SEG_SYLLABLE)
            segs += Seg("", SEG_CURSOR_BOX)
        }

        leftText.textSize = sp(24f)
        val boxW = leftText.measureText("中")
        val widths = segs.map { if (it.kind == SEG_CURSOR_BOX) boxW else leftText.measureText(it.text) }
        val pad = dp(10f)
        var cursorRight = pad
        var acc = pad
        segs.forEachIndexed { i, s ->
            acc += widths[i]
            if (s.kind == SEG_CURSOR_BOX || s.kind == SEG_CURSOR_CELL) cursorRight = acc
        }
        var x = pad - maxOf(0f, cursorRight - (width - pad))
        val textTop = top + dp(8f)
        val textBottom = top + compH - dp(8f)
        val y = baseline(RectF(0f, textTop, 0f, textBottom), leftText)
        segs.forEachIndexed { i, s ->
            val w = widths[i]
            when (s.kind) {
                SEG_CURSOR_CELL, SEG_CURSOR_BOX -> {
                    fill.color = COLOR_CURSOR
                    canvas.drawRect(x, textTop, x + w, textBottom, fill)
                    fill.style = Paint.Style.STROKE
                    fill.strokeWidth = dp(1.5f)
                    fill.color = COLOR_CURSOR_BORDER
                    canvas.drawRect(x, textTop, x + w, textBottom, fill)
                    fill.style = Paint.Style.FILL
                }
            }
            leftText.color = when (s.kind) {
                SEG_SYLLABLE, SEG_FAILED -> COLOR_ACCENT
                else -> Color.BLACK
            }
            canvas.drawText(s.text, x, y, leftText)
            if (s.kind == SEG_SYLLABLE || s.kind == SEG_FAILED) {
                fill.color = if (s.kind == SEG_FAILED) COLOR_FAILED else COLOR_ACCENT
                val h = if (s.kind == SEG_FAILED) dp(3f) else dp(1.5f)
                canvas.drawRect(x, textBottom - h, x + w, textBottom, fill)
            }
            x += w
        }
    }

    private fun drawCandidates(canvas: Canvas) {
        val top = headerH + compRowH
        fill.color = COLOR_CAND_BG
        canvas.drawRect(0f, top, width.toFloat(), top + candH, fill)
        candidateRects.clear()
        val c = engine.candidates
        if (c == null) {
            if (loading) {
                leftText.textSize = sp(15f)
                leftText.color = Color.GRAY
                canvas.drawText("字典載入中…", dp(10f), baseline(RectF(0f, top, 0f, top + candH), leftText), leftText)
            } else if (engine.punctMode) {
                leftText.textSize = sp(15f)
                leftText.color = COLOR_PUNCT
                canvas.drawText("中文標點模式：按任一鍵輸入標點", dp(10f), baseline(RectF(0f, top, 0f, top + candH), leftText), leftText)
            }
            return
        }
        val pageLabel = "${c.page + 1}/${c.pageCount}"
        text.textSize = sp(13f)
        text.color = Color.DKGRAY
        canvas.drawText(pageLabel, width - dp(24f), baseline(RectF(0f, top, 0f, top + candH), text), text)

        val slotW = (width - dp(44f)) / 9f
        leftText.textSize = sp(22f)
        val by = baseline(RectF(0f, top, 0f, top + candH), leftText)
        c.pageItems().forEachIndexed { k, item ->
            val r = RectF(k * slotW + dp(4f), top + dp(3f), (k + 1) * slotW + dp(2f), top + candH - dp(3f))
            candidateRects += r
            if (c.page * 9 + k == c.index) {
                fill.color = COLOR_CURSOR
                canvas.drawRoundRect(r, dp(4f), dp(4f), fill)
            }
            // 序號在字左邊的留白處，字置中，字與字之間留大間距
            leftText.textSize = sp(22f)
            leftText.color = Color.BLACK
            val cw = leftText.measureText(item)
            var charX = r.centerX() - cw / 2
            leftText.textSize = sp(12f)
            leftText.color = COLOR_ACCENT
            val label = "${k + 1}"
            val numW = leftText.measureText(label)
            val numX = r.left + dp(1f)
            if (numX + numW + dp(1f) > charX) charX = numX + numW + dp(1f) // 太窄時字右移，不與序號重叠
            canvas.drawText(label, numX, by, leftText)
            leftText.textSize = sp(22f)
            leftText.color = Color.BLACK
            canvas.drawText(item, charX, by, leftText)
        }
    }

    private fun drawKey(canvas: Canvas, k: Key) {
        val r = RectF(k.rect.left + keyGap / 2, k.rect.top + keyGap, k.rect.right - keyGap / 2, k.rect.bottom)
        val pressed = pointers.containsValue(k)
        fill.color = when {
            k.type == Type.SHIFT && shiftHeld -> COLOR_CURSOR
            k.type == Type.CTRL_ALT && engine.punctMode -> COLOR_PUNCT_KEY
            pressed -> COLOR_PRESSED
            k.type == Type.PRINTABLE || k.type == Type.SPACE -> Color.WHITE
            else -> COLOR_CONTROL
        }
        canvas.drawRoundRect(r, dp(5f), dp(5f), fill)

        if (k.type != Type.PRINTABLE) {
            val label = when (k.type) {
                Type.SHIFT -> "Shift"
                Type.CTRL_ALT -> "Ctrl+Alt"
                Type.SPACE -> "空白"
                Type.LEFT -> "←"
                Type.UP -> "↑"
                Type.DOWN -> "↓"
                Type.RIGHT -> "→"
                Type.BACKSPACE -> "⌫"
                Type.ENTER -> "⏎"
                Type.PRINTABLE -> ""
            }
            text.textSize = when {
                label.length > 5 -> sp(12f)
                label.length > 2 -> sp(14f)
                else -> sp(20f)
            }
            text.color = Color.BLACK
            canvas.drawText(label, r.centerX(), baseline(r, text), text)
            return
        }

        val base = k.base
        val big: String
        val small: String
        val smallColor: Int
        if (engine.punctMode) {
            big = Keys.SYMBOLS.getValue(base)
            small = base.uppercaseChar().toString()
            smallColor = Color.GRAY
        } else {
            big = when {
                shiftHeld -> Keys.shifted(base).toString()
                engine.mode == InputMode.ALPHA -> base.toString()
                else -> base.uppercaseChar().toString()
            }
            small = (Keys.ZHUYIN[base] ?: Keys.TONES[base])?.toString() ?: Keys.PUNCT.getValue(base)
            smallColor = if (engine.mode == InputMode.BOPOMOFO) COLOR_ACCENT else COLOR_DIM
        }
        text.textSize = sp(20f)
        text.color = if (engine.punctMode) COLOR_PUNCT else Color.BLACK
        canvas.drawText(big, r.centerX(), baseline(r, text) + dp(3f), text)
        text.textSize = sp(13f)
        text.color = smallColor
        canvas.drawText(small, r.right - dp(10f), r.top + dp(15f), text)
    }

    private fun baseline(r: RectF, p: Paint): Float {
        val fm = p.fontMetrics
        return r.centerY() - (fm.ascent + fm.descent) / 2
    }

    // ---- 觸控：支援多點，按住 Shift 再按其他鍵 ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                pointerDown(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(ev.getPointerId(ev.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                pointers.clear()
                shiftHeld = false
                stopRepeat()
            }
        }
        invalidate()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        if (toggleRect.contains(x, y)) { toggleExpanded(); return }
        if (modeChipRect.contains(x, y)) { listener.onKey(KeyInput.ToggleAlpha); return }
        if (widthChipRect.contains(x, y)) { listener.onKey(KeyInput.Space(shift = true)); return }
        candidateRects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { listener.onCandidate(it); return }
        if (!expanded) return
        val key = keys.firstOrNull { it.rect.contains(x, y) } ?: return
        pointers[id] = key
        when (key.type) {
            Type.SHIFT -> { shiftHeld = true; shiftUsed = false }
            Type.BACKSPACE, Type.LEFT, Type.RIGHT, Type.UP, Type.DOWN -> {
                fire(key)
                stopRepeat()
                repeatKey = key
                handler.postDelayed(repeater, 400)
            }
            else -> {}
        }
    }

    private fun pointerUp(id: Int) {
        val key = pointers.remove(id) ?: return
        when (key.type) {
            Type.SHIFT -> {
                if (!shiftUsed) listener.onKey(KeyInput.ToggleAlpha)
                shiftHeld = false
            }
            Type.BACKSPACE, Type.LEFT, Type.RIGHT, Type.UP, Type.DOWN -> if (repeatKey == key) stopRepeat()
            else -> fire(key)
        }
    }

    private fun stopRepeat() {
        repeatKey = null
        handler.removeCallbacks(repeater)
    }

    private fun fire(key: Key) {
        val input = when (key.type) {
            Type.PRINTABLE -> KeyInput.Printable(key.base, shiftHeld)
            Type.SPACE -> KeyInput.Space(shiftHeld)
            Type.CTRL_ALT -> KeyInput.TogglePunct
            Type.LEFT -> KeyInput.Left
            Type.RIGHT -> KeyInput.Right
            Type.UP -> KeyInput.Up
            Type.DOWN -> KeyInput.Down
            Type.BACKSPACE -> KeyInput.Backspace
            Type.ENTER -> KeyInput.Enter
            Type.SHIFT -> return
        }
        if (shiftHeld) shiftUsed = true
        listener.onKey(input)
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        const val TAG = "BopomofoIME"
        private const val SEG_CELL = 0
        private const val SEG_CURSOR_CELL = 1
        private const val SEG_CURSOR_BOX = 2
        private const val SEG_SYLLABLE = 3
        private const val SEG_FAILED = 4

        private val COLOR_BG = Color.rgb(0xD5, 0xD9, 0xDE)
        private val COLOR_HEADER = Color.rgb(0xEC, 0xEF, 0xF1)
        private val COLOR_CAND_BG = Color.rgb(0xF5, 0xF5, 0xF5)
        private val COLOR_CONTROL = Color.rgb(0xB0, 0xBE, 0xC5)
        private val COLOR_PRESSED = Color.rgb(0x90, 0xA4, 0xAE)
        private val COLOR_ACCENT = Color.rgb(0x15, 0x65, 0xC0)
        private val COLOR_DIM = Color.rgb(0xB0, 0xB0, 0xB0)
        private val COLOR_CURSOR = Color.rgb(0xFF, 0xEB, 0x3B)
        private val COLOR_CURSOR_BORDER = Color.rgb(0xF9, 0xA8, 0x25)
        private val COLOR_FAILED = Color.rgb(0xE5, 0x39, 0x35)
        private val COLOR_PUNCT = Color.rgb(0x6A, 0x1B, 0x9A)
        private val COLOR_PUNCT_KEY = Color.rgb(0xCE, 0x93, 0xD8)
    }
}
