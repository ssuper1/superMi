package com.example.supermi.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.XposedBridge

object DebugToast {

    private const val TAG = "SuperMi"

    @Volatile
    private var debug = false

    private val handler = Handler(Looper.getMainLooper())

    fun refreshDebug() {
        debug = BubblePrefs.debugEnabled(SystemContextHolder.context())
    }

    fun isDebug(): Boolean = debug

    fun log(msg: String) {
        if (debug) XposedBridge.log("$TAG: $msg")
    }

    fun log(msg: String, t: Throwable) {
        XposedBridge.log("$TAG: $msg: $t")
    }

    fun show(ctx: Context, msg: String) {
        log(msg)
        if (!debug) return
        handler.post {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                log("toast failed", t)
            }
        }
    }

    fun systemContext(): Context? = SystemContextHolder.context()
}
