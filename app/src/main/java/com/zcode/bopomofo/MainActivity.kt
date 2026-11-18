package com.zcode.bopomofo

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 設定與測試頁：顯示目前輸入法狀態、啟用/切換輸入法，並提供輸入框測試 */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "1. 啟用「七伯新注音」\n2. 切換為此輸入法\n3. 在下方輸入框測試"
            textSize = 16f
        })
        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "啟用輸入法"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "切換輸入法"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        root.addView(EditText(this).apply {
            id = R.id.test_input
            hint = "在此測試輸入"
            textSize = 20f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })
        setContentView(root)
    }

    /** 從設定頁或切換視窗回來時更新；更新、重裝 App 後系統可能改回預設輸入法 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val (text, color) = when {
            current.startsWith("$packageName/") -> "✓ 目前使用七伯新注音" to Color.rgb(0x2E, 0x7D, 0x32)
            imm.enabledInputMethodList.any { it.packageName == packageName } -> "✗ 目前不是七伯新注音，請按「切換輸入法」" to Color.rgb(0xC6, 0x28, 0x28)
            else -> "✗ 尚未啟用，請按「啟用輸入法」" to Color.rgb(0xC6, 0x28, 0x28)
        }
        status.text = text
        status.setTextColor(color)
    }
}
