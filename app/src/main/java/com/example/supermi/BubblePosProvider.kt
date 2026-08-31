package com.example.supermi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.example.supermi.xposed.BubblePrefs
import java.io.File

class BubblePosProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.supermi.bubblepos"
        const val METHOD_GET = "get_config"
        const val METHOD_DEBUG_LOG_APPEND = "debug_log_append"
        const val METHOD_DEBUG_LOG_READ = "debug_log_read"
        const val METHOD_DEBUG_LOG_CLEAR = "debug_log_clear"
        const val KEY_Y = "y"
        const val KEY_X = "x"
        const val KEY_ADDR_APP = "addr_app"
        const val KEY_URL_APP = "url_app"
        const val KEY_PHONE_APP = "phone_app"
        const val KEY_PLATFORM_RULES = "platform_rules"
        const val KEY_NUMBER_RULES = "number_rules"
        const val KEY_DEFAULT_LEN_MIN = "num_default_min"
        const val KEY_DEFAULT_LEN_MAX = "num_default_max"
        const val KEY_MAX_LEN = "max_len"
        const val KEY_DEBUG = "debug"
        const val KEY_DEBUG_TOAST = "debug_toast"
        const val KEY_PREVIEW_ICONS = "preview_icons"
        const val KEY_GAP12 = "gap12"
        const val KEY_GAP23 = "gap23"
        const val KEY_GAP12_2 = "gap12_2"
        const val KEY_GAP12_3 = "gap12_3"
        const val KEY_GAP23_3 = "gap23_3"
        const val KEY_ICON_SIZE = "icon_size"
        const val KEY_BG_ALPHA = "bg_alpha"
        const val KEY_BG_LIGHT = "bg_light"
        const val KEY_BG_MODE = "bg_mode"
        const val KEY_BG_BORDER = "bg_border"
        const val KEY_DISMISS_SECS = "dismiss_secs"
        const val KEY_SNAPSHOT_MAX_COUNT = "snapshot_max_count"
        const val KEY_SNAPSHOT_TTL_SECS = "snapshot_ttl_secs"
        const val KEY_SNAPSHOT_AUTO_CLEAN = "snapshot_auto_clean"
        const val KEY_SNAPSHOT_SOURCE_BY_NAME = "snapshot_source_by_name"
        const val KEY_SNAPSHOT_DELETE_HOURS = "snapshot_delete_hours"
        const val KEY_SNAPSHOT_AUTO_CLOSE = "snapshot_auto_close"
        const val KEY_SNAPSHOT_OPEN_SOURCE_CLOSE = "snapshot_open_source_close"
        const val KEY_SNAPSHOT_CLICK_OPEN_SOURCE = "snapshot_click_open_source"
        const val KEY_SNAPSHOT_BG_BLUR = "snapshot_bg_blur"
        const val KEY_SNAPSHOT_CORNER_DP = "snapshot_corner_dp"
        const val KEY_SNAPSHOT_DIR = "snapshot_dir"
        const val CONFIG_FILE = "supermi_config"

        @Volatile
        var y1: Int = BubblePrefs.DEFAULT_TOP_OFFSET

        @Volatile
        var x1: Int = BubblePrefs.DEFAULT_X_OFFSET

        @Volatile
        var y2: Int = BubblePrefs.DEFAULT_TOP_OFFSET

        @Volatile
        var x2: Int = BubblePrefs.DEFAULT_X_OFFSET

        @Volatile
        var y3: Int = BubblePrefs.DEFAULT_TOP_OFFSET

        @Volatile
        var x3: Int = BubblePrefs.DEFAULT_X_OFFSET

        @Volatile
        var addrApp: String = ""

        @Volatile
        var urlApp: String = ""

        @Volatile
        var phoneApp: String = ""

        @Volatile
        var platformRulesJson: String = ""

        @Volatile
        var numberRulesJson: String = ""

        @Volatile
        var defaultLenMin: Int = 11

        @Volatile
        var defaultLenMax: Int = 18

        @Volatile
        var maxLen: Int = BubblePrefs.DEFAULT_MAX_LEN

        @Volatile
        var iconSize: Int = BubblePrefs.DEFAULT_ICON_SIZE

        @Volatile
        var gap12_2: Int = 6

        @Volatile
        var gap12_3: Int = 6

        @Volatile
        var gap23_3: Int = 6

        @Volatile
        var bgAlpha: Int = BubblePrefs.DEFAULT_BG_ALPHA

        @Volatile
        var bgLight: Boolean = BubblePrefs.DEFAULT_BG_LIGHT
        @Volatile
        var bgMode: Int = BubblePrefs.DEFAULT_BG_MODE

        @Volatile
        var bgBorder: Boolean = BubblePrefs.DEFAULT_BG_BORDER

        @Volatile
        var dismissSecs: Int = BubblePrefs.DEFAULT_DISMISS_SECS

        @Volatile
        var debug: Boolean = false
        @Volatile
        var debugToast: Boolean = true
        @Volatile var snapshotMaxCount: Int = 1
        @Volatile var snapshotTtlSecs: Int = 60
        @Volatile var snapshotAutoClean: Boolean = true
        @Volatile var snapshotSourceByName: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_SOURCE_BY_NAME
        @Volatile var snapshotDeleteHours: Int = BubblePrefs.DEFAULT_SNAPSHOT_DELETE_HOURS
        @Volatile var snapshotAutoClose: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_AUTO_CLOSE
        @Volatile var snapshotOpenSourceClose: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_OPEN_SOURCE_CLOSE
        @Volatile var snapshotClickOpenSource: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_CLICK_OPEN_SOURCE
        @Volatile var snapshotBgBlur: Boolean = BubblePrefs.DEFAULT_SNAPSHOT_BG_BLUR
        @Volatile var snapshotCornerDp: Int = BubblePrefs.DEFAULT_SNAPSHOT_CORNER_DP
        @Volatile var snapshotDir: String = BubblePrefs.DEFAULT_SNAPSHOT_DIR
    }

    override fun onCreate(): Boolean {
        loadFromFile(context)
        return true
    }

    private fun loadFromFile(ctx: Context?) {
        try {
            val lines = File(ctx!!.filesDir, CONFIG_FILE).readLines()
            var bgModeSeen = false
            for (line in lines) {
                val i = line.indexOf('=')
                if (i < 0) continue
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                when (k) {
                    KEY_Y -> { y1 = v.toIntOrNull() ?: y1 }
                    KEY_X -> { x1 = v.toIntOrNull() ?: x1 }
                    "y1" -> y1 = v.toIntOrNull() ?: y1
                    "x1" -> x1 = v.toIntOrNull() ?: x1
                    "y2" -> y2 = v.toIntOrNull() ?: y2
                    "x2" -> x2 = v.toIntOrNull() ?: x2
                    "y3" -> y3 = v.toIntOrNull() ?: y3
                    "x3" -> x3 = v.toIntOrNull() ?: x3
                    KEY_ADDR_APP -> addrApp = v
                    KEY_URL_APP -> urlApp = v
                    KEY_PHONE_APP -> phoneApp = v
                    KEY_PLATFORM_RULES -> platformRulesJson = v
                    KEY_NUMBER_RULES -> numberRulesJson = v
                    KEY_DEFAULT_LEN_MIN -> defaultLenMin = v.toIntOrNull() ?: defaultLenMin
                    KEY_DEFAULT_LEN_MAX -> defaultLenMax = v.toIntOrNull() ?: defaultLenMax
                    KEY_MAX_LEN -> maxLen = v.toIntOrNull() ?: maxLen
                    KEY_ICON_SIZE -> iconSize = v.toIntOrNull() ?: iconSize
                    KEY_GAP12_2 -> gap12_2 = v.toIntOrNull() ?: gap12_2
                    KEY_GAP12_3 -> gap12_3 = v.toIntOrNull() ?: gap12_3
                    KEY_GAP23_3 -> gap23_3 = v.toIntOrNull() ?: gap23_3
                    KEY_GAP12 -> {
                        gap12_2 = v.toIntOrNull() ?: gap12_2
                        gap12_3 = v.toIntOrNull() ?: gap12_3
                    }
                    KEY_GAP23 -> gap23_3 = v.toIntOrNull() ?: gap23_3
                    KEY_BG_ALPHA -> bgAlpha = v.toIntOrNull()?.coerceIn(0, 100) ?: bgAlpha
                    KEY_BG_LIGHT -> {
                        bgLight = v != "0"
                        if (!bgModeSeen) bgMode = if (bgLight) BubblePrefs.BG_MODE_LIGHT else BubblePrefs.BG_MODE_DARK
                    }
                    KEY_BG_MODE -> {
                        bgMode = v.toIntOrNull()?.coerceIn(BubblePrefs.BG_MODE_DARK, BubblePrefs.BG_MODE_SYSTEM) ?: bgMode
                        bgModeSeen = true
                    }
                    KEY_BG_BORDER -> bgBorder = v != "0"
                    KEY_DISMISS_SECS -> dismissSecs = v.toIntOrNull()?.coerceIn(1, 10) ?: dismissSecs
                    KEY_DEBUG -> debug = v == "1"
                    KEY_DEBUG_TOAST -> debugToast = v != "0"
                    KEY_SNAPSHOT_MAX_COUNT -> snapshotMaxCount = v.toIntOrNull()?.coerceIn(1, 3) ?: snapshotMaxCount
                    KEY_SNAPSHOT_TTL_SECS -> snapshotTtlSecs = v.toIntOrNull()?.coerceIn(15, 600) ?: snapshotTtlSecs
                    KEY_SNAPSHOT_AUTO_CLEAN -> snapshotAutoClean = v != "0"
                    KEY_SNAPSHOT_SOURCE_BY_NAME -> snapshotSourceByName = v != "0"
                    KEY_SNAPSHOT_DELETE_HOURS -> snapshotDeleteHours = v.toIntOrNull()?.coerceIn(BubblePrefs.SNAPSHOT_DELETE_HOURS_MIN, BubblePrefs.SNAPSHOT_DELETE_HOURS_MAX) ?: snapshotDeleteHours
                    KEY_SNAPSHOT_AUTO_CLOSE -> snapshotAutoClose = v != "0"
                    KEY_SNAPSHOT_OPEN_SOURCE_CLOSE -> snapshotOpenSourceClose = v != "0"
                    KEY_SNAPSHOT_CLICK_OPEN_SOURCE -> snapshotClickOpenSource = v != "0"
                    KEY_SNAPSHOT_BG_BLUR -> snapshotBgBlur = v != "0"
                    KEY_SNAPSHOT_CORNER_DP -> snapshotCornerDp = v.toIntOrNull()?.coerceIn(BubblePrefs.SNAPSHOT_CORNER_MIN, BubblePrefs.SNAPSHOT_CORNER_MAX) ?: snapshotCornerDp
                    KEY_SNAPSHOT_DIR -> snapshotDir = v
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_DEBUG_LOG_APPEND) {
            val line = extras?.getString("line").orEmpty()
            if (line.isNotEmpty()) synchronized(this) {
                val prefs = context!!.getSharedPreferences("debug_logs", Context.MODE_PRIVATE)
                prefs.edit().putString("log", (prefs.getString("log", "").orEmpty() + line + "\n").takeLast(24000)).apply()
            }
            return Bundle()
        }
        if (method == METHOD_DEBUG_LOG_READ) return Bundle().apply {
            putString("log", context?.getSharedPreferences("debug_logs", Context.MODE_PRIVATE)?.getString("log", "").orEmpty())
        }
        if (method == METHOD_DEBUG_LOG_CLEAR) {
            context?.getSharedPreferences("debug_logs", Context.MODE_PRIVATE)?.edit()?.remove("log")?.apply()
            return Bundle()
        }
        if (method != METHOD_GET) return null
        return Bundle().apply {
            putInt("y1", y1)
            putInt("x1", x1)
            putInt("y2", y2)
            putInt("x2", x2)
            putInt("y3", y3)
            putInt("x3", x3)
            putString(KEY_ADDR_APP, addrApp)
            putString(KEY_URL_APP, urlApp)
            putString(KEY_PHONE_APP, phoneApp)
            putString(KEY_PLATFORM_RULES, platformRulesJson)
            putString(KEY_NUMBER_RULES, numberRulesJson)
            putInt(KEY_DEFAULT_LEN_MIN, defaultLenMin)
            putInt(KEY_DEFAULT_LEN_MAX, defaultLenMax)
            putInt(KEY_MAX_LEN, maxLen)
            putInt(KEY_ICON_SIZE, iconSize)
            putInt(KEY_BG_ALPHA, bgAlpha)
            putBoolean(KEY_BG_LIGHT, bgLight)
            putInt(KEY_BG_MODE, bgMode)
            putBoolean(KEY_BG_BORDER, bgBorder)
            putInt(KEY_DISMISS_SECS, dismissSecs)
            putInt(KEY_GAP12_2, gap12_2)
            putInt(KEY_GAP12_3, gap12_3)
            putInt(KEY_GAP23_3, gap23_3)
            putBoolean(KEY_DEBUG, debug)
            putBoolean(KEY_DEBUG_TOAST, debugToast)
            putInt(KEY_SNAPSHOT_MAX_COUNT, snapshotMaxCount)
            putInt(KEY_SNAPSHOT_TTL_SECS, snapshotTtlSecs)
            putBoolean(KEY_SNAPSHOT_AUTO_CLEAN, snapshotAutoClean)
            putBoolean(KEY_SNAPSHOT_SOURCE_BY_NAME, snapshotSourceByName)
            putInt(KEY_SNAPSHOT_DELETE_HOURS, snapshotDeleteHours)
            putBoolean(KEY_SNAPSHOT_AUTO_CLOSE, snapshotAutoClose)
            putBoolean(KEY_SNAPSHOT_OPEN_SOURCE_CLOSE, snapshotOpenSourceClose)
            putBoolean(KEY_SNAPSHOT_CLICK_OPEN_SOURCE, snapshotClickOpenSource)
            putBoolean(KEY_SNAPSHOT_BG_BLUR, snapshotBgBlur)
            putInt(KEY_SNAPSHOT_CORNER_DP, snapshotCornerDp)
            putString(KEY_SNAPSHOT_DIR, snapshotDir)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
