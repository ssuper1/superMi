package com.example.supermi.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge

object DebugToast {

    private const val TAG = "SuperMi"
    private const val CHECK_TTL_MS = 2000L

    @Volatile
    private var debug = false

    @Volatile
    private var checkedAt = 0L

    private fun isDebug(): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt > CHECK_TTL_MS) {
            debug = BubblePrefs.debugEnabled(SystemContextHolder.context())
            checkedAt = now
        }
        return debug
    }

    fun log(msg: String) {
        if (isDebug()) XposedBridge.log("$TAG: $msg")
    }

    fun log(msg: String, t: Throwable) {
        XposedBridge.log("$TAG: $msg: $t")
    }

    fun show(ctx: Context, msg: String) {
        log(msg)
        if (!isDebug()) return
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                log("toast failed", t)
            }
        }
    }

    fun systemContext(): Context? = SystemContextHolder.context()
}
