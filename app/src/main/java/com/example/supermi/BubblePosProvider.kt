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
        const val KEY_Y = "y"
        const val KEY_X = "x"
        const val KEY_ADDR_APP = "addr_app"
        const val KEY_URL_APP = "url_app"
        const val KEY_PHONE_APP = "phone_app"
        const val KEY_PLATFORM_RULES = "platform_rules"
        const val KEY_NUMBER_RULES = "number_rules"
        const val KEY_DEBUG = "debug"
        const val CONFIG_FILE = "supermi_config"

        @Volatile
        var currentY: Int = BubblePrefs.DEFAULT_TOP_OFFSET

        @Volatile
        var currentX: Int = BubblePrefs.DEFAULT_X_OFFSET

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
        var debug: Boolean = false
    }

    override fun onCreate(): Boolean {
        loadFromFile(context)
        return true
    }

    private fun loadFromFile(ctx: Context?) {
        try {
            val lines = File(ctx!!.filesDir, CONFIG_FILE).readLines()
            for (line in lines) {
                val i = line.indexOf('=')
                if (i < 0) continue
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                when (k) {
                    KEY_Y -> currentY = v.toIntOrNull() ?: currentY
                    KEY_X -> currentX = v.toIntOrNull() ?: currentX
                    KEY_ADDR_APP -> addrApp = v
                    KEY_URL_APP -> urlApp = v
                    KEY_PHONE_APP -> phoneApp = v
                    KEY_PLATFORM_RULES -> platformRulesJson = v
                    KEY_NUMBER_RULES -> numberRulesJson = v
                    KEY_DEBUG -> debug = v == "1"
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET) return null
        return Bundle().apply {
            putInt(KEY_Y, currentY)
            putInt(KEY_X, currentX)
            putString(KEY_ADDR_APP, addrApp)
            putString(KEY_URL_APP, urlApp)
            putString(KEY_PHONE_APP, phoneApp)
            putString(KEY_PLATFORM_RULES, platformRulesJson)
            putString(KEY_NUMBER_RULES, numberRulesJson)
            putBoolean(KEY_DEBUG, debug)
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
