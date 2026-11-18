package com.zcode.bopomofo.ime

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.zcode.bopomofo.engine.Dictionary
import com.zcode.bopomofo.engine.ImeEngine
import com.zcode.bopomofo.engine.KeyInput
import com.zcode.bopomofo.engine.Keys
import com.zcode.bopomofo.engine.Result
import java.io.File

class BopomofoImeService : InputMethodService() {
    private lateinit var engine: ImeEngine
    private var panel: ImePanelView? = null
    private var hasComposing = false
    /** 組字在目標欄位中的起點；取不到時為 -1，游標就留在組字結尾 */
    private var composeStart = -1
    /**
     * 目標是能顯示組字的文字欄位。不是（TYPE_NULL、沒有欄位）時實體鍵盤直接交給 App，
     * 螢幕鍵盤的輸入詞改在面板顯示，送出時才進目標。
     */
    private var inlineComposition = true
    private var dictLoaded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 實體鍵盤修飾鍵狀態
    private var shiftDown = false
    private var shiftUsed = false
    private var ctrlDown = false
    private var altDown = false
    private val consumedKeys = HashSet<Int>()

    private val lruFile get() = File(filesDir, "lru.txt")

    override fun onCreate() {
        super.onCreate()
        engine = ImeEngine(Dictionary()) { text ->
            currentInputConnection?.commitText(text, 1)
            hasComposing = false
        }
        // 詞典十幾萬筆，在背景載入，完成後再換進引擎
        Thread {
            val start = SystemClock.elapsedRealtime()
            val loaded = Dictionary().apply {
                assets.open("chars.txt").reader().use { loadChars(it) }
                assets.open("words.txt").reader().use { loadWords(it) }
                if (lruFile.exists()) importLru(lruFile.readText())
            }
            Log.i(ImePanelView.TAG, "dictionary loaded in ${SystemClock.elapsedRealtime() - start} ms")
            mainHandler.post {
                engine.dict = loaded
                dictLoaded = true
                panel?.loading = false
                refresh()
            }
        }.start()
    }

    override fun onCreateInputView(): View =
        ImePanelView(this, engine, object : ImePanelView.Listener {
            override fun onKey(input: KeyInput) {
                process(input, soft = true)
            }

            override fun onCandidate(slot: Int) {
                engine.pickCandidate(slot)
                refresh()
            }
        }).also {
            it.loading = !dictLoaded
            it.showComposition = !inlineComposition
            panel = it
        }

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    /** 接實體鍵盤時系統預設不顯示螢幕鍵盤，但候選字、ㄅ/a、半/全都在面板上，一律顯示 */
    override fun onShowInputRequested(flags: Int, configChange: Boolean) = true

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inlineComposition = attribute == null || attribute.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_NULL
        panel?.showComposition = !inlineComposition
        shiftDown = false
        ctrlDown = false
        altDown = false
        if (!restarting) {
            engine.reset()
            hasComposing = false
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panel?.invalidate()
    }

    override fun onFinishInput() {
        // 離開欄位前把組字換成輸入詞本身，免得未完成的注音和游標框留在目標
        if (hasComposing) currentInputConnection?.setComposingText(engine.cells.joinToString("") { it.text }, 1)
        super.onFinishInput()
        engine.reset()
        hasComposing = false
        if (dictLoaded) lruFile.writeText(engine.dict.exportLru())
    }

    private fun process(input: KeyInput, soft: Boolean): Boolean {
        val result = engine.handle(input)
        if (result == Result.PASS && soft) sendDefault(input)
        refresh()
        // 實體鍵盤打字時面板被收掉（如按過返回）就叫回來，才看得到候選字與狀態
        if (!soft && result == Result.HANDLED && !isInputViewShown && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestShowSelf(0)
        }
        return result == Result.HANDLED
    }

    /** 未輸入狀態：控制鍵直接作用於目標 */
    private fun sendDefault(input: KeyInput) {
        val ic = currentInputConnection ?: return
        when (input) {
            KeyInput.Left -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyInput.Right -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            KeyInput.Up -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            KeyInput.Down -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            KeyInput.Backspace -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            KeyInput.Enter -> {
                val ei = currentInputEditorInfo
                val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
                val multiLine = ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
                val noAction = ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                if (!multiLine && !noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                }
            }
            else -> {}
        }
    }

    /** 同步目標欄位中的組字文字與面板畫面 */
    private fun refresh() {
        val ic = currentInputConnection
        if (ic != null && inlineComposition) {
            if (engine.isComposing) {
                if (!hasComposing) composeStart = selectionStart(ic)
                val (text, caret) = composingText()
                ic.beginBatchEdit()
                ic.setComposingText(text, 1)
                // setComposingText 只能把游標放在組字頭尾，在中間時另外移過去
                if (composeStart >= 0 && caret < text.length) ic.setSelection(composeStart + caret, composeStart + caret)
                ic.endBatchEdit()
                hasComposing = true
            } else if (hasComposing) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                hasComposing = false
            }
        }
        panel?.invalidate()
    }

    /** 目標欄位目前選取範圍的起點（自欄位開頭算）；取不到時為 -1 */
    private fun selectionStart(ic: InputConnection): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ic.getSurroundingText(0, 0, 0)?.takeIf { it.offset >= 0 }?.let {
                return it.offset + minOf(it.selectionStart, it.selectionEnd)
            }
        }
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        if (et.selectionStart < 0) return -1
        return et.startOffset + minOf(et.selectionStart, et.selectionEnd)
    }

    /**
     * 目標欄位中的輸入詞與其中的游標位置：整段加底線、游標所在的字反黃、
     * 游標在最後時補一格黃底框、湊字中的注音藍字（查無此音則紅字紅底）。
     * 送出時只送輸入詞本身，黃底框與未完成的注音不會進目標。
     */
    private fun composingText(): Pair<CharSequence, Int> {
        val sb = SpannableStringBuilder()
        var caret = 0
        val syllable = engine.syllable
        fun appendSyllable() {
            if (!syllable.isEmpty) {
                val start = sb.length
                sb.append(syllable.display())
                val color = if (syllable.failed) Color.rgb(0xE5, 0x39, 0x35) else Color.rgb(0x15, 0x65, 0xC0)
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (syllable.failed) {
                    sb.setSpan(BackgroundColorSpan(Color.rgb(0xFF, 0xCD, 0xD2)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            caret = sb.length
        }
        engine.cells.forEachIndexed { i, cell ->
            if (i == engine.cursor) appendSyllable()
            val start = sb.length
            sb.append(cell.text)
            if (i == engine.cursor || i == engine.candidates?.cellIndex) {
                sb.setSpan(BackgroundColorSpan(Color.rgb(0xFF, 0xEB, 0x3B)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (engine.cursor == engine.cells.size) {
            appendSyllable()
            val start = sb.length
            sb.append('\u3000')
            sb.setSpan(BackgroundColorSpan(Color.rgb(0xFF, 0xEB, 0x3B)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.setSpan(UnderlineSpan(), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb to caret
    }

    /** 送出輸入詞，再把 48 鍵以外的鍵交給目標 */
    private fun flush() {
        if (!engine.isComposing) return
        engine.commitAll()
        refresh()
    }

    // ---- 實體鍵盤 ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 沒有文字欄位（或 TYPE_NULL 如終端機）時實體鍵盤一律交給 App，免得吃掉快捷鍵
        if (!inlineComposition) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (event.repeatCount == 0) {
                    shiftDown = true
                    shiftUsed = false
                }
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                ctrlDown = true
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                altDown = true
                return super.onKeyDown(keyCode, event)
            }
        }
        // 其餘修飾鍵、鎖定鍵不影響輸入詞
        if (KeyEvent.isModifierKey(keyCode) || keyCode in LOCK_KEYS) return super.onKeyDown(keyCode, event)
        shiftUsed = true
        val ctrl = ctrlDown || event.isCtrlPressed
        val alt = altDown || event.isAltPressed
        val input = toInput(keyCode, event, shiftDown || event.isShiftPressed)
        if (ctrl && alt && input is KeyInput.Printable && input.base == ',') {
            // Ctrl+Alt+,：切換符號模式（同微軟新注音），下一鍵輸入符號鍵盤上的符號
            process(KeyInput.TogglePunct, soft = false)
            consumedKeys += keyCode
            return true
        }
        if (input == null || ctrl || alt) {
            // 48 鍵以外的鍵與 Ctrl/Alt 組合鍵：先送出輸入詞，再交給目標
            if (!event.isSystem) flush()
            return super.onKeyDown(keyCode, event)
        }
        val handled = process(input, soft = false)
        if (handled) consumedKeys += keyCode
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inlineComposition && consumedKeys.isEmpty()) return super.onKeyUp(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (shiftDown && !shiftUsed) process(KeyInput.ToggleAlpha, soft = false)
                shiftDown = false
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) ctrlDown = false else altDown = false
                return super.onKeyUp(keyCode, event)
            }
        }
        if (consumedKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun toInput(keyCode: Int, event: KeyEvent, shift: Boolean): KeyInput? = when (keyCode) {
        KeyEvent.KEYCODE_SPACE -> KeyInput.Space(shift)
        KeyEvent.KEYCODE_DEL -> KeyInput.Backspace
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyInput.Enter
        KeyEvent.KEYCODE_DPAD_LEFT -> KeyInput.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> KeyInput.Right
        KeyEvent.KEYCODE_DPAD_UP -> KeyInput.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyInput.Down
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> {
            // 數字鍵盤一律當數字與符號（Android 預設 NumLock 關閉，關閉時 0-9 會變成方向/編輯鍵）
            val c = event.getUnicodeChar(KeyEvent.META_NUM_LOCK_ON).toChar()
            if (c in '!'..'~') KeyInput.Numpad(c) else null
        }
        else -> {
            val c = event.getUnicodeChar(0).toChar()
            if (c != '\u0000' && Keys.isKey(c)) KeyInput.Printable(c, shift) else null
        }
    }

    companion object {
        private val LOCK_KEYS = setOf(KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK)
    }
}
