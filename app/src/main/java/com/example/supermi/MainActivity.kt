package com.example.supermi

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.supermi.xposed.BubblePrefs
import com.example.supermi.xposed.NumberRuleStore
import com.example.supermi.xposed.PlatformRuleStore
import java.io.File

class MainActivity : AppCompatActivity() {

    private val wm: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private var previewView: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private var offsetY: Int = BubblePrefs.DEFAULT_TOP_OFFSET
    private var offsetX: Int = BubblePrefs.DEFAULT_X_OFFSET
    private var step: Int = 5
    private var addrAppPkg: String = ""
    private var urlAppPkg: String = ""
    private var phoneAppPkg: String = ""
    private var debugEnabled: Boolean = false
    private var pendingType: String = BubblePosProvider.KEY_ADDR_APP

    private val pickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val pkgs = data.getStringArrayExtra(AppPickerActivity.EXTRA_PKGS)
                ?.joinToString(",")
                ?: data.getStringExtra(AppPickerActivity.EXTRA_PKG)
            if (pkgs.isNullOrEmpty()) return@registerForActivityResult
            when (pendingType) {
                BubblePosProvider.KEY_ADDR_APP -> addrAppPkg = pkgs
                BubblePosProvider.KEY_URL_APP -> urlAppPkg = pkgs
                else -> phoneAppPkg = pkgs
            }
            saveConfig()
            updateAppLabels()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.header).updatePadding(top = bars.top + dp(8))
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        loadConfig()

        findViewById<Button>(R.id.btn_show).setOnClickListener { togglePreview() }
        findViewById<Button>(R.id.btn_up).setOnClickListener { moveY(-step) }
        findViewById<Button>(R.id.btn_down).setOnClickListener { moveY(step) }
        findViewById<Button>(R.id.btn_left).setOnClickListener { moveX(-step) }
        findViewById<Button>(R.id.btn_right).setOnClickListener { moveX(step) }
        findViewById<Button>(R.id.btn_reset2).setOnClickListener { resetOffset() }

        findViewById<Button>(R.id.btn_step_1).setOnClickListener { setStep(1) }
        findViewById<Button>(R.id.btn_step_5).setOnClickListener { setStep(5) }
        findViewById<Button>(R.id.btn_step_10).setOnClickListener { setStep(10) }

        findViewById<View>(R.id.row_addr).setOnClickListener { launchPicker(BubblePosProvider.KEY_ADDR_APP) }
        findViewById<View>(R.id.row_url).setOnClickListener { launchPicker(BubblePosProvider.KEY_URL_APP) }
        findViewById<View>(R.id.row_platform).setOnClickListener {
            startActivity(Intent(this, PlatformRulesActivity::class.java))
        }
        findViewById<View>(R.id.row_number).setOnClickListener {
            startActivity(Intent(this, NumberRulesActivity::class.java))
        }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_debug).apply {
            isChecked = debugEnabled
            setOnCheckedChangeListener { _, checked ->
                debugEnabled = checked
                saveConfig()
            }
        }

        updateStepUi()
        updateOffsetLabel()
        updateAppLabels()
        ensureOverlayPermission()
    }

    private fun launchPicker(type: String) {
        pendingType = type
        val current = when (type) {
            BubblePosProvider.KEY_ADDR_APP -> addrAppPkg
            BubblePosProvider.KEY_URL_APP -> urlAppPkg
            else -> phoneAppPkg
        }
        pickerLauncher.launch(
            Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_TYPE, type)
                .putExtra(AppPickerActivity.EXTRA_MULTI, true)
                .putExtra(AppPickerActivity.EXTRA_CURRENT, currentAppArray(current))
        )
    }

    private fun ensureOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun setStep(s: Int) {
        step = s
        updateStepUi()
    }

    private fun updateStepUi() {
        val ids = mapOf(
            1 to R.id.btn_step_1,
            5 to R.id.btn_step_5,
            10 to R.id.btn_step_10
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == step
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_secondary, theme))
        }
    }

    private fun resetOffset() {
        offsetY = BubblePrefs.DEFAULT_TOP_OFFSET
        offsetX = BubblePrefs.DEFAULT_X_OFFSET
        applyOffset()
    }

    private fun updateOffsetLabel() {
        findViewById<TextView>(R.id.tv_offset).text = "Y: $offsetY  X: $offsetX"
    }

    private fun updateAppLabels() {
        findViewById<TextView>(R.id.tv_addr_desc).text =
            "地图 App：" + (appLabels(addrAppPkg) ?: "自动")
        findViewById<TextView>(R.id.tv_url_desc).text =
            "浏览器 App：" + (appLabels(urlAppPkg) ?: "自动")
    }

    private fun appLabels(pkgs: String): String? {
        if (pkgs.isEmpty()) return null
        val parts = pkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.map { appLabel(it) ?: it }.joinToString(",")
    }

    private fun currentAppArray(pkgs: String): Array<String>? {
        val parts = pkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.toTypedArray()
    }

    private fun appLabel(pkg: String): String? = try {
        if (pkg.isEmpty()) null
        else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        null
    }

    private fun togglePreview() {
        if (previewView != null) {
            dismissPreview()
            findViewById<Button>(R.id.btn_show).text = "▶ 显示预览"
        } else {
            showPreview()
            if (previewView != null) {
                findViewById<Button>(R.id.btn_show).text = "隐藏"
            }
        }
    }

    private fun showPreview() {
        if (!Settings.canDrawOverlays(this)) {
            ensureOverlayPermission()
            return
        }
        dismissPreview()
        val view = buildPreviewRow()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(offsetX)
            y = dp(offsetY)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTitle("SuperMi Preview")
        }
        try {
            wm.addView(view, params)
            previewView = view
            previewParams = params
        } catch (t: Throwable) {
            findViewById<TextView>(R.id.tv_offset).text = "预览失败: $t"
        }
    }

    private fun dismissPreview() {
        previewView?.let { v ->
            try {
                wm.removeView(v)
            } catch (_: Throwable) {
            }
        }
        previewView = null
        previewParams = null
    }

    private fun moveY(delta: Int) {
        offsetY = (offsetY + delta).coerceIn(0, 600)
        applyOffset()
    }

    private fun moveX(delta: Int) {
        offsetX = (offsetX + delta).coerceIn(-400, 400)
        applyOffset()
    }

    private fun applyOffset() {
        saveConfig()
        updateOffsetLabel()
        val params = previewParams
        val view = previewView
        if (params != null && view != null) {
            params.y = dp(offsetY)
            params.x = dp(offsetX)
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Throwable) {
            }
        }
    }

    private fun loadConfig() {
        val m = AppConfig.read(this)
        if (m.isEmpty()) {
            try {
                val lines = File(filesDir, "supermi_pos").readLines()
                if (lines.size >= 2) {
                    offsetY = lines[0].trim().toIntOrNull() ?: offsetY
                    offsetX = lines[1].trim().toIntOrNull() ?: offsetX
                }
            } catch (_: Throwable) {
            }
        } else {
            offsetY = m[BubblePosProvider.KEY_Y]?.toIntOrNull() ?: offsetY
            offsetX = m[BubblePosProvider.KEY_X]?.toIntOrNull() ?: offsetX
            addrAppPkg = m[BubblePosProvider.KEY_ADDR_APP] ?: addrAppPkg
            urlAppPkg = m[BubblePosProvider.KEY_URL_APP] ?: urlAppPkg
            phoneAppPkg = m[BubblePosProvider.KEY_PHONE_APP] ?: phoneAppPkg
            debugEnabled = m[BubblePosProvider.KEY_DEBUG] == "1"
            BubblePosProvider.platformRulesJson =
                m[PlatformRuleStore.KEY_PLATFORM_RULES] ?: PlatformRuleStore.toJson(PlatformRuleStore.DEFAULT_RULES)
        }
        syncProvider()
    }

    private fun syncProvider() {
        BubblePosProvider.currentY = offsetY
        BubblePosProvider.currentX = offsetX
        BubblePosProvider.addrApp = addrAppPkg
        BubblePosProvider.urlApp = urlAppPkg
        BubblePosProvider.phoneApp = phoneAppPkg
        BubblePosProvider.debug = debugEnabled
    }

    private fun saveConfig() {
        syncProvider()
        val m = AppConfig.read(this)
        m[BubblePosProvider.KEY_Y] = "$offsetY"
        m[BubblePosProvider.KEY_X] = "$offsetX"
        m[BubblePosProvider.KEY_ADDR_APP] = addrAppPkg
        m[BubblePosProvider.KEY_URL_APP] = urlAppPkg
        m[BubblePosProvider.KEY_PHONE_APP] = phoneAppPkg
        m[BubblePosProvider.KEY_DEBUG] = if (debugEnabled) "1" else "0"
        if (!m.containsKey(PlatformRuleStore.KEY_PLATFORM_RULES)) {
            m[PlatformRuleStore.KEY_PLATFORM_RULES] = PlatformRuleStore.toJson(PlatformRuleStore.DEFAULT_RULES)
        }
        if (!m.containsKey(NumberRuleStore.KEY_NUMBER_RULES)) {
            m[NumberRuleStore.KEY_NUMBER_RULES] = NumberRuleStore.toJson(NumberRuleStore.DEFAULT_RULES)
        }
        AppConfig.write(this, m)
    }

    private fun buildPreviewRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#E63C3C3C"))
                cornerRadius = dp(16).toFloat()
            }
        }

        val lp = LinearLayout.LayoutParams(dp(24), dp(24))
        lp.marginStart = dp(3)
        lp.marginEnd = dp(3)
        row.addView(ImageView(this).apply {
            setImageDrawable(loadPreviewIcon())
            layoutParams = lp
        })
        return row
    }

    private fun loadPreviewIcon(): android.graphics.drawable.Drawable {
        val pkg = addrAppPkg.ifEmpty { urlAppPkg }
        if (pkg.isNotEmpty()) {
            try {
                return packageManager.getApplicationIcon(pkg)
            } catch (_: Throwable) {
            }
        }
        return packageManager.defaultActivityIcon
    }

    override fun onPause() {
        super.onPause()
        dismissPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissPreview()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
