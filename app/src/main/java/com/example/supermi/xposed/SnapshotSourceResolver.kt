package com.example.supermi.xposed

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import de.robv.android.xposed.XposedBridge

data class SnapshotSource(val packageName: String?, val label: String?)

object SnapshotSourceResolver {

    private const val LOOKBACK_MS = 24L * 60 * 60 * 1000

    /**
     * 用 UsageStats 找到截图时刻最靠近的前台 App。
     * 截图文件名/MediaStore 的时间戳比“收到分享”更早，因此能避开分享面板的干扰。
     */
    fun resolve(ctx: Context, takenMs: Long?): SnapshotSource {
        if (takenMs == null || takenMs <= 0L) return SnapshotSource(null, null)
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val pkg = usm?.let { manager ->
                val events = manager.queryEvents(takenMs - LOOKBACK_MS, takenMs)
                var found: String? = null
                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED,
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            val p = event.packageName
                            if (!p.isNullOrEmpty()) found = p
                        }
                    }
                }
                found
            }
            val label = pkg?.let { appLabel(ctx, it) } ?: ""
            SnapshotSource(pkg, label.ifBlank { pkg })
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: 截图来源解析失败: $t")
            SnapshotSource(null, null)
        }
    }

    private fun appLabel(ctx: Context, packageName: String): String = try {
        ctx.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(ctx.packageManager)
            .toString()
    } catch (_: Throwable) {
        ""
    }
}
