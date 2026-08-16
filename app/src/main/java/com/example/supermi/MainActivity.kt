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

    companion object {
        private val MAX_LEN_VALUES = listOf(50, 100, 150, 200, 250, 400)
    }

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
    private var maxLen: Int = BubblePrefs.DEFAULT_MAX_LEN
    private var previewCount: Int = 1
    private var gap12: Int = 6
    private var gap23: Int = 6
    private var iconSize: Int = BubblePrefs.DEFAULT_ICON_SIZE
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

        findViewById<Button>(R.id.btn_len_50).setOnClickListener { setMaxLen(50) }
        findViewById<Button>(R.id.btn_len_100).setOnClickListener { setMaxLen(100) }
        findViewById<Button>(R.id.btn_len_150).setOnClickListener { setMaxLen(150) }
        findViewById<Button>(R.id.btn_len_200).setOnClickListener { setMaxLen(200) }
        findViewById<Button>(R.id.btn_len_250).setOnClickListener { setMaxLen(250) }
        findViewById<Button>(R.id.btn_len_400).setOnClickListener { setMaxLen(400) }
        updateMaxLenSeg()

        updateStepUi()
        updatePreviewSeg()
        setupGapSeekBar(R.id.seek_gap12, R.id.tv_gap12, gap12) { gap12 = it }
        setupGapSeekBar(R.id.seek_gap23, R.id.tv_gap23, gap23) { gap23 = it }
        setupIconSeekBar(R.id.seek_icon, R.id.tv_icon, iconSize) { iconSize = it }
        findViewById<Button>(R.id.btn_reset_icon).setOnClickListener {
            setIconSize(BubblePrefs.DEFAULT_ICON_SIZE)
        }
        findViewById<Button>(R.id.btn_reset_gap12).setOnClickListener {
            setGap(1, 6)
        }
        findViewById<Button>(R.id.btn_reset_gap23).setOnClickListener {
            setGap(2, 6)
        }
        updateOffsetLabel()
        updateAppLabels()
        ensureOverlayPermission()
    }

    private fun setupGapSeekBar(
        seekId: Int,
        tvId: Int,
        initial: Int,
        update: (Int) -> Unit
    ) {
        val seek = findViewById<android.widget.SeekBar>(seekId)
        val tv = findViewById<TextView>(tvId)
        seek.max = 50
        seek.progress = initial
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

    private fun setGap(index: Int, value: Int) {
        val v = value.coerceIn(0, 50)
        if (index == 1) gap12 = v else gap23 = v
        val seekId = if (index == 1) R.id.seek_gap12 else R.id.seek_gap23
        val tvId = if (index == 1) R.id.tv_gap12 else R.id.tv_gap23
        findViewById<android.widget.SeekBar>(seekId).progress = v
        findViewById<TextView>(tvId).text = "$v"
        saveConfig()
        refreshPreview()
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

    private fun refreshPreview() {
        if (previewView == null) return
        dismissPreview()
        showPreview()
    }

    private fun setPreviewCount(c: Int) {
        previewCount = c.coerceIn(1, 3)
        updatePreviewSeg()
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
        android.util.Log.i(
            "SuperMiApp",
            "loadConfig map=${m.size} y='${m["y"]}' addr='${m["addr_app"]}' url='${m["url_app"]}'"
        )
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
            maxLen = m[BubblePosProvider.KEY_MAX_LEN]?.toIntOrNull() ?: maxLen
            if (maxLen !in MAX_LEN_VALUES) maxLen = BubblePrefs.DEFAULT_MAX_LEN
            previewCount = m[BubblePosProvider.KEY_PREVIEW_ICONS]?.toIntOrNull()?.coerceIn(1, 3) ?: previewCount
            gap12 = m[BubblePosProvider.KEY_GAP12]?.toIntOrNull()?.coerceIn(0, 50) ?: gap12
            gap23 = m[BubblePosProvider.KEY_GAP23]?.toIntOrNull()?.coerceIn(0, 50) ?: gap23
            iconSize = m[BubblePosProvider.KEY_ICON_SIZE]?.toIntOrNull()
                ?.coerceIn(BubblePrefs.ICON_SIZE_MIN, BubblePrefs.ICON_SIZE_MAX) ?: iconSize
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
        BubblePosProvider.maxLen = maxLen
        BubblePosProvider.iconSize = iconSize
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
        m[BubblePosProvider.KEY_MAX_LEN] = "$maxLen"
        m[BubblePosProvider.KEY_PREVIEW_ICONS] = "$previewCount"
        m[BubblePosProvider.KEY_GAP12] = "$gap12"
        m[BubblePosProvider.KEY_GAP23] = "$gap23"
        m[BubblePosProvider.KEY_ICON_SIZE] = "$iconSize"
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
                setColor(Color.parseColor("#D93C3C3C"))
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
                0 -> if (icons.size > 1) dp(gap12) else dp(3)
                1 -> if (icons.size > 2) dp(gap23) else dp(3)
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
