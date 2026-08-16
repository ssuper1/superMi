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
    const val DEFAULT_TOP_OFFSET = 30
    const val DEFAULT_X_OFFSET = 0

    private const val PROVIDER_URI = "content://com.example.supermi.bubblepos"
    private const val METHOD_GET = "get_config"
    private const val CACHE_TTL_MS = 3000L

    @Volatile
    private var cachedConfig: Bundle? = null

    @Volatile
    private var cachedAt: Long = 0L

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
                persistSettings(ctx, b)
                return b
            }
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: provider query failed: $t")
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        return readSettings(ctx)
    }

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
            putBoolean("debug", Settings.System.getInt(cr, KEY_DEBUG, 0) == 1)
        }
    } catch (_: Throwable) {
        null
    }
}
