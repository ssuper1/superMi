package com.example.supermi

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
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
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    companion object {
        private val MAX_LEN_VALUES = (1..8).map { it * 100 }
        private val SNAPSHOT_TTL_PRESETS = intArrayOf(15, 30, 60, 120, 180, 300, 600)
        private const val REPEAT_DELAY_MS = 220L
        private const val REPEAT_INTERVAL_MS = 80L
    }

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatAction: (() -> Unit)? = null
    private val repeatTick = object : Runnable {
        override fun run() {
            repeatAction?.invoke()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
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
    private var debugEnabled: Boolean = BubblePrefs.DEFAULT_DEBUG_ENABLED
    private var maxLen: Int = BubblePrefs.DEFAULT_MAX_LEN
    private var previewCount: Int = 1
    private var gap12_2: Int = 6
    private var gap12_3: Int = 6
    private var gap23_3: Int = 6
    private var iconSize: Int = BubblePrefs.DEFAULT_ICON_SIZE
    private var bgAlpha: Int = BubblePrefs.DEFAULT_BG_ALPHA
    private var bgLight: Boolean = BubblePrefs.DEFAULT_BG_LIGHT
    private var bgBorder: Boolean = BubblePrefs.DEFAULT_BG_BORDER
    private var dismissSecs: Int = BubblePrefs.DEFAULT_DISMISS_SECS
    private var snapshotMaxCount: Int = BubblePrefs.DEFAULT_SNAPSHOT_MAX_COUNT
    private var snapshotTtlSecs: Int = BubblePrefs.DEFAULT_SNAPSHOT_TTL_SECS
    private var snapshotAutoClean: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_AUTO_CLEAN
    private var snapshotSourceByName: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_SOURCE_BY_NAME
    private var snapshotDeleteHours: Int = BubblePrefs.DEFAULT_SNAPSHOT_DELETE_HOURS
    private var snapshotAutoClose: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_AUTO_CLOSE
    private var snapshotOpenSourceClose: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE
    private var snapshotCornerDp: Int = BubblePrefs.DEFAULT_SNAPSHOT_CORNER_DP
    private var snapshotBgBlur: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_BG_BLUR
    private var snapshotDir: String = BubblePrefs.DEFAULT_SNAPSHOT_DIR
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

    private val snapDirTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val dir = physicalDirFromTreeUri(uri)
        if (dir == null) {
            Toast.makeText(this, "未识别到该目录的存储路径，请改选内置存储中的目录", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        snapshotDir = dir
        saveConfig()
        updateSnapshotDirEntry()
        Toast.makeText(this, "已设置截屏目录：$dir", Toast.LENGTH_SHORT).show()
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

        val bubbleInfoText = "点击「显示预览」，气泡会真实叠加在屏幕上方展示应用后的位置，不同图标个数布局需单独调整。"
        val bubbleInfoSpannable = SpannableString(bubbleInfoText)
        val bubbleInfoHighlight = "不同图标个数布局需单独调整"
        val bubbleInfoStart = bubbleInfoText.indexOf(bubbleInfoHighlight)
        if (bubbleInfoStart >= 0) {
            bubbleInfoSpannable.setSpan(
                ForegroundColorSpan(Color.RED),
                bubbleInfoStart,
                bubbleInfoStart + bubbleInfoHighlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        findViewById<TextView>(R.id.tv_bubble_info).text = bubbleInfoSpannable

        val bubbleInfoPanel = findViewById<View>(R.id.panel_bubble_info)
        findViewById<View>(R.id.btn_bubble_info).setOnClickListener {
            bubbleInfoPanel.visibility = if (bubbleInfoPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
        findViewById<View>(R.id.btn_bubble_info_close).setOnClickListener {
            bubbleInfoPanel.visibility = View.GONE
        }
        val uiState = getSharedPreferences("ui_state", Context.MODE_PRIVATE)
        if (!uiState.getBoolean("bubble_adjust_tooltip_shown", false)) {
            bubbleInfoPanel.visibility = View.VISIBLE
            uiState.edit().putBoolean("bubble_adjust_tooltip_shown", true).apply()
        }

        val snapshotInfoPanel = findViewById<View>(R.id.panel_snapshot_info)
        findViewById<View>(R.id.btn_snapshot_info).setOnClickListener {
            snapshotInfoPanel.visibility = if (snapshotInfoPanel.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
        findViewById<View>(R.id.btn_snapshot_info_close).setOnClickListener {
            snapshotInfoPanel.visibility = View.GONE
        }
        if (!uiState.getBoolean("snapshot_bubble_tooltip_shown", false)) {
            snapshotInfoPanel.visibility = View.VISIBLE
            uiState.edit().putBoolean("snapshot_bubble_tooltip_shown", true).apply()
        }

        findViewById<Button>(R.id.btn_show).setOnClickListener { togglePreview() }
        setupRepeatDirectionButton(R.id.btn_up) { moveY(-step) }
        setupRepeatDirectionButton(R.id.btn_down) { moveY(step) }
        setupRepeatDirectionButton(R.id.btn_left) { moveX(-step) }
        setupRepeatDirectionButton(R.id.btn_right) { moveX(step) }
        findViewById<Button>(R.id.btn_reset2).setOnClickListener { resetOffset() }

        findViewById<Button>(R.id.btn_step_1).setOnClickListener { setStep(1) }
        findViewById<Button>(R.id.btn_step_5).setOnClickListener { setStep(5) }
        findViewById<Button>(R.id.btn_step_10).setOnClickListener { setStep(10) }

        findViewById<Button>(R.id.btn_preview_1).setOnClickListener { setPreviewCount(1) }
        findViewById<Button>(R.id.btn_preview_2).setOnClickListener { setPreviewCount(2) }
        findViewById<Button>(R.id.btn_preview_3).setOnClickListener { setPreviewCount(3) }

        findViewById<Button>(R.id.btn_snap_1).setOnClickListener { setSnapshotMaxCount(1) }
        findViewById<Button>(R.id.btn_snap_2).setOnClickListener { setSnapshotMaxCount(2) }
        findViewById<Button>(R.id.btn_snap_3).setOnClickListener { setSnapshotMaxCount(3) }

        findViewById<View>(R.id.row_addr).setOnClickListener { launchPicker(BubblePosProvider.KEY_ADDR_APP) }
        findViewById<View>(R.id.row_url).setOnClickListener { launchPicker(BubblePosProvider.KEY_URL_APP) }
        findViewById<View>(R.id.row_platform).setOnClickListener {
            startActivity(Intent(this, PlatformRulesActivity::class.java))
        }
        findViewById<View>(R.id.row_number).setOnClickListener {
            startActivity(Intent(this, NumberRulesActivity::class.java))
        }

        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_debug).apply {
            isSaveEnabled = false
            isChecked = debugEnabled
            setOnCheckedChangeListener { _, checked ->
                debugEnabled = checked
                saveConfig()
            }
        }
        findViewById<View>(R.id.debug_entry).setOnClickListener {
            startActivity(Intent(this, DebugDetailsActivity::class.java))
        }

        findViewById<View>(R.id.row_export).setOnClickListener { exportConfig() }
        findViewById<View>(R.id.row_import).setOnClickListener { importLauncher.launch(arrayOf("text/plain", "application/octet-stream")) }

        findViewById<View>(R.id.btn_refresh_apps).setOnClickListener {
            Toast.makeText(this, "正在后台刷新应用列表…", Toast.LENGTH_SHORT).show()
            AppListCache.refreshAsync(applicationContext) {
                runOnUiThread {
                    Toast.makeText(this, "应用列表已刷新", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<View>(R.id.header_logo).setOnClickListener {
            openExternal("https://github.com/ssuper1/superMi")
        }

        setupMaxLenSeekBar()

        updateStepUi()
        updatePreviewSeg()
        setupIconSeekBar(R.id.seek_icon, R.id.tv_icon, iconSize) { iconSize = it }
        findViewById<Button>(R.id.btn_reset_icon).setOnClickListener {
            setIconSize(BubblePrefs.DEFAULT_ICON_SIZE)
        }
        updateBgColorSeg()
        findViewById<Button>(R.id.btn_bg_dark).setOnClickListener { setBgLight(false) }
        findViewById<Button>(R.id.btn_bg_light).setOnClickListener { setBgLight(true) }
        updateBgBorderSeg()
        findViewById<Button>(R.id.btn_bg_border_off).setOnClickListener { setBgBorder(false) }
        findViewById<Button>(R.id.btn_bg_border_on).setOnClickListener { setBgBorder(true) }
        setupBgAlphaSeekBar(R.id.seek_bg_alpha, R.id.tv_bg_alpha, bgAlpha) { bgAlpha = it }
        findViewById<Button>(R.id.btn_reset_bg_alpha).setOnClickListener {
            setBgAlpha(BubblePrefs.DEFAULT_BG_ALPHA)
        }
        setupDismissSeekBar(R.id.seek_dismiss, R.id.tv_dismiss, dismissSecs) { dismissSecs = it }
        findViewById<Button>(R.id.btn_reset_dismiss).setOnClickListener {
            setDismissSecs(BubblePrefs.DEFAULT_DISMISS_SECS)
        }
        updateSnapshotSeg()
        setupSnapshotTtlSeekBar(R.id.seek_snap_ttl, R.id.tv_snap_ttl, snapshotTtlSecs) { snapshotTtlSecs = it }
        findViewById<Button>(R.id.btn_reset_snap_ttl).setOnClickListener {
            setSnapshotTtlSecs(BubblePrefs.DEFAULT_SNAPSHOT_TTL_SECS)
        }
        updateSnapshotCloseSeg()
        findViewById<Button>(R.id.btn_snap_always).setOnClickListener { setSnapshotAutoClose(false) }
        findViewById<Button>(R.id.btn_snap_timed).setOnClickListener { setSnapshotAutoClose(true) }
        applySnapshotCloseUi()
        updateSnapshotOpenSourceSeg()
        findViewById<Button>(R.id.btn_snap_return_close).setOnClickListener { setSnapshotOpenSourceReturnClose(true) }
        findViewById<Button>(R.id.btn_snap_return_viewer).setOnClickListener { setSnapshotOpenSourceReturnClose(false) }
        updateSnapshotBgSeg()
        findViewById<Button>(R.id.btn_snap_bg_normal).setOnClickListener { setSnapshotBgBlur(false) }
        findViewById<Button>(R.id.btn_snap_bg_blur).setOnClickListener { setSnapshotBgBlur(true) }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_snap_auto_clean).apply {
            isSaveEnabled = false
            isChecked = snapshotAutoClean
            setOnCheckedChangeListener { _, checked ->
                snapshotAutoClean = checked
                saveConfig()
            }
        }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_snap_source_by_name).apply {
            isSaveEnabled = false
            isChecked = snapshotSourceByName
            setOnCheckedChangeListener { _, checked ->
                snapshotSourceByName = checked
                saveConfig()
            }
        }
        updateSnapshotDeleteHoursSeg()
        findViewById<Button>(R.id.btn_snap_delete_1h).setOnClickListener { setSnapshotDeleteHours(1) }
        findViewById<Button>(R.id.btn_snap_delete_3h).setOnClickListener { setSnapshotDeleteHours(3) }
        findViewById<Button>(R.id.btn_snap_delete_6h).setOnClickListener { setSnapshotDeleteHours(6) }
        findViewById<Button>(R.id.btn_snap_delete_12h).setOnClickListener { setSnapshotDeleteHours(12) }
        val snapshotMorePanel = findViewById<View>(R.id.panel_snap_more)
        findViewById<TextView>(R.id.btn_snap_more).setOnClickListener { button ->
            val expanded = snapshotMorePanel.visibility != View.VISIBLE
            snapshotMorePanel.visibility = if (expanded) View.VISIBLE else View.GONE
            (button as TextView).text = if (expanded) "收起" else "查看更多"
        }
        findViewById<View>(R.id.row_snap_corner).setOnClickListener {
            startActivity(Intent(this, SnapshotCornerActivity::class.java))
        }
        findViewById<View>(R.id.row_snap_dir).setOnClickListener { snapDirTreeLauncher.launch(null) }
        findViewById<android.widget.SeekBar>(R.id.seek_gap12).setOnSeekBarChangeListener(gapListener(1))
        findViewById<android.widget.SeekBar>(R.id.seek_gap23).setOnSeekBarChangeListener(gapListener(2))
        findViewById<Button>(R.id.btn_reset_gap12).setOnClickListener { setGap(1, 6) }
        findViewById<Button>(R.id.btn_reset_gap23).setOnClickListener { setGap(2, 6) }
        updateGapUI()
        updateOffsetLabel()
        updateAppLabels()
        refreshSnapshotCornerEntry()
        updateSnapshotDirEntry()
        ensureOverlayPermission()
    }

    override fun onResume() {
        super.onResume()
        snapshotCornerDp = BubblePosProvider.snapshotCornerDp
        refreshSnapshotCornerEntry()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        previewView = null
        previewParams = null
        findViewById<Button>(R.id.btn_show).text = "▶ 显示预览"
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
        seek12.isSaveEnabled = false
        seek23.isSaveEnabled = false
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
        seek.isSaveEnabled = false
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
        seek.isSaveEnabled = false
        seek.max = 100
        seek.progress = initial.coerceIn(0, 100)
        tv.text = "$initial"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                tv.text = "$progress"
                update(progress)
                updatePreviewBackground()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                saveConfig()
            }
        })
    }

    private fun setBgAlpha(value: Int) {
        val v = value.coerceIn(0, 100)
        bgAlpha = v
        findViewById<android.widget.SeekBar>(R.id.seek_bg_alpha).progress = v
        findViewById<TextView>(R.id.tv_bg_alpha).text = "$v"
        saveConfig()
        updatePreviewBackground()
    }

    /** 透明度变化只更新现有 Drawable，避免拖动时反复 remove/add 悬浮窗。 */
    private fun updatePreviewBackground() {
        val drawable = previewView?.background as? GradientDrawable ?: return
        val a = bgAlpha * 255 / 100
        val rgb = if (bgLight) 0xFF else 0x3C
        drawable.setColor(Color.argb(a, rgb, rgb, rgb))
        previewView?.invalidate()
    }

    private fun setBgLight(value: Boolean) {
        bgLight = value
        updateBgColorSeg()
        saveConfig()
        refreshPreview()
    }

    private fun updateBgColorSeg() {
        val ids = mapOf(
            false to R.id.btn_bg_dark,
            true to R.id.btn_bg_light
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == bgLight
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    private fun setBgBorder(value: Boolean) {
        bgBorder = value
        updateBgBorderSeg()
        saveConfig()
        refreshPreview()
    }

    private fun updateBgBorderSeg() {
        val ids = mapOf(
            false to R.id.btn_bg_border_off,
            true to R.id.btn_bg_border_on
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == bgBorder
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    private fun setupDismissSeekBar(
        seekId: Int,
        tvId: Int,
        initial: Int,
        update: (Int) -> Unit
    ) {
        val seek = findViewById<android.widget.SeekBar>(seekId)
        val tv = findViewById<TextView>(tvId)
        seek.isSaveEnabled = false
        seek.max = 9
        seek.progress = initial.coerceIn(1, 10) - 1
        tv.text = "${initial.coerceIn(1, 10)}s"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = (progress + 1).coerceIn(1, 10)
                tv.text = "${v}s"
                update(v)
                saveConfig()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun setDismissSecs(value: Int) {
        val v = value.coerceIn(1, 10)
        dismissSecs = v
        findViewById<android.widget.SeekBar>(R.id.seek_dismiss).progress = v - 1
        findViewById<TextView>(R.id.tv_dismiss).text = "${v}s"
        saveConfig()
    }

    private fun setSnapshotMaxCount(c: Int) {
        snapshotMaxCount = c.coerceIn(1, 3)
        updateSnapshotSeg()
        saveConfig()
    }

    private fun updateSnapshotSeg() {
        val ids = mapOf(
            1 to R.id.btn_snap_1,
            2 to R.id.btn_snap_2,
            3 to R.id.btn_snap_3
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == snapshotMaxCount
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    private fun setSnapshotAutoClose(value: Boolean) {
        snapshotAutoClose = value
        updateSnapshotCloseSeg()
        applySnapshotCloseUi()
        saveConfig()
    }

    private fun setSnapshotDeleteHours(value: Int) {
        snapshotDeleteHours = value.coerceIn(
            BubblePrefs.SNAPSHOT_DELETE_HOURS_MIN,
            BubblePrefs.SNAPSHOT_DELETE_HOURS_MAX
        )
        updateSnapshotDeleteHoursSeg()
        saveConfig()
    }

    private fun updateSnapshotDeleteHoursSeg() {
        val ids = mapOf(
            1 to R.id.btn_snap_delete_1h,
            3 to R.id.btn_snap_delete_3h,
            6 to R.id.btn_snap_delete_6h,
            12 to R.id.btn_snap_delete_12h
        )
        for ((hours, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = hours == snapshotDeleteHours
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    private fun setSnapshotOpenSourceReturnClose(value: Boolean) {
        snapshotOpenSourceClose = value
        updateSnapshotOpenSourceSeg()
        saveConfig()
    }

    /** 更新「来源返回后」分段按钮的选中样式。 */
    private fun updateSnapshotOpenSourceSeg() {
        val ids = mapOf(
            true to R.id.btn_snap_return_close,
            false to R.id.btn_snap_return_viewer
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == snapshotOpenSourceClose
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    /** 更新「查看框背景」分段按钮的选中样式。 */
    private fun updateSnapshotBgSeg() {
        val ids = mapOf(
            false to R.id.btn_snap_bg_normal,
            true to R.id.btn_snap_bg_blur
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == snapshotBgBlur
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    private fun setSnapshotBgBlur(value: Boolean) {
        snapshotBgBlur = value
        updateSnapshotBgSeg()
        saveConfig()
    }

    /** 更新关闭方式分段按钮的选中样式。 */
    private fun updateSnapshotCloseSeg() {
        val ids = mapOf(
            false to R.id.btn_snap_always,
            true to R.id.btn_snap_timed
        )
        for ((v, id) in ids) {
            val btn = findViewById<Button>(id)
            val active = v == snapshotAutoClose
            btn.background = getDrawable(if (active) R.drawable.bg_seg_active else android.R.color.transparent)
            btn.backgroundTintList = null
            btn.setTextColor(resources.getColor(if (active) R.color.blue_text else R.color.text_tertiary, theme))
        }
    }

    /** 定时关闭关闭时，TTL 相关控件置灰。 */
    private fun applySnapshotCloseUi() {
        val enabled = snapshotAutoClose
        findViewById<View>(R.id.row_snap_ttl).alpha = if (enabled) 1f else 0.4f
        findViewById<android.widget.SeekBar>(R.id.seek_snap_ttl).isEnabled = enabled
        findViewById<View>(R.id.btn_reset_snap_ttl).isEnabled = enabled
    }

    private fun setupSnapshotTtlSeekBar(
        seekId: Int,
        tvId: Int,
        initial: Int,
        update: (Int) -> Unit
    ) {
        val seek = findViewById<android.widget.SeekBar>(seekId)
        val tv = findViewById<TextView>(tvId)
        seek.isSaveEnabled = false
        seek.max = SNAPSHOT_TTL_PRESETS.size - 1
        val idx = SNAPSHOT_TTL_PRESETS.indexOf(initial).let { if (it < 0) closestIndex(initial) else it }
        seek.progress = idx
        tv.text = "${SNAPSHOT_TTL_PRESETS[idx]} 秒"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = SNAPSHOT_TTL_PRESETS[progress.coerceIn(0, SNAPSHOT_TTL_PRESETS.size - 1)]
                tv.text = "$v 秒"
                update(v)
                saveConfig()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun setSnapshotTtlSecs(value: Int) {
        val v = value.coerceIn(15, 600)
        snapshotTtlSecs = v
        val seek = findViewById<android.widget.SeekBar>(R.id.seek_snap_ttl)
        val idx = SNAPSHOT_TTL_PRESETS.indexOf(v).let { if (it < 0) closestIndex(v) else it }
        seek.progress = idx
        findViewById<TextView>(R.id.tv_snap_ttl).text = "${SNAPSHOT_TTL_PRESETS[idx]} 秒"
        saveConfig()
    }

    private fun closestIndex(value: Int): Int {
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for ((i, p) in SNAPSHOT_TTL_PRESETS.withIndex()) {
            val d = kotlin.math.abs(p - value)
            if (d < bestDiff) {
                bestDiff = d
                best = i
            }
        }
        return best
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

    private fun setupMaxLenSeekBar() {
        val seek = findViewById<android.widget.SeekBar>(R.id.seek_max_len)
        val label = findViewById<TextView>(R.id.tv_max_len)
        val initial = (maxLen / 100).coerceIn(1, 8)
        seek.max = 7
        seek.progress = initial - 1
        label.text = "${initial * 100}"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                val value = (progress + 1) * 100
                maxLen = value
                label.text = "$value"
                if (fromUser) saveConfig()
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
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

    private fun refreshSnapshotCornerEntry() {
        findViewById<TextView>(R.id.tv_snap_corner_desc).text = "当前 $snapshotCornerDp dp"
    }

    private fun updateSnapshotDirEntry() {
        val desc = findViewById<TextView>(R.id.tv_snap_dir_desc)
        val manual = snapshotDir.trim()
        desc.text = if (manual.isEmpty()) {
            "未设置，点击选择截屏目录"
        } else {
            "目录：$manual"
        }
    }

    /** SAF 目录选择器返回的 tree uri 转物理路径：primary:DCIM/Screenshots -> /storage/emulated/0/DCIM/Screenshots。 */
    private fun physicalDirFromTreeUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
            val decoded = URLDecoder.decode(docId, "UTF-8")
            val volume = decoded.substringBefore(':', "primary")
            val relative = decoded.substringAfter(':', "").trim('/')
            val root = when {
                volume == "primary" -> Environment.getExternalStorageDirectory().absolutePath
                else -> "/storage/$volume"
            }
            if (relative.isEmpty()) null else "$root/$relative"
        }
        catch (_: Throwable) {
            null
        }
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

    private fun setupRepeatDirectionButton(buttonId: Int, action: () -> Unit) {
        findViewById<Button>(buttonId).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeatAction = action
                    action()
                    repeatHandler.postDelayed(repeatTick, REPEAT_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRepeatMove()
                    true
                }
                else -> true
            }
        }
    }

    private fun stopRepeatMove() {
        repeatAction = null
        repeatHandler.removeCallbacks(repeatTick)
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
            debugEnabled = fromSettings("debug", if (BubblePrefs.DEFAULT_DEBUG_ENABLED) 1 else 0) == 1
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
        bgLight = if (m.containsKey(BubblePosProvider.KEY_BG_LIGHT)) {
            m[BubblePosProvider.KEY_BG_LIGHT] != "0"
        } else {
            fromSettings("bg_light", if (bgLight) 1 else 0) == 1
        }
        bgBorder = if (m.containsKey(BubblePosProvider.KEY_BG_BORDER)) {
            m[BubblePosProvider.KEY_BG_BORDER] != "0"
        } else {
            fromSettings("bg_border", if (bgBorder) 1 else 0) == 1
        }
        dismissSecs = m[BubblePosProvider.KEY_DISMISS_SECS]?.toIntOrNull()?.coerceIn(1, 10)
            ?: fromSettings("dismiss_secs", dismissSecs)
        snapshotMaxCount = m[BubblePosProvider.KEY_SNAPSHOT_MAX_COUNT]?.toIntOrNull()?.coerceIn(1, 3)
            ?: fromSettings("snapshot_max_count", snapshotMaxCount)
        snapshotTtlSecs = m[BubblePosProvider.KEY_SNAPSHOT_TTL_SECS]?.toIntOrNull()?.coerceIn(15, 600)
            ?: fromSettings("snapshot_ttl_secs", snapshotTtlSecs)
        snapshotAutoClean = if (m.containsKey(BubblePosProvider.KEY_SNAPSHOT_AUTO_CLEAN)) {
            m[BubblePosProvider.KEY_SNAPSHOT_AUTO_CLEAN] != "0"
        } else {
            fromSettings("snapshot_auto_clean", if (snapshotAutoClean) 1 else 0) == 1
        }
        snapshotSourceByName = if (m.containsKey(BubblePosProvider.KEY_SNAPSHOT_SOURCE_BY_NAME)) {
            m[BubblePosProvider.KEY_SNAPSHOT_SOURCE_BY_NAME] != "0"
        } else {
            fromSettings("snapshot_source_by_name", if (snapshotSourceByName) 1 else 0) == 1
        }
        snapshotDeleteHours = m[BubblePosProvider.KEY_SNAPSHOT_DELETE_HOURS]?.toIntOrNull()
            ?.coerceIn(BubblePrefs.SNAPSHOT_DELETE_HOURS_MIN, BubblePrefs.SNAPSHOT_DELETE_HOURS_MAX)
            ?: fromSettings("snapshot_delete_hours", BubblePrefs.DEFAULT_SNAPSHOT_DELETE_HOURS)
                .coerceIn(BubblePrefs.SNAPSHOT_DELETE_HOURS_MIN, BubblePrefs.SNAPSHOT_DELETE_HOURS_MAX)
        snapshotAutoClose = if (m.containsKey(BubblePosProvider.KEY_SNAPSHOT_AUTO_CLOSE)) {
            m[BubblePosProvider.KEY_SNAPSHOT_AUTO_CLOSE] != "0"
        } else {
            fromSettings("snapshot_auto_close", if (snapshotAutoClose) 1 else 0) == 1
        }
        snapshotOpenSourceClose = if (m.containsKey(BubblePosProvider.KEY_SNAPSHOT_OPEN_SOURCE_CLOSE)) {
            m[BubblePosProvider.KEY_SNAPSHOT_OPEN_SOURCE_CLOSE] != "0"
        } else {
            fromSettings("snapshot_open_source_close", if (snapshotOpenSourceClose) 1 else 0) == 1
        }
        snapshotBgBlur = if (m.containsKey(BubblePosProvider.KEY_SNAPSHOT_BG_BLUR)) {
            m[BubblePosProvider.KEY_SNAPSHOT_BG_BLUR] != "0"
        } else {
            fromSettings("snapshot_bg_blur", if (snapshotBgBlur) 1 else 0) == 1
        }
        snapshotCornerDp = m[BubblePosProvider.KEY_SNAPSHOT_CORNER_DP]?.toIntOrNull()
            ?.coerceIn(BubblePrefs.SNAPSHOT_CORNER_MIN, BubblePrefs.SNAPSHOT_CORNER_MAX)
            ?: fromSettings("snapshot_corner_dp", snapshotCornerDp)
        snapshotDir = m[BubblePosProvider.KEY_SNAPSHOT_DIR]
            ?: fromSettingsString("snapshot_dir")
            ?: snapshotDir
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
        BubblePosProvider.bgLight = bgLight
        BubblePosProvider.bgBorder = bgBorder
        BubblePosProvider.dismissSecs = dismissSecs
        BubblePosProvider.snapshotMaxCount = snapshotMaxCount
        BubblePosProvider.snapshotTtlSecs = snapshotTtlSecs
        BubblePosProvider.snapshotAutoClean = snapshotAutoClean
        BubblePosProvider.snapshotSourceByName = snapshotSourceByName
        BubblePosProvider.snapshotDeleteHours = snapshotDeleteHours
        BubblePosProvider.snapshotAutoClose = snapshotAutoClose
        BubblePosProvider.snapshotOpenSourceClose = snapshotOpenSourceClose
        BubblePosProvider.snapshotBgBlur = snapshotBgBlur
        BubblePosProvider.snapshotCornerDp = snapshotCornerDp
        BubblePosProvider.snapshotDir = snapshotDir
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
        m[BubblePosProvider.KEY_BG_LIGHT] = if (bgLight) "1" else "0"
        m[BubblePosProvider.KEY_BG_BORDER] = if (bgBorder) "1" else "0"
        m[BubblePosProvider.KEY_DISMISS_SECS] = "$dismissSecs"
        m[BubblePosProvider.KEY_SNAPSHOT_MAX_COUNT] = "$snapshotMaxCount"
        m[BubblePosProvider.KEY_SNAPSHOT_TTL_SECS] = "$snapshotTtlSecs"
        m[BubblePosProvider.KEY_SNAPSHOT_AUTO_CLEAN] = if (snapshotAutoClean) "1" else "0"
        m[BubblePosProvider.KEY_SNAPSHOT_SOURCE_BY_NAME] = if (snapshotSourceByName) "1" else "0"
        m[BubblePosProvider.KEY_SNAPSHOT_DELETE_HOURS] = "$snapshotDeleteHours"
        m[BubblePosProvider.KEY_SNAPSHOT_AUTO_CLOSE] = if (snapshotAutoClose) "1" else "0"
        m[BubblePosProvider.KEY_SNAPSHOT_OPEN_SOURCE_CLOSE] = if (snapshotOpenSourceClose) "1" else "0"
        m[BubblePosProvider.KEY_SNAPSHOT_BG_BLUR] = if (snapshotBgBlur) "1" else "0"
        m[BubblePosProvider.KEY_SNAPSHOT_CORNER_DP] = "$snapshotCornerDp"
        m[BubblePosProvider.KEY_SNAPSHOT_DIR] = snapshotDir
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
            // 以当前 Activity 状态补齐截图气泡字段，兼容旧版本遗留的
            // supermi_config（旧文件可能没有新增的 snapshot_* 键）。
            val exportMap = AppConfig.read(this).apply {
                this[BubblePosProvider.KEY_SNAPSHOT_MAX_COUNT] = snapshotMaxCount.toString()
                this[BubblePosProvider.KEY_SNAPSHOT_TTL_SECS] = snapshotTtlSecs.toString()
                this[BubblePosProvider.KEY_SNAPSHOT_AUTO_CLEAN] = if (snapshotAutoClean) "1" else "0"
                this[BubblePosProvider.KEY_SNAPSHOT_SOURCE_BY_NAME] = if (snapshotSourceByName) "1" else "0"
                this[BubblePosProvider.KEY_SNAPSHOT_DELETE_HOURS] = snapshotDeleteHours.toString()
                this[BubblePosProvider.KEY_SNAPSHOT_AUTO_CLOSE] = if (snapshotAutoClose) "1" else "0"
                this[BubblePosProvider.KEY_SNAPSHOT_OPEN_SOURCE_CLOSE] = if (snapshotOpenSourceClose) "1" else "0"
                this[BubblePosProvider.KEY_SNAPSHOT_BG_BLUR] = if (snapshotBgBlur) "1" else "0"
                this[BubblePosProvider.KEY_SNAPSHOT_CORNER_DP] = snapshotCornerDp.toString()
                this[BubblePosProvider.KEY_SNAPSHOT_DIR] = snapshotDir
            }
            val content = exportMap.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
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

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildPreviewRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val a = bgAlpha * 255 / 100
                val rgb = if (bgLight) 0xFF else 0x3C
                setColor(Color.argb(a, rgb, rgb, rgb))
                if (bgBorder) {
                    setStroke(
                        dp(1),
                        if (bgLight) Color.argb(0x66, 0xFF, 0xFF, 0xFF) else Color.BLACK
                    )
                }
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
        stopRepeatMove()
        dismissPreview()
        findViewById<Button>(R.id.btn_show).text = "▶ 显示预览"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRepeatMove()
        dismissPreview()
        findViewById<Button>(R.id.btn_show).text = "▶ 显示预览"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
