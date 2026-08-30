package com.example.supermi

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.supermi.xposed.DebugLogStore
import com.example.supermi.xposed.BubblePrefs
import androidx.appcompat.widget.SwitchCompat

class DebugDetailsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_details)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.debug_details_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        findViewById<TextView>(R.id.btn_debug_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_clear_debug).setOnClickListener {
            DebugLogStore.clear(this)
            updateLogs()
        }
        findViewById<SwitchCompat>(R.id.sw_debug_toast).apply {
            isChecked = BubblePrefs.debugToastEnabled(this@DebugDetailsActivity)
            setOnCheckedChangeListener { _, checked -> persistDebugToast(checked) }
        }
        val enabled = AppConfig.read(this)["debug"] == "1"
        findViewById<TextView>(R.id.tv_debug_status).text = if (enabled) "已开启" else "未开启"
        findViewById<TextView>(R.id.tv_debug_status).setTextColor(
            getColor(if (enabled) R.color.ok else R.color.text_tertiary)
        )
        updateLogs()
    }

    override fun onResume() {
        super.onResume()
        updateLogs()
    }

    private fun updateLogs() {
        findViewById<TextView>(R.id.tv_debug_logs)?.text =
            DebugLogStore.read(this).ifBlank { "暂无调试记录" }
    }

    private fun persistDebugToast(checked: Boolean) {
        val config = AppConfig.read(this).toMutableMap()
        config[BubblePosProvider.KEY_DEBUG_TOAST] = if (checked) "1" else "0"
        AppConfig.write(this, config)
        BubblePosProvider.debugToast = checked
    }
}
