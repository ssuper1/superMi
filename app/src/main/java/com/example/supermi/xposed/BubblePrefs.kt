package com.example.supermi.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import de.robv.android.xposed.XposedBridge

object BubblePrefs {

    const val KEY_TOP_OFFSET = "supermi_bubble_top_offset"
    const val KEY_X_OFFSET = "supermi_bubble_x_offset"
    const val KEY_ADDR_APP = "supermi_addr_app"
    const val KEY_URL_APP = "supermi_url_app"
    const val KEY_PHONE_APP = "supermi_phone_app"
    const val KEY_RULES = "supermi_platform_rules"
    const val KEY_NUM_RULES = "supermi_number_rules"
    const val KEY_DEBUG = "supermi_debug"
    const val KEY_MAX_LEN = "supermi_max_len"
    const val DEFAULT_TOP_OFFSET = 30
    const val DEFAULT_X_OFFSET = 0
    const val DEFAULT_MAX_LEN = 200

    private const val PROVIDER_URI = "content://com.example.supermi.bubblepos"
    private const val METHOD_GET = "get_config"
    private const val CACHE_TTL_MS = 3000L

    @Volatile
    private var cachedConfig: Bundle? = null

    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var lastPersistedSig: String? = null

    fun topOffsetDp(ctx: Context?): Int {
        config(ctx)?.getInt("y")?.let { return it }
        val resolver = ctx?.contentResolver ?: return DEFAULT_TOP_OFFSET
        return Settings.System.getInt(resolver, KEY_TOP_OFFSET, DEFAULT_TOP_OFFSET)
    }

    fun xOffsetDp(ctx: Context?): Int {
        config(ctx)?.getInt("x")?.let { return it }
        val resolver = ctx?.contentResolver ?: return DEFAULT_X_OFFSET
        return Settings.System.getInt(resolver, KEY_X_OFFSET, DEFAULT_X_OFFSET)
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

    fun iconSizeDp(ctx: Context?): Int {
        val v = config(ctx)?.getInt("icon_size") ?: Settings.System.getInt(
            ctx?.contentResolver ?: return DEFAULT_ICON_SIZE,
            "supermi_icon_size", DEFAULT_ICON_SIZE
        )
        return v.coerceIn(ICON_SIZE_MIN, ICON_SIZE_MAX)
    }

    fun maxLen(ctx: Context?): Int {
        config(ctx)?.getInt("max_len")?.takeIf { it > 0 }?.let { return it }
        val resolver = ctx?.contentResolver ?: return DEFAULT_MAX_LEN
        return Settings.System.getInt(resolver, KEY_MAX_LEN, DEFAULT_MAX_LEN)
    }

    fun debugEnabled(ctx: Context?): Boolean {
        config(ctx)?.getBoolean("debug")?.let { return it }
        val resolver = ctx?.contentResolver ?: return false
        return Settings.System.getInt(resolver, KEY_DEBUG, 0) == 1
    }

    private fun splitApps(value: String?): List<String> =
        value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

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
            b.getInt("y", DEFAULT_TOP_OFFSET).toString(),
            b.getInt("x", DEFAULT_X_OFFSET).toString(),
            b.getString("addr_app") ?: "",
            b.getString("url_app") ?: "",
            b.getString("phone_app") ?: "",
            b.getString(PlatformRuleStore.KEY_PLATFORM_RULES) ?: "",
            b.getString(NumberRuleStore.KEY_NUMBER_RULES) ?: "",
            b.getBoolean("debug").toString(),
            b.getInt("max_len", DEFAULT_MAX_LEN).toString()
        ).joinToString("\u0000")

    private fun persistSettings(ctx: Context, b: Bundle) {
        try {
            val cr = ctx.contentResolver
            Settings.System.putInt(cr, KEY_TOP_OFFSET, b.getInt("y", DEFAULT_TOP_OFFSET))
            Settings.System.putInt(cr, KEY_X_OFFSET, b.getInt("x", DEFAULT_X_OFFSET))
            Settings.System.putString(cr, KEY_ADDR_APP, b.getString("addr_app"))
            Settings.System.putString(cr, KEY_URL_APP, b.getString("url_app"))
            Settings.System.putString(cr, KEY_PHONE_APP, b.getString("phone_app"))
            Settings.System.putString(cr, KEY_RULES, b.getString(PlatformRuleStore.KEY_PLATFORM_RULES))
            Settings.System.putString(cr, KEY_NUM_RULES, b.getString(NumberRuleStore.KEY_NUMBER_RULES))
            Settings.System.putInt(cr, "supermi_num_default_min", b.getInt("num_default_min", DEFAULT_LEN_MIN))
            Settings.System.putInt(cr, "supermi_num_default_max", b.getInt("num_default_max", DEFAULT_LEN_MAX))
            Settings.System.putInt(cr, "supermi_icon_size", b.getInt("icon_size", DEFAULT_ICON_SIZE))
            Settings.System.putInt(cr, KEY_MAX_LEN, b.getInt("max_len", DEFAULT_MAX_LEN))
            Settings.System.putInt(cr, KEY_DEBUG, if (b.getBoolean("debug")) 1 else 0)
        } catch (_: Throwable) {
        }
    }

    private fun readSettings(ctx: Context): Bundle? = try {
        val cr = ctx.contentResolver
        Bundle().apply {
            putInt("y", Settings.System.getInt(cr, KEY_TOP_OFFSET, DEFAULT_TOP_OFFSET))
            putInt("x", Settings.System.getInt(cr, KEY_X_OFFSET, DEFAULT_X_OFFSET))
            putString("addr_app", Settings.System.getString(cr, KEY_ADDR_APP))
            putString("url_app", Settings.System.getString(cr, KEY_URL_APP))
            putString("phone_app", Settings.System.getString(cr, KEY_PHONE_APP))
            putString(PlatformRuleStore.KEY_PLATFORM_RULES, Settings.System.getString(cr, KEY_RULES))
            putString(NumberRuleStore.KEY_NUMBER_RULES, Settings.System.getString(cr, KEY_NUM_RULES))
            putInt("num_default_min", Settings.System.getInt(cr, "supermi_num_default_min", DEFAULT_LEN_MIN))
            putInt("num_default_max", Settings.System.getInt(cr, "supermi_num_default_max", DEFAULT_LEN_MAX))
            putInt("icon_size", Settings.System.getInt(cr, "supermi_icon_size", DEFAULT_ICON_SIZE))
            putInt("max_len", Settings.System.getInt(cr, KEY_MAX_LEN, DEFAULT_MAX_LEN))
            putBoolean("debug", Settings.System.getInt(cr, KEY_DEBUG, 0) == 1)
        }
    } catch (_: Throwable) {
        null
    }
}
