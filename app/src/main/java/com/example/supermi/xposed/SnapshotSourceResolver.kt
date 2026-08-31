package com.example.supermi.xposed

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import de.robv.android.xposed.XposedBridge

data class SnapshotSource(val packageName: String?, val label: String?)

object SnapshotSourceResolver {

    private const val LOOKBACK_MS = 24L * 60 * 60 * 1000
    /** 只识别由“.”连接的字母/数字片段；文件名中的“_”不纳入包名。 */
    private val DOTTED_NAME = Regex("(?:[A-Za-z][A-Za-z0-9]*\\.)+[A-Za-z][A-Za-z0-9]*")

    /**
     * 按设置选择截图来源判断方式：优先从文件名提取点号包名，失败后回退到 UsageStats。
     */
    fun resolve(ctx: Context, takenMs: Long?, fileName: String? = null): SnapshotSource {
        if (BubblePrefs.snapshotSourceByName(ctx)) {
            val packageName = extractPackageName(fileName)
            if (!packageName.isNullOrBlank()) {
                val label = appLabel(ctx, packageName)
                XposedBridge.log("SuperMi: 截图文件名来源: ${label.ifBlank { packageName }} pkg=$packageName name=$fileName")
                return SnapshotSource(packageName, label.ifBlank { packageName })
            }
            XposedBridge.log("SuperMi: 截图文件名未识别到包名，回退时间判断: name=$fileName")
        }
        return resolveByTime(ctx, takenMs)
    }

    /**
     * 用 UsageStats 找到截图时刻最靠近的前台 App。
     * 截图文件名/MediaStore 的时间戳比“收到分享”更早，因此能避开分享面板的干扰。
     */
    private fun resolveByTime(ctx: Context, takenMs: Long?): SnapshotSource {
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

    /**
     * 从文件名任意位置提取点号连接的候选包名，不验证包是否安装。
     * 先去掉扩展名，避免把 jpg/png 等当成候选的一部分；下划线会自然截断候选。
     */
    private fun extractPackageName(rawName: String?): String? {
        val decoded = rawName?.let { android.net.Uri.decode(it) } ?: return null
        val name = decoded.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        val extension = if (dot >= 0) name.substring(dot + 1) else ""
        val stem = if (extension.equals("jpg", true) ||
            extension.equals("jpeg", true) ||
            extension.equals("png", true) ||
            extension.equals("webp", true) ||
            extension.equals("heic", true)
        ) name.substring(0, dot) else name
        return DOTTED_NAME.findAll(stem)
            .map { it.value }
            .maxWithOrNull(compareBy<String> { it.count { c -> c == '.' } }.thenBy { it.length })
    }

    private fun appLabel(ctx: Context, packageName: String): String = try {
        ctx.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(ctx.packageManager)
            .toString()
    } catch (_: Throwable) {
        ""
    }
}
