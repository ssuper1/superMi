package com.example.supermi.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Small shared log buffer so the app can inspect logs after a Toast disappears. */
object DebugLogStore {
    private const val KEY = "supermi_debug_log"
    private const val MAX_CHARS = 24000

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "supermi-log").apply { isDaemon = true }
    }

    @Synchronized
    fun append(ctx: Context?, message: String) {
        executor.execute {
            try {
                ctx?.contentResolver?.call(Uri.parse("content://com.example.supermi.bubblepos"), "debug_log_append", null,
                    Bundle().apply { putString("line", message.replace("\n", " ")) })
            } catch (_: Throwable) { }
        }
    }

    fun read(ctx: Context?): String = try {
        ctx?.contentResolver?.call(Uri.parse("content://com.example.supermi.bubblepos"), "debug_log_read", null, null)?.getString("log").orEmpty()
    } catch (_: Throwable) { "" }

    fun clear(ctx: Context?) {
        try { ctx?.contentResolver?.call(Uri.parse("content://com.example.supermi.bubblepos"), "debug_log_clear", null, null) } catch (_: Throwable) { }
    }
}
