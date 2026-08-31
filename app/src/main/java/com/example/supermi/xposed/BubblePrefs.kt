package com.example.supermi.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object BubblePrefs {

    const val KEY_ADDR_APP = "supermi_addr_app"
    const val KEY_URL_APP = "supermi_url_app"
    const val KEY_PHONE_APP = "supermi_phone_app"
    const val KEY_RULES = "supermi_platform_rules"
    const val KEY_NUM_RULES = "supermi_number_rules"
    const val KEY_DEBUG = "supermi_debug"
    const val KEY_DEBUG_TOAST = "supermi_debug_toast"
    const val KEY_MAX_LEN = "supermi_max_len"
    const val DEFAULT_TOP_OFFSET = 30
    const val DEFAULT_X_OFFSET = 0
    const val DEFAULT_MAX_LEN = 400
    const val DEFAULT_DEBUG_ENABLED = false
    private val MAX_LEN_VALUES = (1..8).map { it * 100 }.toSet()

    private const val PROVIDER_URI = "content://com.example.supermi.bubblepos"
    private const val METHOD_GET = "get_config"
    private const val CACHE_TTL_MS = 3000L

    /** 供 DebugToast/DebugLogStore 等把 Provider/设置读取挪到后台，避免阻塞 system_server 主线程。 */
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "supermi-prefs").apply { isDaemon = true }
    }

    @Volatile
    private var cachedConfig: Bundle? = null

    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var lastPersistedSig: String? = null

    fun topOffsetDp(ctx: Context?, count: Int): Int {
        val n = count.coerceIn(1, 3)
        config(ctx)?.getInt("y$n")?.let { return it }
        val resolver = ctx?.contentResolver ?: return DEFAULT_TOP_OFFSET
        return Settings.System.getInt(resolver, "supermi_bubble_y$n", DEFAULT_TOP_OFFSET)
    }

    fun xOffsetDp(ctx: Context?, count: Int): Int {
        val n = count.coerceIn(1, 3)
        config(ctx)?.getInt("x$n")?.let { return it }
        val resolver = ctx?.contentResolver ?: return DEFAULT_X_OFFSET
        return Settings.System.getInt(resolver, "supermi_bubble_x$n", DEFAULT_X_OFFSET)
    }

    fun addrApps(ctx: Context?): List<String> = splitApps(config(ctx)?.getString("addr_app"))

    fun urlApps(ctx: Context?): List<String> = splitApps(config(ctx)?.getString("url_app"))

    fun phoneApps(ctx: Context?): List<String> = splitApps(config(ctx)?.getString("phone_app"))

    fun platformRulesJson(ctx: Context?): String? =
        config(ctx)?.getString(PlatformRuleStore.KEY_PLATFORM_RULES)

    fun numberRulesJson(ctx: Context?): String? =
        config(ctx)?.getString(NumberRuleStore.KEY_NUMBER_RULES)

    fun numberDefaultLen(ctx: Context?): Pair<Int, Int> {
        val b = config(ctx)
        val min = b?.getInt("num_default_min") ?: DEFAULT_LEN_MIN
        val max = b?.getInt("num_default_max") ?: DEFAULT_LEN_MAX
        return if (min > 0 && max >= min) min to max else (DEFAULT_LEN_MIN to DEFAULT_LEN_MAX)
    }

    const val DEFAULT_LEN_MIN = 11
    const val DEFAULT_LEN_MAX = 18
    const val DEFAULT_ICON_SIZE = 24
    const val ICON_SIZE_MIN = 16
    const val ICON_SIZE_MAX = 48
    const val DEFAULT_BG_ALPHA = 20
    const val DEFAULT_BG_LIGHT = false
    const val DEFAULT_BG_MODE = 0
    const val BG_MODE_DARK = 0
    const val BG_MODE_LIGHT = 1
    const val BG_MODE_SYSTEM = 2
    const val DEFAULT_BG_BORDER = false
    const val DEFAULT_DISMISS_SECS = 5
    const val DEFAULT_SNAPSHOT_MAX_COUNT = 1
    const val DEFAULT_SNAPSHOT_TTL_SECS = 60
    const val DEFAULT_SNAPSHOT_AUTO_CLEAN = true
    const val DEFAULT_SNAPSHOT_AUTO_CLOSE = false
    const val DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE = true
    const val DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE = false
    const val DEFAULT_SNAPSHOT_CORNER_DP = 12
    const val DEFAULT_SNAPSHOT_BG_BLUR = false
    /** true=优先按截图文件名判断来源，失败回退时间判断；false=始终按时间判断。 */
    const val DEFAULT_SNAPSHOT_SOURCE_BY_NAME = true
    const val DEFAULT_SNAPSHOT_DELETE_HOURS = 6
    const val DEFAULT_SNAPSHOT_DIR = ""
    const val SNAPSHOT_CORNER_MIN = 0
    const val SNAPSHOT_CORNER_MAX = 40
    const val SNAPSHOT_DELETE_HOURS_MIN = 1
    const val SNAPSHOT_DELETE_HOURS_MAX = 12

    fun snapshotDeleteHours(ctx: Context?): Int {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getInt("snapshot_delete_hours")
            ?: resolver?.let {
                Settings.System.getInt(it, "supermi_snapshot_delete_hours", DEFAULT_SNAPSHOT_DELETE_HOURS)
            }
            ?: DEFAULT_SNAPSHOT_DELETE_HOURS
        return v.coerceIn(SNAPSHOT_DELETE_HOURS_MIN, SNAPSHOT_DELETE_HOURS_MAX)
    }

    fun snapshotRecentMs(ctx: Context?): Long = snapshotDeleteHours(ctx) * 60L * 60L * 1000L

    fun snapshotMaxCount(ctx: Context?): Int {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getInt("snapshot_max_count")
            ?: resolver?.let { Settings.System.getInt(it, "supermi_snapshot_max_count", DEFAULT_SNAPSHOT_MAX_COUNT) }
            ?: DEFAULT_SNAPSHOT_MAX_COUNT
        return v.coerceIn(1, 3)
    }

    fun snapshotTtlMs(ctx: Context?): Long {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getInt("snapshot_ttl_secs")
            ?: resolver?.let { Settings.System.getInt(it, "supermi_snapshot_ttl_secs", DEFAULT_SNAPSHOT_TTL_SECS) }
            ?: DEFAULT_SNAPSHOT_TTL_SECS
        return v.coerceIn(15, 600) * 1000L
    }

    fun snapshotAutoClean(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getBoolean("snapshot_auto_clean")
            ?: resolver?.let { Settings.System.getInt(it, "supermi_snapshot_auto_clean", if (DEFAULT_SNAPSHOT_AUTO_CLEAN) 1 else 0) == 1 }
            ?: DEFAULT_SNAPSHOT_AUTO_CLEAN
        return v
    }

    /** true=按截图文件名中的包名判断来源；文件名未识别到时回退 UsageStats。 */
    fun snapshotSourceByName(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        return config(ctx)?.getBoolean("snapshot_source_by_name")
            ?: resolver?.let {
                Settings.System.getInt(
                    it,
                    "supermi_snapshot_source_by_name",
                    if (DEFAULT_SNAPSHOT_SOURCE_BY_NAME) 1 else 0
                ) == 1
            }
            ?: DEFAULT_SNAPSHOT_SOURCE_BY_NAME
    }

    /** true=定时关闭（到 TTL 自动消失），false=一直开启（只能手动关闭）。 */
    fun snapshotAutoClose(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getBoolean("snapshot_auto_close")
            ?: resolver?.let { Settings.System.getInt(it, "supermi_snapshot_auto_close", if (DEFAULT_SNAPSHOT_AUTO_CLOSE) 1 else 0) == 1 }
            ?: DEFAULT_SNAPSHOT_AUTO_CLOSE
        return v
    }

    /** true=点击来源 App 返回后直接关闭查看框；false=返回后停留在查看框。 */
    fun snapshotOpenSourceClose(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getBoolean("snapshot_open_source_close")
            ?: resolver?.let {
                Settings.System.getInt(it, "supermi_snapshot_open_source_close", if (DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE) 1 else 0) == 1
            }
            ?: DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE
        return v
    }

    /** true=点击截图气泡直接打开识别到的来源 App；来源未知或不可启动时由调用方回退查看框。 */
    fun snapshotClickOpenSource(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        return config(ctx)?.getBoolean("snapshot_click_open_source")
            ?: resolver?.let {
                Settings.System.getInt(
                    it,
                    "supermi_snapshot_click_open_source",
                    if (DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE) 1 else 0
                ) == 1
            }
            ?: DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE
    }

    /** 点击气泡时使用，绕过配置缓存，确保设置修改立即生效。 */
    fun snapshotClickOpenSourceFresh(ctx: Context?): Boolean {
        val b = freshConfig(ctx)
        if (b != null && b.containsKey("snapshot_click_open_source")) {
            return b.getBoolean("snapshot_click_open_source", DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE)
        }
        val resolver = ctx?.contentResolver ?: return DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE
        return Settings.System.getInt(
            resolver,
            "supermi_snapshot_click_open_source",
            if (DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE) 1 else 0
        ) == 1
    }

    /** true=查看框背景用白色透明模糊；false=使用普通黑底背景。 */
    fun snapshotBgBlur(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getBoolean("snapshot_bg_blur")
            ?: resolver?.let {
                Settings.System.getInt(it, "supermi_snapshot_bg_blur", if (DEFAULT_SNAPSHOT_BG_BLUR) 1 else 0) == 1
            }
            ?: DEFAULT_SNAPSHOT_BG_BLUR
        return v
    }

    /** 查看框启动时使用，不经过 3 秒缓存，确保背景模式切换后立即生效。 */
    fun snapshotBgBlurFresh(ctx: Context?): Boolean {
        val b = freshConfig(ctx)
        if (b != null && b.containsKey("snapshot_bg_blur")) {
            return b.getBoolean("snapshot_bg_blur", DEFAULT_SNAPSHOT_BG_BLUR)
        }
        val resolver = ctx?.contentResolver ?: return DEFAULT_SNAPSHOT_BG_BLUR
        return Settings.System.getInt(
            resolver,
            "supermi_snapshot_bg_blur",
            if (DEFAULT_SNAPSHOT_BG_BLUR) 1 else 0
        ) == 1
    }

    fun snapshotCornerDp(ctx: Context?): Int {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getInt("snapshot_corner_dp")
            ?: resolver?.let { Settings.System.getInt(it, "supermi_snapshot_corner_dp", DEFAULT_SNAPSHOT_CORNER_DP) }
            ?: DEFAULT_SNAPSHOT_CORNER_DP
        return v.coerceIn(SNAPSHOT_CORNER_MIN, SNAPSHOT_CORNER_MAX)
    }

    /** 查看框启动时使用，不经过 3 秒缓存，确保刚调整完就能读到新值。 */
    fun snapshotCornerDpFresh(ctx: Context?): Int {
        val b = freshConfig(ctx)
        if (b != null && b.containsKey("snapshot_corner_dp")) {
            return b.getInt("snapshot_corner_dp", DEFAULT_SNAPSHOT_CORNER_DP)
                .coerceIn(SNAPSHOT_CORNER_MIN, SNAPSHOT_CORNER_MAX)
        }
        val resolver = ctx?.contentResolver ?: return DEFAULT_SNAPSHOT_CORNER_DP
        return Settings.System.getInt(resolver, "supermi_snapshot_corner_dp", DEFAULT_SNAPSHOT_CORNER_DP)
            .coerceIn(SNAPSHOT_CORNER_MIN, SNAPSHOT_CORNER_MAX)
    }

    /** 系统截屏目录：手动配置优先，未配置时由删除链路自动检测。 */
    fun snapshotDir(ctx: Context?): String {
        val resolver = ctx?.contentResolver
        val v = config(ctx)?.getString("snapshot_dir")
            ?: resolver?.let { Settings.System.getString(it, "supermi_snapshot_dir") }
            ?: DEFAULT_SNAPSHOT_DIR
        return v
    }

    /** 删除链路使用，不经过 3 秒缓存，避免刚改完目录就读到旧值。 */
    fun snapshotDirFresh(ctx: Context?): String {
        val b = freshConfig(ctx)
        if (b != null && b.containsKey("snapshot_dir")) return b.getString("snapshot_dir").orEmpty()
        val resolver = ctx?.contentResolver ?: return DEFAULT_SNAPSHOT_DIR
        return Settings.System.getString(resolver, "supermi_snapshot_dir").orEmpty()
    }

    fun dismissSecs(ctx: Context?): Int {
        val v = config(ctx)?.getInt("dismiss_secs") ?: Settings.System.getInt(
            ctx?.contentResolver ?: return DEFAULT_DISMISS_SECS,
            "supermi_dismiss_secs", DEFAULT_DISMISS_SECS
        )
        return v.coerceIn(1, 10)
    }

    fun dismissMs(ctx: Context?): Long = dismissSecs(ctx) * 1000L

    fun iconSizeDp(ctx: Context?): Int {
        val v = config(ctx)?.getInt("icon_size") ?: Settings.System.getInt(
            ctx?.contentResolver ?: return DEFAULT_ICON_SIZE,
            "supermi_icon_size", DEFAULT_ICON_SIZE
        )
        return v.coerceIn(ICON_SIZE_MIN, ICON_SIZE_MAX)
    }

    fun bgAlpha(ctx: Context?): Int {
        val v = config(ctx)?.getInt("bg_alpha") ?: Settings.System.getInt(
            ctx?.contentResolver ?: return DEFAULT_BG_ALPHA,
            "supermi_bg_alpha", DEFAULT_BG_ALPHA
        )
        return v.coerceIn(0, 100)
    }

    fun bgLight(ctx: Context?): Boolean {
        return when (bgMode(ctx)) {
            BG_MODE_LIGHT -> true
            BG_MODE_SYSTEM -> {
                val uiMode = (ctx?.resources ?: android.content.res.Resources.getSystem()).configuration.uiMode
                (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    /** 背景模式：0=黑，1=白，2=跟随系统；兼容旧版仅有 bg_light 的配置。 */
    fun bgMode(ctx: Context?): Int {
        val resolver = ctx?.contentResolver
        val mode = config(ctx)?.getInt("bg_mode")
            ?: resolver?.let {
                val sentinel = Int.MIN_VALUE
                val value = Settings.System.getInt(it, "supermi_bg_mode", sentinel)
                if (value != sentinel) value else null
            }
        if (mode != null) return mode.coerceIn(BG_MODE_DARK, BG_MODE_SYSTEM)
        return if (bgLightLegacy(ctx)) BG_MODE_LIGHT else BG_MODE_DARK
    }

    private fun bgLightLegacy(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        return config(ctx)?.getBoolean("bg_light")
            ?: resolver?.let { Settings.System.getInt(it, "supermi_bg_light", if (DEFAULT_BG_LIGHT) 1 else 0) == 1 }
            ?: DEFAULT_BG_LIGHT
    }

    fun bgBorder(ctx: Context?): Boolean {
        val resolver = ctx?.contentResolver
        val light = bgLight(ctx)
        val specificKey = if (light) "bg_border_light" else "bg_border_dark"
        val b = config(ctx)
        if (b?.containsKey(specificKey) == true) return b.getBoolean(specificKey, DEFAULT_BG_BORDER)
        if (b?.containsKey("bg_border") == true) return b.getBoolean("bg_border", DEFAULT_BG_BORDER)
        val specificSetting = if (light) "supermi_bg_border_light" else "supermi_bg_border_dark"
        val legacy = resolver?.let { Settings.System.getInt(it, "supermi_bg_border", if (DEFAULT_BG_BORDER) 1 else 0) }
            ?: if (DEFAULT_BG_BORDER) 1 else 0
        return resolver?.let { Settings.System.getInt(it, specificSetting, legacy) == 1 } ?: (legacy == 1)
    }

    fun maxLen(ctx: Context?): Int {
        config(ctx)?.getInt("max_len")?.takeIf { it in MAX_LEN_VALUES }?.let { return it }
        val resolver = ctx?.contentResolver ?: return DEFAULT_MAX_LEN
        return Settings.System.getInt(resolver, KEY_MAX_LEN, DEFAULT_MAX_LEN)
            .takeIf { it in MAX_LEN_VALUES } ?: DEFAULT_MAX_LEN
    }

    fun gap12(ctx: Context?, count: Int): Int {
        val key = if (count >= 3) "gap12_3" else "gap12_2"
        config(ctx)?.getInt(key)?.let { return it }
        val resolver = ctx?.contentResolver ?: return 6
        return Settings.System.getInt(resolver, "supermi_$key", Settings.System.getInt(resolver, "supermi_gap12", 6))
    }

    fun gap23(ctx: Context?, count: Int): Int {
        config(ctx)?.getInt("gap23_3")?.let { return it }
        val resolver = ctx?.contentResolver ?: return 6
        return Settings.System.getInt(resolver, "supermi_gap23_3", Settings.System.getInt(resolver, "supermi_gap23", 6))
    }

    fun debugEnabled(ctx: Context?): Boolean {
        config(ctx)?.getBoolean("debug")?.let { return it }
        val resolver = ctx?.contentResolver ?: return false
        return Settings.System.getInt(resolver, KEY_DEBUG, 0) == 1
    }

    fun debugToastEnabled(ctx: Context?): Boolean {
        // 不经过 3 秒缓存，切换后立即生效（缓存里可能是旧值）
        val b = freshConfig(ctx)
        if (b != null && b.containsKey("debug_toast")) return b.getBoolean("debug_toast", true)
        val resolver = ctx?.contentResolver ?: return true
        return Settings.System.getInt(resolver, KEY_DEBUG_TOAST, 1) == 1
    }

    /** 强制在后台刷新一次缓存；主线程调用时不会做跨进程读取。 */
    fun refreshInBackground(ctx: Context?) {
        executor.execute { debugEnabled(ctx) }
    }

    private fun splitApps(value: String?): List<String> =
        value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun freshConfig(ctx: Context?): Bundle? {
        val resolver = ctx?.contentResolver ?: return null
        var token = 0L
        try {
            token = android.os.Binder.clearCallingIdentity()
            return resolver.call(Uri.parse(PROVIDER_URI), METHOD_GET, null, null)
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: fresh config query failed: $t")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        return null
    }

    /** 设置页发出刷新通知后，立即替换短期缓存，避免读取到旧的气泡样式。 */
    fun refreshCachedConfig(ctx: Context?): Boolean {
        val b = freshConfig(ctx) ?: return false
        cachedConfig = b
        cachedAt = System.currentTimeMillis()
        return true
    }

    private fun config(ctx: Context?): Bundle? {
        val resolver = ctx?.contentResolver ?: return null
        val now = System.currentTimeMillis()
        if (cachedConfig != null && now - cachedAt < CACHE_TTL_MS) return cachedConfig
        var token = 0L
        try {
            token = android.os.Binder.clearCallingIdentity()
            val b = resolver.call(Uri.parse(PROVIDER_URI), METHOD_GET, null, null)
            if (b != null) {
                cachedConfig = b
                cachedAt = now
                val sig = signature(b)
                if (sig != lastPersistedSig) {
                    persistSettings(ctx, b)
                    lastPersistedSig = sig
                }
                return b
            }
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: provider query failed: $t")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        return readSettings(ctx)
    }

    private fun signature(b: Bundle): String =
        listOf(
            b.getInt("y1", DEFAULT_TOP_OFFSET).toString(),
            b.getInt("y2", DEFAULT_TOP_OFFSET).toString(),
            b.getInt("y3", DEFAULT_TOP_OFFSET).toString(),
            b.getInt("x1", DEFAULT_X_OFFSET).toString(),
            b.getInt("x2", DEFAULT_X_OFFSET).toString(),
            b.getInt("x3", DEFAULT_X_OFFSET).toString(),
            b.getString("addr_app") ?: "",
            b.getString("url_app") ?: "",
            b.getString("phone_app") ?: "",
            b.getString(PlatformRuleStore.KEY_PLATFORM_RULES) ?: "",
            b.getString(NumberRuleStore.KEY_NUMBER_RULES) ?: "",
            b.getBoolean("debug").toString(),
            b.getBoolean("debug_toast", true).toString(),
            b.getInt("max_len", DEFAULT_MAX_LEN).toString(),
            b.getInt("gap12_2", 6).toString(),
            b.getInt("gap12_3", 6).toString(),
            b.getInt("gap23_3", 6).toString(),
            b.getInt("bg_alpha", DEFAULT_BG_ALPHA).toString(),
            b.getBoolean("bg_light", DEFAULT_BG_LIGHT).toString(),
            b.getInt("bg_mode", if (b.getBoolean("bg_light", DEFAULT_BG_LIGHT)) BG_MODE_LIGHT else BG_MODE_DARK).toString(),
            b.getBoolean("bg_border", DEFAULT_BG_BORDER).toString(),
            b.getBoolean("bg_border_dark", b.getBoolean("bg_border", DEFAULT_BG_BORDER)).toString(),
            b.getBoolean("bg_border_light", b.getBoolean("bg_border", DEFAULT_BG_BORDER)).toString(),
            b.getInt("dismiss_secs", DEFAULT_DISMISS_SECS).toString()
            ,b.getInt("snapshot_max_count", DEFAULT_SNAPSHOT_MAX_COUNT).toString(), b.getInt("snapshot_ttl_secs", DEFAULT_SNAPSHOT_TTL_SECS).toString(), b.getBoolean("snapshot_auto_clean", DEFAULT_SNAPSHOT_AUTO_CLEAN).toString(), b.getBoolean("snapshot_source_by_name", DEFAULT_SNAPSHOT_SOURCE_BY_NAME).toString(), b.getInt("snapshot_delete_hours", DEFAULT_SNAPSHOT_DELETE_HOURS).toString(), b.getBoolean("snapshot_auto_close", DEFAULT_SNAPSHOT_AUTO_CLOSE).toString(), b.getBoolean("snapshot_open_source_close", DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE).toString(), b.getBoolean("snapshot_click_open_source", DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE).toString(), b.getBoolean("snapshot_bg_blur", DEFAULT_SNAPSHOT_BG_BLUR).toString(), b.getInt("snapshot_corner_dp", DEFAULT_SNAPSHOT_CORNER_DP).toString(), b.getString("snapshot_dir") ?: ""
        ).joinToString("\u0000")

    private fun persistSettings(ctx: Context, b: Bundle) {
        try {
            val cr = ctx.contentResolver
            for (n in 1..3) {
                Settings.System.putInt(cr, "supermi_bubble_x$n", b.getInt("x$n", DEFAULT_X_OFFSET))
                Settings.System.putInt(cr, "supermi_bubble_y$n", b.getInt("y$n", DEFAULT_TOP_OFFSET))
            }
            Settings.System.putString(cr, KEY_ADDR_APP, b.getString("addr_app"))
            Settings.System.putString(cr, KEY_URL_APP, b.getString("url_app"))
            Settings.System.putString(cr, KEY_PHONE_APP, b.getString("phone_app"))
            Settings.System.putString(cr, KEY_RULES, b.getString(PlatformRuleStore.KEY_PLATFORM_RULES))
            Settings.System.putString(cr, KEY_NUM_RULES, b.getString(NumberRuleStore.KEY_NUMBER_RULES))
            Settings.System.putInt(cr, "supermi_num_default_min", b.getInt("num_default_min", DEFAULT_LEN_MIN))
            Settings.System.putInt(cr, "supermi_num_default_max", b.getInt("num_default_max", DEFAULT_LEN_MAX))
            Settings.System.putInt(cr, "supermi_icon_size", b.getInt("icon_size", DEFAULT_ICON_SIZE))
            Settings.System.putInt(cr, "supermi_bg_alpha", b.getInt("bg_alpha", DEFAULT_BG_ALPHA))
            Settings.System.putInt(cr, "supermi_bg_light", if (b.getBoolean("bg_light", DEFAULT_BG_LIGHT)) 1 else 0)
            Settings.System.putInt(cr, "supermi_bg_mode", b.getInt("bg_mode", if (b.getBoolean("bg_light", DEFAULT_BG_LIGHT)) BG_MODE_LIGHT else BG_MODE_DARK))
            Settings.System.putInt(cr, "supermi_bg_border", if (b.getBoolean("bg_border", DEFAULT_BG_BORDER)) 1 else 0)
            Settings.System.putInt(cr, "supermi_bg_border_dark", if (b.getBoolean("bg_border_dark", b.getBoolean("bg_border", DEFAULT_BG_BORDER))) 1 else 0)
            Settings.System.putInt(cr, "supermi_bg_border_light", if (b.getBoolean("bg_border_light", b.getBoolean("bg_border", DEFAULT_BG_BORDER))) 1 else 0)
            Settings.System.putInt(cr, "supermi_dismiss_secs", b.getInt("dismiss_secs", DEFAULT_DISMISS_SECS))
            Settings.System.putInt(cr, KEY_MAX_LEN, b.getInt("max_len", DEFAULT_MAX_LEN))
            Settings.System.putInt(cr, "supermi_gap12_2", b.getInt("gap12_2", 6))
            Settings.System.putInt(cr, "supermi_gap12_3", b.getInt("gap12_3", 6))
            Settings.System.putInt(cr, "supermi_gap23_3", b.getInt("gap23_3", 6))
            Settings.System.putInt(cr, KEY_DEBUG, if (b.getBoolean("debug")) 1 else 0)
            Settings.System.putInt(cr, KEY_DEBUG_TOAST, if (b.getBoolean("debug_toast", true)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_max_count", b.getInt("snapshot_max_count", DEFAULT_SNAPSHOT_MAX_COUNT))
            Settings.System.putInt(cr, "supermi_snapshot_ttl_secs", b.getInt("snapshot_ttl_secs", DEFAULT_SNAPSHOT_TTL_SECS))
            Settings.System.putInt(cr, "supermi_snapshot_auto_clean", if (b.getBoolean("snapshot_auto_clean", DEFAULT_SNAPSHOT_AUTO_CLEAN)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_source_by_name", if (b.getBoolean("snapshot_source_by_name", DEFAULT_SNAPSHOT_SOURCE_BY_NAME)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_delete_hours", b.getInt("snapshot_delete_hours", DEFAULT_SNAPSHOT_DELETE_HOURS))
            Settings.System.putInt(cr, "supermi_snapshot_auto_close", if (b.getBoolean("snapshot_auto_close", DEFAULT_SNAPSHOT_AUTO_CLOSE)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_open_source_close", if (b.getBoolean("snapshot_open_source_close", DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_click_open_source", if (b.getBoolean("snapshot_click_open_source", DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_bg_blur", if (b.getBoolean("snapshot_bg_blur", DEFAULT_SNAPSHOT_BG_BLUR)) 1 else 0)
            Settings.System.putInt(cr, "supermi_snapshot_corner_dp", b.getInt("snapshot_corner_dp", DEFAULT_SNAPSHOT_CORNER_DP))
            Settings.System.putString(cr, "supermi_snapshot_dir", b.getString("snapshot_dir"))
        } catch (_: Throwable) {
        }
    }

    private fun readSettings(ctx: Context): Bundle? = try {
        val cr = ctx.contentResolver
        Bundle().apply {
            for (n in 1..3) {
                putInt("x$n", Settings.System.getInt(cr, "supermi_bubble_x$n", DEFAULT_X_OFFSET))
                putInt("y$n", Settings.System.getInt(cr, "supermi_bubble_y$n", DEFAULT_TOP_OFFSET))
            }
            putString("addr_app", Settings.System.getString(cr, KEY_ADDR_APP))
            putString("url_app", Settings.System.getString(cr, KEY_URL_APP))
            putString("phone_app", Settings.System.getString(cr, KEY_PHONE_APP))
            putString(PlatformRuleStore.KEY_PLATFORM_RULES, Settings.System.getString(cr, KEY_RULES))
            putString(NumberRuleStore.KEY_NUMBER_RULES, Settings.System.getString(cr, KEY_NUM_RULES))
            putInt("num_default_min", Settings.System.getInt(cr, "supermi_num_default_min", DEFAULT_LEN_MIN))
            putInt("num_default_max", Settings.System.getInt(cr, "supermi_num_default_max", DEFAULT_LEN_MAX))
            putInt("icon_size", Settings.System.getInt(cr, "supermi_icon_size", DEFAULT_ICON_SIZE))
            putInt("bg_alpha", Settings.System.getInt(cr, "supermi_bg_alpha", DEFAULT_BG_ALPHA))
            putBoolean("bg_light", Settings.System.getInt(cr, "supermi_bg_light", if (DEFAULT_BG_LIGHT) 1 else 0) == 1)
            putInt("bg_mode", Settings.System.getInt(cr, "supermi_bg_mode", Int.MIN_VALUE).let {
                if (it == Int.MIN_VALUE) {
                    if (Settings.System.getInt(cr, "supermi_bg_light", if (DEFAULT_BG_LIGHT) 1 else 0) == 1) BG_MODE_LIGHT else BG_MODE_DARK
                } else it.coerceIn(BG_MODE_DARK, BG_MODE_SYSTEM)
            })
            putBoolean("bg_border", Settings.System.getInt(cr, "supermi_bg_border", if (DEFAULT_BG_BORDER) 1 else 0) == 1)
            val legacyBorder = Settings.System.getInt(cr, "supermi_bg_border", if (DEFAULT_BG_BORDER) 1 else 0)
            putBoolean("bg_border_dark", Settings.System.getInt(cr, "supermi_bg_border_dark", legacyBorder) == 1)
            putBoolean("bg_border_light", Settings.System.getInt(cr, "supermi_bg_border_light", legacyBorder) == 1)
            putInt("dismiss_secs", Settings.System.getInt(cr, "supermi_dismiss_secs", DEFAULT_DISMISS_SECS))
            putInt("max_len", Settings.System.getInt(cr, KEY_MAX_LEN, DEFAULT_MAX_LEN))
            putInt("gap12_2", Settings.System.getInt(cr, "supermi_gap12_2", Settings.System.getInt(cr, "supermi_gap12", 6)))
            putInt("gap12_3", Settings.System.getInt(cr, "supermi_gap12_3", Settings.System.getInt(cr, "supermi_gap12", 6)))
            putInt("gap23_3", Settings.System.getInt(cr, "supermi_gap23_3", Settings.System.getInt(cr, "supermi_gap23", 6)))
            putBoolean("debug", Settings.System.getInt(cr, KEY_DEBUG, 0) == 1)
            putBoolean("debug_toast", Settings.System.getInt(cr, KEY_DEBUG_TOAST, 1) == 1)
            putInt("snapshot_max_count", Settings.System.getInt(cr, "supermi_snapshot_max_count", DEFAULT_SNAPSHOT_MAX_COUNT))
            putInt("snapshot_ttl_secs", Settings.System.getInt(cr, "supermi_snapshot_ttl_secs", DEFAULT_SNAPSHOT_TTL_SECS))
            putBoolean("snapshot_auto_clean", Settings.System.getInt(cr, "supermi_snapshot_auto_clean", 1) == 1)
            putBoolean("snapshot_source_by_name", Settings.System.getInt(cr, "supermi_snapshot_source_by_name", if (DEFAULT_SNAPSHOT_SOURCE_BY_NAME) 1 else 0) == 1)
            putInt("snapshot_delete_hours", Settings.System.getInt(cr, "supermi_snapshot_delete_hours", DEFAULT_SNAPSHOT_DELETE_HOURS))
            putBoolean("snapshot_auto_close", Settings.System.getInt(cr, "supermi_snapshot_auto_close", if (DEFAULT_SNAPSHOT_AUTO_CLOSE) 1 else 0) == 1)
            putBoolean("snapshot_open_source_close", Settings.System.getInt(cr, "supermi_snapshot_open_source_close", if (DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE) 1 else 0) == 1)
            putBoolean("snapshot_click_open_source", Settings.System.getInt(cr, "supermi_snapshot_click_open_source", if (DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE) 1 else 0) == 1)
            putBoolean("snapshot_bg_blur", Settings.System.getInt(cr, "supermi_snapshot_bg_blur", if (DEFAULT_SNAPSHOT_BG_BLUR) 1 else 0) == 1)
            putInt("snapshot_corner_dp", Settings.System.getInt(cr, "supermi_snapshot_corner_dp", DEFAULT_SNAPSHOT_CORNER_DP))
            putString("snapshot_dir", Settings.System.getString(cr, "supermi_snapshot_dir"))
        }
    } catch (_: Throwable) {
        null
    }
}
