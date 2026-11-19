package com.zcode.bopomofo.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ImeEngineTest {
    private lateinit var dict: Dictionary
    private lateinit var engine: ImeEngine
    private val committed = StringBuilder()

    @Before
    fun setUp() {
        dict = Dictionary().apply {
            File("src/main/assets/chars.txt").reader().use { loadChars(it) }
            File("src/main/assets/words.txt").reader().use { loadWords(it) }
        }
        engine = ImeEngine(dict) { committed.append(it) }
    }

    /** 依實體鍵盤按鍵序列輸入；`{S}`=單按 Shift，`{CA}`=Ctrl+Alt+,，`{E}`=Enter，`^x`=Shift+x，`_`=空白 */
    private fun type(seq: String) {
        var i = 0
        while (i < seq.length) {
            when {
                seq.startsWith("{S}", i) -> { engine.handle(KeyInput.ToggleAlpha); i += 3 }
                seq.startsWith("{CA}", i) -> { engine.handle(KeyInput.TogglePunct); i += 4 }
                seq.startsWith("{E}", i) -> { engine.handle(KeyInput.Enter); i += 3 }
                seq[i] == '^' -> { engine.handle(KeyInput.Printable(seq[i + 1], shift = true)); i += 2 }
                seq[i] == '_' -> { engine.handle(KeyInput.Space()); i++ }
                else -> { engine.handle(KeyInput.Printable(seq[i])); i++ }
            }
        }
    }

    private fun composition() = engine.cells.joinToString("") { it.text }

    @Test
    fun targetSentence() {
        // 你好嗎 a!bc ？ 今天天氣真好 d$ef ！
        type("su3cl3a87")
        assertEquals("你好嗎", composition())
        type("{S}a^1bc")
        type("{CA}/")
        type("{S}rup_wu0_wu0_fu45p_cl3")
        type("{S}d^4ef")
        type("{CA}k")
        assertEquals("你好嗎a!bc？今天天氣真好d\$ef！", composition())
        type("{E}")
        assertEquals("你好嗎a!bc？今天天氣真好d\$ef！", committed.toString())
        assertFalse(engine.isComposing)
    }

    @Test
    fun idleKeysPassThroughAndAlphaCommitsDirectly() {
        assertEquals(Result.PASS, engine.handle(KeyInput.Left))
        assertEquals(Result.PASS, engine.handle(KeyInput.Backspace))
        assertEquals(Result.PASS, engine.handle(KeyInput.Enter))
        type("{S}ab^c")
        assertEquals("abC", committed.toString())
        assertFalse(engine.isComposing)
    }

    @Test
    fun fullWidthToggle() {
        engine.handle(KeyInput.Space(shift = true))
        assertTrue(engine.fullWidth)
        type("{S}a^1")
        assertEquals("ａ！", committed.toString())
        engine.handle(KeyInput.Space(shift = true))
        type("a")
        assertEquals("ａ！a", committed.toString())
    }

    @Test
    fun syllableMustEndWithToneAndFailsWithRedLine() {
        type("2up")
        assertEquals("ㄉㄧㄣ", engine.syllable.display())
        assertTrue(engine.cells.isEmpty())
        // 湊字時方向鍵、Enter 無反應
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Enter)
        assertEquals("ㄉㄧㄣ", engine.syllable.display())
        // ㄉㄧㄣˋ 不是國語音節 → 紅線
        type("4")
        assertTrue(engine.syllable.failed)
        engine.handle(KeyInput.Backspace)
        assertFalse(engine.syllable.failed)
        engine.handle(KeyInput.Backspace)
        type("3")
        assertEquals(dict.bestChar("ㄉㄧˇ"), composition())
        assertTrue(engine.syllable.isEmpty)
    }

    @Test
    fun userReportedPhrases() {
        type("hk4g4d04d04")
        assertEquals("測試看看", composition())
        type("{E}")
        type("d04") // ㄎㄢˋ
        assertEquals("看", composition())
    }

    @Test
    fun bareBopomofoSymbols() {
        // 單一注音符號＋空白 → 符號本身
        type("su3cl3a_") // ㄋㄧˇ ㄏㄠˇ ㄇ␣
        assertEquals("你好ㄇ", composition())
        type("{CA},")
        type("j_i_") // ㄨ␣ ㄛ␣
        type("cp3cl3") // ㄏㄣˇ ㄏㄠˇ
        assertEquals("你好ㄇ，ㄨㄛ很好", composition())
        type("{E}")
        assertEquals("你好ㄇ，ㄨㄛ很好", committed.toString())

        // 詞典仍優先：ㄉㄧˋ ㄧ␣ → 第一；ㄧ␣ ㄍㄜ˙ → 一個
        type("2u4u_")
        assertEquals("第一", composition())
        type("{E}")
        type("u_ek7")
        assertEquals("一個", composition())
        type("{E}")

        // 單獨的 ㄧ␣ 為「ㄧ」；選過「一」之後依 LRU 預設為「一」，存檔讀回仍保留
        type("u_")
        assertEquals("ㄧ", composition())
        engine.handle(KeyInput.Down)
        engine.pickCandidate(engine.candidates!!.items.indexOf("一"))
        assertEquals("一", composition())
        type("{E}")
        type("u_")
        assertEquals("一", composition())
        val restored = Dictionary().apply {
            File("src/main/assets/chars.txt").reader().use { loadChars(it) }
            importLru(dict.exportLru())
        }
        assertEquals("一", restored.bestChar("ㄧ"))
    }

    @Test
    fun downAtEndOpensCandidatesForLastChar() {
        type("hk4g4") // 測試
        assertEquals(2, engine.cursor)
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        assertEquals(1, c.cellIndex)
        assertEquals("試", c.items[c.index])
    }

    @Test
    fun spaceInCompositionInsertsSpace() {
        type("hk4g4")
        engine.handle(KeyInput.Space())
        assertNull(engine.candidates)
        assertEquals("測試 ", composition())
        // 全形時為全形空白
        engine.handle(KeyInput.Space(shift = true))
        engine.handle(KeyInput.Space())
        assertEquals("測試 　", composition())
        // 空白後接著打的注音照常湊字
        engine.handle(KeyInput.Space(shift = true))
        type("{E}")
        type("su3_cl3")
        assertEquals("你 好", composition())
    }

    @Test
    fun spaceInCandidatesGoesToFirstOfNextPage() {
        type("g4") // ㄕˋ，上百個候選
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        engine.handle(KeyInput.Right)
        engine.handle(KeyInput.Right)
        engine.handle(KeyInput.Space())
        assertEquals(1, c.page)
        assertEquals(CandidateState.PAGE_SIZE, c.index)
        engine.handle(KeyInput.Up) // 回最後一頁再空白 → 環形回第一頁第一個
        engine.handle(KeyInput.Up)
        assertEquals(c.pageCount - 1, c.page)
        engine.handle(KeyInput.Space())
        assertEquals(0, c.index)
        assertNotNull(engine.candidates)
    }

    @Test
    fun lettersAndDigitsDuringSyllableCancelIt() {
        type("su3c") // 你 + 湊字中的 ㄏ
        type("^b") // Shift+b → B：捨棄 ㄏ，B 插在游標處
        engine.handle(KeyInput.Numpad('7'))
        assertEquals("你B7", composition())
        assertTrue(engine.syllable.isEmpty)
        type("cl3") // 注音照常湊字
        assertEquals("你B7好", composition())
        // 未輸入時湊字中打英文：捨棄 ㄏ，英文直接送出
        type("{E}")
        type("c^a")
        assertEquals("你B7好A", committed.toString())
        assertFalse(engine.isComposing)
        type("c{S}q") // a 模式的鍵也一樣
        assertEquals("你B7好Aq", committed.toString())
        assertFalse(engine.isComposing)
    }

    @Test
    fun manyCandidatePagesForCommonReading() {
        type("u_") // ㄧ
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        assertTrue("ㄧ 只有 ${c.items.size} 個候選", c.items.size >= 45)
        assertEquals(9, c.pageItems().size)
        assertEquals((c.items.size + 8) / 9, c.pageCount)
    }

    @Test
    fun cursorMovesAndBackspaceRemovesCharBeforeCursor() {
        type("rup_wu0_")
        assertEquals("今天", composition())
        assertEquals(2, engine.cursor)
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Left)
        assertEquals(0, engine.cursor)
        engine.handle(KeyInput.Right)
        engine.handle(KeyInput.Backspace)
        assertEquals("天", composition())
        assertEquals(0, engine.cursor)
        // 在游標前插入新字
        type("rup_")
        assertEquals("今天", composition())
        assertEquals(1, engine.cursor)
    }

    @Test
    fun candidatesPagingAndSelection() {
        type("g4") // ㄕˋ，上百個候選
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        val lastPage = c.pageCount - 1
        assertEquals(0, c.index)
        assertTrue(c.pageCount > 3)
        engine.handle(KeyInput.Left) // 到頭 → 環形到最後一頁最後一個
        assertEquals(c.items.size - 1, c.index)
        assertEquals(lastPage, c.page)
        engine.handle(KeyInput.Right) // 到底 → 回第一頁
        assertEquals(0, c.index)
        engine.handle(KeyInput.Up) // 上 → 環形到最後一頁
        assertEquals(lastPage, c.page)
        engine.handle(KeyInput.Down)
        engine.handle(KeyInput.Down)
        assertEquals(1, c.page)
        engine.handle(KeyInput.Backspace) // 無反應
        assertNotNull(engine.candidates)
        engine.handle(KeyInput.Printable('9'))
        assertNull(engine.candidates)
        assertEquals(c.items[17], composition())
        assertTrue(engine.cells[0].fixed)
    }

    @Test
    fun missingDigitDoesNothing() {
        type("d04") // ㄎㄢˋ：最後一頁不滿 9 個
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        engine.handle(KeyInput.Up) // 環形到最後一頁
        val lastCount = c.pageItems().size
        assertTrue(lastCount < 9)
        engine.handle(KeyInput.Printable('1' + lastCount)) // 該位置沒有字
        assertNotNull(engine.candidates)
        engine.handle(KeyInput.Printable('1'))
        assertEquals(c.items[c.page * 9], composition())
    }

    @Test
    fun pickedCharIsNotChangedBySystemAndLruRanksFirst() {
        type("wu0_") // 天
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Down)
        val picked = engine.candidates!!.items[1]
        engine.handle(KeyInput.Printable('2'))
        assertEquals(picked, composition())
        engine.handle(KeyInput.Right)
        type("fu4") // ㄑㄧˋ：天氣 為詞，但第一字已被挑過，不得變動
        assertEquals(picked, composition().substring(0, 1))
        type("{E}")
        // LRU：再打 ㄊㄧㄢ 預設為剛挑的字
        type("wu0_")
        assertEquals(picked, composition())
    }

    @Test
    fun phraseDictionaryWins() {
        type("rup_wu0_wu0_fu4")
        assertEquals("今天天氣", composition())
    }

    @Test
    fun punctModeWorksWhenIdleAndInComposition() {
        // 未輸入：Ctrl+Alt+, 後按鍵直接送出符號
        type("{CA}")
        assertTrue(engine.punctMode)
        type(",")
        assertEquals("，", committed.toString())
        assertFalse(engine.punctMode)
        assertFalse(engine.isComposing)
        // 輸入詞中：插在游標處，輸入一個後退出符號模式
        type("su3{CA}")
        assertTrue(engine.punctMode)
        type(",")
        assertFalse(engine.punctMode)
        assertEquals("你，", composition())
        // 符號模式下 a 模式也輸入符號
        type("{S}{CA}m")
        assertEquals("你，。", composition())
        // 再按一次 Ctrl+Alt+, 取消
        type("{CA}{CA}m")
        assertEquals("你，。m", composition())
    }

    @Test
    fun symbolKeyboardMatchesMicrosoftLayout() {
        // 微軟新注音 Ctrl+Alt+, 符號鍵盤（輸入法整合器）
        val expected = mapOf(
            ',' to "，", 'm' to "。", '\'' to "、", ';' to "；", 'l' to "：", '/' to "？", 'k' to "！",
            '.' to "‧", 'z' to "─", '=' to "「", '0' to "『", 'o' to "【", '\\' to "」", '-' to "』",
            'p' to "】", 't' to "〈", 'u' to "《", 'y' to "〉", 'i' to "》", '`' to "€", '1' to "┌",
            '4' to "〝", '5' to "〞", '6' to "‘", '7' to "’", '8' to "“", '9' to "”", 'r' to "※",
            'f' to "○", 'g' to "●", 'c' to "◎", 'v' to "§",
        )
        expected.forEach { (key, symbol) ->
            committed.clear()
            type("{CA}$key")
            assertEquals("Ctrl+Alt+, $key", symbol, committed.toString())
        }
    }

    @Test
    fun numpadTypesCharactersAsIs() {
        // 未輸入：直接送出，不當注音或聲調
        "1+3".forEach { engine.handle(KeyInput.Numpad(it)) }
        assertEquals("1+3", committed.toString())
        assertFalse(engine.isComposing)
        // 輸入詞中：插入字元本身（/ 不是 ㄥ、. 不是 ㄡ 或 。）
        type("su3")
        "3./".forEach { engine.handle(KeyInput.Numpad(it)) }
        assertEquals("你3./", composition())
        // 湊字中：捨棄湊字，字元照常輸入
        type("c")
        engine.handle(KeyInput.Numpad('3'))
        assertEquals("你3./3", composition())
        assertTrue(engine.syllable.isEmpty)
        engine.handle(KeyInput.Backspace)
        // 候選字：數字鍵盤 1-9 選字
        type("cl3")
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        engine.handle(KeyInput.Numpad('2'))
        assertNull(engine.candidates)
        assertEquals(c.items[1], engine.cells.last().text)
        // 全形
        type("{E}")
        engine.handle(KeyInput.Space(shift = true))
        engine.handle(KeyInput.Numpad('7'))
        assertTrue(committed.endsWith("７"))
    }

    @Test
    fun pickingCandidateMovesCursorPastPickedChar() {
        type("rup_wu0_wu0_fu4") // 今天天氣
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Left)
        engine.handle(KeyInput.Down)
        val c = engine.candidates!!
        assertEquals(2, c.cellIndex)
        engine.handle(KeyInput.Right)
        val picked = c.items[c.index]
        engine.handle(KeyInput.Enter)
        assertEquals(3, engine.cursor)
        assertEquals(picked, engine.cells[2].text)
        // 接著打的字插在選定的字之後，選定的字不變
        type("wu0_")
        assertEquals(4, engine.cursor)
        assertEquals(5, engine.cells.size)
        assertEquals(picked, engine.cells[2].text)
        assertEquals("氣", engine.cells[4].text)
    }

    @Test
    fun commitAllSendsCompositionAndDropsUnfinishedSyllable() {
        type("su3cl") // 你 + 湊字中的 ㄏㄠ
        engine.commitAll()
        assertEquals("你", committed.toString())
        assertFalse(engine.isComposing)
        type("cl")
        engine.commitAll()
        assertEquals("你", committed.toString())
        assertFalse(engine.isComposing)
    }

    @Test
    fun lruKeepsTenPicksPerReading() {
        val all = dict.candidates("ㄕˋ")
        val picks = all.takeLast(12)
        picks.forEach { dict.use("ㄕˋ", it) }
        val ranked = dict.candidates("ㄕˋ")
        assertEquals(picks.reversed().take(10), ranked.take(10))
        assertEquals(all[0], ranked[10]) // 最早挑的兩個已被擠出
        // 讀回舊版存的 20 筆也只留 10 筆
        val restored = Dictionary().apply {
            File("src/main/assets/chars.txt").reader().use { loadChars(it) }
            importLru("ㄕˋ\t" + all.takeLast(20).joinToString(" "))
        }
        assertEquals(10, restored.exportLru().substringAfter('\t').split(' ').size)
    }

    @Test
    fun typingBeforeDictionaryLoadsIsKept() {
        engine = ImeEngine(Dictionary()) { committed.append(it) }
        type("{S}a{S}su3") // 字典未載入：英文照常送出，注音查不到 → 紅線
        assertEquals("a", committed.toString())
        assertTrue(engine.syllable.failed)
        engine.dict = dict
        assertEquals("你", composition())
        assertTrue(engine.syllable.isEmpty)
    }

    @Test
    fun backspaceToEmptyReturnsToIdle() {
        type("su3")
        engine.handle(KeyInput.Backspace)
        assertFalse(engine.isComposing)
        assertEquals(Result.PASS, engine.handle(KeyInput.Backspace))
    }

    @Test
    fun alphaLettersGoIntoComposition() {
        type("su3{S}a^b")
        assertEquals("你aB", composition())
        assertEquals("", committed.toString())
    }
}
