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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.supermi.xposed.BubblePrefs
import com.example.supermi.xposed.NumberRuleStore
import com.example.supermi.xposed.PlatformRuleStore
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private val MAX_LEN_VALUES = listOf(50, 100, 150, 200, 250, 400)
    }

    private val wm: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private var previewView: View? = null
    private var previewParams: WindowManager.LayoutParams? = null
    private val posX = IntArray(4) { BubblePrefs.DEFAULT_X_OFFSET }
    private val posY = IntArray(4) { BubblePrefs.DEFAULT_TOP_OFFSET }
    private var step: Int = 5
    private var addrAppPkg: String = ""
    private var urlAppPkg: String = ""
    private var phoneAppPkg: String = ""
    private var debugEnabled: Boolean = false
    private var maxLen: Int = BubblePrefs.DEFAULT_MAX_LEN
    private var previewCount: Int = 1
    private var gap12_2: Int = 6
    private var gap12_3: Int = 6
    private var gap23_3: Int = 6
    private var iconSize: Int = BubblePrefs.DEFAULT_ICON_SIZE
    private var bgAlpha: Int = BubblePrefs.DEFAULT_BG_ALPHA
    private var pendingType: String = BubblePosProvider.KEY_ADDR_APP

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val ok = writeExport(uri)
            Toast.makeText(this, if (ok) "已导出配置" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importConfig(uri)
    }

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

        findViewById<Button>(R.id.btn_preview_1).setOnClickListener { setPreviewCount(1) }
        findViewById<Button>(R.id.btn_preview_2).setOnClickListener { setPreviewCount(2) }
        findViewById<Button>(R.id.btn_preview_3).setOnClickListener { setPreviewCount(3) }

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

        findViewById<View>(R.id.row_export).setOnClickListener { exportConfig() }
        findViewById<View>(R.id.row_import).setOnClickListener { importLauncher.launch(arrayOf("text/plain", "application/octet-stream")) }

        findViewById<Button>(R.id.btn_len_50).setOnClickListener { setMaxLen(50) }
        findViewById<Button>(R.id.btn_len_100).setOnClickListener { setMaxLen(100) }
        findViewById<Button>(R.id.btn_len_150).setOnClickListener { setMaxLen(150) }
        findViewById<Button>(R.id.btn_len_200).setOnClickListener { setMaxLen(200) }
        findViewById<Button>(R.id.btn_len_250).setOnClickListener { setMaxLen(250) }
        findViewById<Button>(R.id.btn_len_400).setOnClickListener { setMaxLen(400) }
        updateMaxLenSeg()

        updateStepUi()
        updatePreviewSeg()
        setupIconSeekBar(R.id.seek_icon, R.id.tv_icon, iconSize) { iconSize = it }
        findViewById<Button>(R.id.btn_reset_icon).setOnClickListener {
            setIconSize(BubblePrefs.DEFAULT_ICON_SIZE)
        }
        setupBgAlphaSeekBar(R.id.seek_bg_alpha, R.id.tv_bg_alpha, bgAlpha) { bgAlpha = it }
        findViewById<Button>(R.id.btn_reset_bg_alpha).setOnClickListener {
            setBgAlpha(BubblePrefs.DEFAULT_BG_ALPHA)
        }
        findViewById<android.widget.SeekBar>(R.id.seek_gap12).setOnSeekBarChangeListener(gapListener(1))
        findViewById<android.widget.SeekBar>(R.id.seek_gap23).setOnSeekBarChangeListener(gapListener(2))
        findViewById<Button>(R.id.btn_reset_gap12).setOnClickListener { setGap(1, 6) }
        findViewById<Button>(R.id.btn_reset_gap23).setOnClickListener { setGap(2, 6) }
        updateGapUI()
        updateOffsetLabel()
        updateAppLabels()
        ensureOverlayPermission()
    }

    private fun gapListener(index: Int) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            setGap(index, progress)
        }

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
    }

    private fun currentGap12(): Int = when (previewCount.coerceIn(1, 3)) {
        2 -> gap12_2
        else -> gap12_3
    }

    private fun setGap(index: Int, value: Int) {
        val v = value.coerceIn(0, 50)
        when (index) {
            1 -> {
                if (previewCount == 2) gap12_2 = v else gap12_3 = v
            }
            else -> gap23_3 = v
        }
        updateGapUI()
        saveConfig()
        refreshPreview()
    }

    private fun updateGapUI() {
        val n = previewCount.coerceIn(1, 3)
        val seek12 = findViewById<android.widget.SeekBar>(R.id.seek_gap12)
        val seek23 = findViewById<android.widget.SeekBar>(R.id.seek_gap23)
        val tv12 = findViewById<TextView>(R.id.tv_gap12)
        val tv23 = findViewById<TextView>(R.id.tv_gap23)
        val v12 = currentGap12()
        seek12.isEnabled = n >= 2
        seek23.isEnabled = n >= 3
        seek12.progress = v12
        tv12.text = "$v12"
        seek23.progress = gap23_3
        tv23.text = "${gap23_3}"
    }

    private fun setupIconSeekBar(
        seekId: Int,
        tvId: Int,
        initial: Int,
        update: (Int) -> Unit
    ) {
        val seek = findViewById<android.widget.SeekBar>(seekId)
        val tv = findViewById<TextView>(tvId)
        seek.max = BubblePrefs.ICON_SIZE_MAX - BubblePrefs.ICON_SIZE_MIN
        seek.progress = (initial - BubblePrefs.ICON_SIZE_MIN).coerceIn(0, seek.max)
        tv.text = "$initial"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = (progress + BubblePrefs.ICON_SIZE_MIN).coerceIn(BubblePrefs.ICON_SIZE_MIN, BubblePrefs.ICON_SIZE_MAX)
                tv.text = "$v"
                update(v)
                saveConfig()
                refreshPreview()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun setIconSize(value: Int) {
        val v = value.coerceIn(BubblePrefs.ICON_SIZE_MIN, BubblePrefs.ICON_SIZE_MAX)
        iconSize = v
        findViewById<android.widget.SeekBar>(R.id.seek_icon).progress = v - BubblePrefs.ICON_SIZE_MIN
        findViewById<TextView>(R.id.tv_icon).text = "$v"
        saveConfig()
        refreshPreview()
    }

    private fun setupBgAlphaSeekBar(
        seekId: Int,
        tvId: Int,
        initial: Int,
        update: (Int) -> Unit
    ) {
        val seek = findViewById<android.widget.SeekBar>(seekId)
        val tv = findViewById<TextView>(tvId)
        seek.max = 100
        seek.progress = initial.coerceIn(0, 100)
        tv.text = "$initial"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tv.text = "$progress"
                update(progress)
                saveConfig()
                refreshPreview()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun setBgAlpha(value: Int) {
        val v = value.coerceIn(0, 100)
        bgAlpha = v
        findViewById<android.widget.SeekBar>(R.id.seek_bg_alpha).progress = v
        findViewById<TextView>(R.id.tv_bg_alpha).text = "$v"
        saveConfig()
        refreshPreview()
    }

    private fun refreshPreview() {
        if (previewView == null) return
        dismissPreview()
        showPreview()
    }

    private fun setPreviewCount(c: Int) {
        previewCount = c.coerceIn(1, 3)
        updatePreviewSeg()
        updateGapUI()
        saveConfig()
        refreshPreview()
    }

    private fun updatePreviewSeg() {
        val ids = mapOf(
            1 to R.id.btn_preview_1,
            2 to R.id.btn_preview_2,
            3 to R.id.btn_preview_3
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == previewCount
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
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
        if (Settings.canDrawOverlays(this)) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun setStep(s: Int) {
        step = s
        updateStepUi()
    }

    private fun setMaxLen(v: Int) {
        maxLen = v
        updateMaxLenSeg()
        saveConfig()
    }

    private fun updateMaxLenSeg() {
        val ids = mapOf(
            50 to R.id.btn_len_50,
            100 to R.id.btn_len_100,
            150 to R.id.btn_len_150,
            200 to R.id.btn_len_200,
            250 to R.id.btn_len_250,
            400 to R.id.btn_len_400
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == maxLen
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
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
        for (i in 1..3) {
            posY[i] = BubblePrefs.DEFAULT_TOP_OFFSET
            posX[i] = BubblePrefs.DEFAULT_X_OFFSET
        }
        applyOffset()
    }

    private fun updateOffsetLabel() {
        val n = previewCount.coerceIn(1, 3)
        findViewById<TextView>(R.id.tv_offset).text = "$n 个图标  Y: ${posY[n]}  X: ${posX[n]}"
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
            val n = previewCount.coerceIn(1, 3)
            x = dp(posX[n])
            y = dp(posY[n])
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
        val n = previewCount.coerceIn(1, 3)
        posY[n] = (posY[n] + delta).coerceIn(0, 600)
        applyOffset()
    }

    private fun moveX(delta: Int) {
        val n = previewCount.coerceIn(1, 3)
        posX[n] = (posX[n] + delta).coerceIn(-400, 400)
        applyOffset()
    }

    private fun applyOffset() {
        saveConfig()
        updateOffsetLabel()
        val params = previewParams
        val view = previewView
        if (params != null && view != null) {
            val n = previewCount.coerceIn(1, 3)
            params.y = dp(posY[n])
            params.x = dp(posX[n])
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Throwable) {
            }
        }
    }

    private fun loadConfig() {
        val m = AppConfig.read(this)
        android.util.Log.i(
            "SuperMiApp",
            "loadConfig map=${m.size} y='${m["y"]}' addr='${m["addr_app"]}' url='${m["url_app"]}'"
        )
        if (m.isEmpty()) {
            try {
                val lines = File(filesDir, "supermi_pos").readLines()
                if (lines.size >= 2) {
                    posY[1] = lines[0].trim().toIntOrNull() ?: posY[1]
                    posX[1] = lines[1].trim().toIntOrNull() ?: posX[1]
                    for (i in 2..3) {
                        posY[i] = posY[1]
                        posX[i] = posX[1]
                    }
                }
            } catch (_: Throwable) {
            }
        } else {
            for (i in 1..3) {
                posY[i] = m["y$i"]?.toIntOrNull() ?: posY[i]
                posX[i] = m["x$i"]?.toIntOrNull() ?: posX[i]
            }
            if (m["x1"] == null) {
                val oldX = m[BubblePosProvider.KEY_X]?.toIntOrNull() ?: BubblePrefs.DEFAULT_X_OFFSET
                val oldY = m[BubblePosProvider.KEY_Y]?.toIntOrNull() ?: BubblePrefs.DEFAULT_TOP_OFFSET
                for (i in 1..3) {
                    posX[i] = oldX
                    posY[i] = oldY
                }
            }
        }
        loadFieldsFromSettings(m)
        syncProvider()
    }

    private fun loadFieldsFromSettings(m: Map<String, String>) {
        for (i in 1..3) {
            if (m["y$i"] == null) posY[i] = fromSettings("bubble_y$i", posY[i])
            if (m["x$i"] == null) posX[i] = fromSettings("bubble_x$i", posX[i])
        }
        addrAppPkg = m[BubblePosProvider.KEY_ADDR_APP] ?: fromSettingsString("addr_app") ?: addrAppPkg
        urlAppPkg = m[BubblePosProvider.KEY_URL_APP] ?: fromSettingsString("url_app") ?: urlAppPkg
        phoneAppPkg = m[BubblePosProvider.KEY_PHONE_APP] ?: fromSettingsString("phone_app") ?: phoneAppPkg
        if (m.containsKey(BubblePosProvider.KEY_DEBUG)) {
            debugEnabled = m[BubblePosProvider.KEY_DEBUG] == "1"
        } else {
            debugEnabled = fromSettings("debug", if (debugEnabled) 1 else 0) == 1
        }
        maxLen = m[BubblePosProvider.KEY_MAX_LEN]?.toIntOrNull() ?: fromSettings("max_len", maxLen)
        if (maxLen !in MAX_LEN_VALUES) maxLen = BubblePrefs.DEFAULT_MAX_LEN
        previewCount = m[BubblePosProvider.KEY_PREVIEW_ICONS]?.toIntOrNull()?.coerceIn(1, 3) ?: previewCount
        gap12_2 = m[BubblePosProvider.KEY_GAP12_2]?.toIntOrNull()?.coerceIn(0, 50)
            ?: m[BubblePosProvider.KEY_GAP12]?.toIntOrNull()?.coerceIn(0, 50)
            ?: fromSettings("gap12_2", fromSettings("gap12", gap12_2))
        gap12_3 = m[BubblePosProvider.KEY_GAP12_3]?.toIntOrNull()?.coerceIn(0, 50)
            ?: m[BubblePosProvider.KEY_GAP12]?.toIntOrNull()?.coerceIn(0, 50)
            ?: fromSettings("gap12_3", fromSettings("gap12", gap12_3))
        gap23_3 = m[BubblePosProvider.KEY_GAP23_3]?.toIntOrNull()?.coerceIn(0, 50)
            ?: m[BubblePosProvider.KEY_GAP23]?.toIntOrNull()?.coerceIn(0, 50)
            ?: fromSettings("gap23_3", fromSettings("gap23", gap23_3))
        iconSize = m[BubblePosProvider.KEY_ICON_SIZE]?.toIntOrNull()
            ?.coerceIn(BubblePrefs.ICON_SIZE_MIN, BubblePrefs.ICON_SIZE_MAX)
            ?: fromSettings("icon_size", iconSize)
        bgAlpha = m[BubblePosProvider.KEY_BG_ALPHA]?.toIntOrNull()?.coerceIn(0, 100)
            ?: fromSettings("bg_alpha", bgAlpha)
        BubblePosProvider.platformRulesJson = m[PlatformRuleStore.KEY_PLATFORM_RULES]
            ?: fromSettingsString(PlatformRuleStore.KEY_PLATFORM_RULES)
            ?: PlatformRuleStore.toJson(PlatformRuleStore.DEFAULT_RULES)
        BubblePosProvider.numberRulesJson = m[NumberRuleStore.KEY_NUMBER_RULES]
            ?: fromSettingsString(NumberRuleStore.KEY_NUMBER_RULES)
            ?: NumberRuleStore.toJson(NumberRuleStore.DEFAULT_RULES)
    }

    private fun syncProvider() {
        BubblePosProvider.y1 = posY[1]
        BubblePosProvider.x1 = posX[1]
        BubblePosProvider.y2 = posY[2]
        BubblePosProvider.x2 = posX[2]
        BubblePosProvider.y3 = posY[3]
        BubblePosProvider.x3 = posX[3]
        BubblePosProvider.addrApp = addrAppPkg
        BubblePosProvider.urlApp = urlAppPkg
        BubblePosProvider.phoneApp = phoneAppPkg
        BubblePosProvider.debug = debugEnabled
        BubblePosProvider.maxLen = maxLen
        BubblePosProvider.iconSize = iconSize
        BubblePosProvider.bgAlpha = bgAlpha
        BubblePosProvider.gap12_2 = gap12_2
        BubblePosProvider.gap12_3 = gap12_3
        BubblePosProvider.gap23_3 = gap23_3
    }

    private fun saveConfig() {
        syncProvider()
        val m = AppConfig.read(this)
        m.remove(BubblePosProvider.KEY_X)
        m.remove(BubblePosProvider.KEY_Y)
        for (i in 1..3) {
            m["x$i"] = "${posX[i]}"
            m["y$i"] = "${posY[i]}"
        }
        m[BubblePosProvider.KEY_ADDR_APP] = addrAppPkg
        m[BubblePosProvider.KEY_URL_APP] = urlAppPkg
        m[BubblePosProvider.KEY_PHONE_APP] = phoneAppPkg
        m[BubblePosProvider.KEY_DEBUG] = if (debugEnabled) "1" else "0"
        m[BubblePosProvider.KEY_MAX_LEN] = "$maxLen"
        m[BubblePosProvider.KEY_PREVIEW_ICONS] = "$previewCount"
        m[BubblePosProvider.KEY_GAP12_2] = "$gap12_2"
        m[BubblePosProvider.KEY_GAP12_3] = "$gap12_3"
        m[BubblePosProvider.KEY_GAP23_3] = "$gap23_3"
        m[BubblePosProvider.KEY_ICON_SIZE] = "$iconSize"
        m[BubblePosProvider.KEY_BG_ALPHA] = "$bgAlpha"
        if (!m.containsKey(PlatformRuleStore.KEY_PLATFORM_RULES)) {
            m[PlatformRuleStore.KEY_PLATFORM_RULES] = PlatformRuleStore.toJson(PlatformRuleStore.DEFAULT_RULES)
        }
        if (!m.containsKey(NumberRuleStore.KEY_NUMBER_RULES)) {
            m[NumberRuleStore.KEY_NUMBER_RULES] = NumberRuleStore.toJson(NumberRuleStore.DEFAULT_RULES)
        }
        AppConfig.write(this, m)
    }

    private fun exportConfig() {
        saveConfig()
        exportLauncher.launch("supermi_config_backup.txt")
    }

    private fun writeExport(uri: Uri): Boolean {
        return try {
            val content = AppConfig.read(this).entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun importConfig(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("无法读取文件")
            val imported = mutableMapOf<String, String>()
            for (line in text.lines()) {
                val i = line.indexOf('=')
                if (i > 0) imported[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
            if (imported.isEmpty()) {
                Toast.makeText(this, "文件为空或格式不正确", Toast.LENGTH_SHORT).show()
                return
            }
            AppConfig.write(this, imported)
            Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
            recreate()
        } catch (_: Throwable) {
            Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fromSettings(key: String, def: Int): Int = try {
        Settings.System.getInt(contentResolver, "supermi_$key", def)
    } catch (_: Throwable) {
        def
    }

    private fun fromSettingsString(key: String): String? = try {
        Settings.System.getString(contentResolver, "supermi_$key")
    } catch (_: Throwable) {
        null
    }

    private fun buildPreviewRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val a = bgAlpha * 255 / 100
                setColor(Color.argb(a, 0x3C, 0x3C, 0x3C))
                cornerRadius = dp(16).toFloat()
            }
        }

        val icons = previewIcons()
        val isz = iconSize.coerceIn(BubblePrefs.ICON_SIZE_MIN, BubblePrefs.ICON_SIZE_MAX)
        val gap = (isz / 8).coerceAtLeast(2)
        val padH = (isz / 3).coerceAtLeast(6)
        val padV = (isz / 6).coerceAtLeast(3)
        val corner = (isz * 2 / 3).coerceAtLeast(10)
        row.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        (row.background as? GradientDrawable)?.cornerRadius = dp(corner).toFloat()
        for ((index, icon) in icons.withIndex()) {
            val lp = LinearLayout.LayoutParams(dp(isz), dp(isz))
            lp.marginStart = if (index == 0) dp(3) else dp(0)
            lp.marginEnd = when (index) {
                0 -> if (icons.size > 1) dp(if (icons.size >= 3) gap12_3 else gap12_2) else dp(3)
                1 -> if (icons.size > 2) dp(gap23_3) else dp(3)
                else -> dp(3)
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(IconUtil.rounded(icon, dp(isz), dp(isz / 4).toFloat(), resources))
                layoutParams = lp
            })
        }
        return row
    }

    private fun previewIcons(): List<android.graphics.drawable.Drawable> {
        val count = previewCount.coerceIn(1, 3)
        val icons = mutableListOf<android.graphics.drawable.Drawable>()
        icons.add(ownAppIcon())
        val top = topAppIcons()
        for (i in 0 until (count - 1)) {
            icons.add(top.getOrNull(i) ?: packageManager.defaultActivityIcon)
        }
        return icons
    }

    private fun ownAppIcon(): android.graphics.drawable.Drawable = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: Throwable) {
        packageManager.defaultActivityIcon
    }

    private var topAppIconsCache: List<android.graphics.drawable.Drawable>? = null

    private fun topAppIcons(): List<android.graphics.drawable.Drawable> {
        topAppIconsCache?.let { return it }
        val icons = mutableListOf<android.graphics.drawable.Drawable>()
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != packageName }
                .distinctBy { it.packageName }
                .sortedBy {
                    try {
                        pm.getApplicationLabel(it).toString().lowercase()
                    } catch (_: Throwable) {
                        ""
                    }
                }
                .take(2)
                .forEach { ai ->
                    try {
                        icons.add(pm.getApplicationIcon(ai))
                    } catch (_: Throwable) {
                    }
                }
        } catch (_: Throwable) {
        }
        topAppIconsCache = icons
        return icons
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
