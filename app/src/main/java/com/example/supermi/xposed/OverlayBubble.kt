package com.example.supermi.xposed

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.supermi.SnapshotViewerActivity
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList

object OverlayBubble {

    private const val TAG = "SuperMi"
    private const val DEDUP_WINDOW_MS = 3000L
    private const val MAX_TARGETS = 3
    private const val RESOLVE_CACHE_SIZE = 16
    private const val RESOLVE_CACHE_TTL_MS = 30_000L
    private const val VIEWER_STALE_MS = 2_000L
    private const val DELETE_MATCH_WINDOW_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgHandler: Handler by lazy {
        val t = HandlerThread("supermi-bg")
        t.start()
        Handler(t.looper)
    }
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var dismissRunnable: Runnable? = null
    private var lastText: String = ""
    private var lastShownAt: Long = 0
    private var snapshotView: View? = null
    private val snapshotUris = CopyOnWriteArrayList<Uri>()
    private val snapshotSources = java.util.concurrent.ConcurrentHashMap<Uri, SnapshotSource>()
    private val snapshotBitmaps = java.util.concurrent.ConcurrentHashMap<Uri, Bitmap>()
    private var snapshotDismiss: Runnable? = null
    private var snapshotParams: WindowManager.LayoutParams? = null
    private var snapshotStyle: BubbleValues? = null
    private var snapshotViewerOpen = false
    /** 查看器跳转到来源 App 后，允许气泡在查看器仍存活时继续更新。 */
    private var snapshotBubbleAllowedWhileViewerOpen = false
    /** 防止查看器异常退出/广播丢失后永久阻塞后续截图气泡。 */
    private var snapshotViewerOpenedAt = 0L

    private data class ResolveCacheKey(
        val type: ContentClassifier.ContentType,
        val queryKey: String,
        val customApps: List<String>,
        val deepLink: Boolean
    )

    private class ResolveCache :
        LinkedHashMap<ResolveCacheKey, Pair<Long, List<AppTarget>>>(RESOLVE_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ResolveCacheKey, Pair<Long, List<AppTarget>>>?
        ): Boolean = size > RESOLVE_CACHE_SIZE
    }

    private val resolveCache = ResolveCache()

    private data class AppMeta(val label: String, val icon: Drawable)

    /** 在后台一次性读齐设置，主线程只使用这份纯内存值，不再跨进程访问 Provider/Settings。 */
    private data class BubbleValues(
        val iconSizeDp: Int,
        val bgAlpha: Int,
        val bgLight: Boolean,
        val bgBorder: Boolean,
        val gap12_2: Int,
        val gap12_3: Int,
        val gap23_3: Int,
        val maxCount: Int,
        val x1: Int,
        val x2: Int,
        val x3: Int,
        val y1: Int,
        val y2: Int,
        val y3: Int,
        val autoClose: Boolean,
        val ttlMs: Long,
        val autoClean: Boolean,
        val dismissMs: Long
    ) {
        fun xOffset(count: Int): Int = when (count.coerceIn(1, 3)) {
            1 -> x1
            2 -> x2
            else -> x3
        }

        fun yOffset(count: Int): Int = when (count.coerceIn(1, 3)) {
            1 -> y1
            2 -> y2
            else -> y3
        }

        fun gap12(count: Int): Int = if (count >= 3) gap12_3 else gap12_2

        fun gap23(count: Int): Int = if (count >= 3) gap23_3 else 6
    }

    private const val ICON_CACHE_SIZE = 16
    private const val ICON_CACHE_TTL_MS = 60_000L

    private class IconCache :
        LinkedHashMap<String, Pair<Long, AppMeta>>(ICON_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, AppMeta>>?
        ): Boolean = size > ICON_CACHE_SIZE
    }

    private val iconCache = IconCache()

    data class AppTarget(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val type: ContentClassifier.ContentType,
        val query: String,
        val openLauncher: Boolean = false
    )

    fun show(text: String) {
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastShownAt < DEDUP_WINDOW_MS) return
        lastText = text
        lastShownAt = now

        val ctx = systemContext()
        if (ctx == null) {
            DebugToast.log("systemContext 获取失败")
            return
        }
        bgHandler.post {
            try {
                DebugToast.refreshDebug()
                val recognized = ContentClassifier.classifyAll(ctx, text)
                if (recognized.isEmpty()) {
                    DebugToast.show(ctx, "未识别: ${text.take(30)}")
                    return@post
                }
                DebugToast.show(ctx, "识别: ${recognized.joinToString { it.type.name }}")
                if (BubblePrefs.debugEnabled(ctx)) {
                    for (rec in recognized) {
                        XposedBridge.log("$TAG debug recognized -> type=${rec.type} query=[${rec.query}] apps=${rec.customApps} deepLink=${rec.deepLink}")
                    }
                }
                val targets = mutableListOf<AppTarget>()
                for (rec in recognized) {
                    targets.addAll(resolveTargets(ctx, rec.type, rec.query, rec.customApps, rec.deepLink))
                }
                if (targets.isEmpty()) {
                    DebugToast.show(ctx, "无可打开App")
                    return@post
                }
                DebugToast.show(ctx, "可打开App: ${targets.size}")
                val capped = if (targets.size > MAX_TARGETS) targets.subList(0, MAX_TARGETS) else targets
                val finalTargets = capped.toList()
                val values = readBubbleValues(ctx)
                mainHandler.post {
                    dismiss()
                    showBubble(ctx, finalTargets, values)
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG show failed: $t")
            }
        }
    }

    fun showSnapshot(
        uri: Uri,
        origPath: String? = null,
        takenMs: Long? = null,
        cachePath: String? = null,
        onFinished: (() -> Unit)? = null
    ) {
        val ctx = systemContext() ?: run {
            onFinished?.invoke()
            return
        }
        bgHandler.post {
            try {
                DebugToast.refreshDebug()
                DebugToast.show(ctx, "收到截图事件: ${uri.toString().takeLast(80)}")
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmapFromUri = runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, decodeOptions)
                    }
                }.onFailure {
                    XposedBridge.log("$TAG snapshot URI read failed: $uri: $it")
                }.getOrNull()
                // Android 16/部分 MIUI 可能不接受 app -> system_server 的 FileProvider grant；
                // system_server 对应用私有 no_backup 文件本身有读权限，使用路径兜底。
                val bitmap = bitmapFromUri ?: cachePath?.let { path ->
                    runCatching {
                        File(path).inputStream().use {
                            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                        }
                    }.onFailure {
                        XposedBridge.log("$TAG snapshot cache path read failed: $path: $it")
                    }.getOrNull()
                }
                if (bitmap == null) {
                    DebugToast.show(ctx, "截图 URI 无法读取")
                    XposedBridge.log("$TAG snapshot decode failed uri=$uri cachePath=$cachePath")
                    return@post
                }
                DebugToast.show(ctx, "截图读取成功: ${bitmap.width}x${bitmap.height}")
                val source = SnapshotSourceResolver.resolve(ctx, takenMs, origPath)
                if (source.label != null) {
                    sysLog("截图来源: ${source.label} pkg=${source.packageName}")
                }
                val values = readBubbleValues(ctx)
                val stale = snapshotUris.filter { itemUri ->
                    try {
                        ctx.contentResolver.openInputStream(itemUri)?.close()
                        false
                    } catch (_: Throwable) {
                        true
                    }
                }
                mainHandler.post {
                    try {
                        // 无可见气泡、无待触发定时 → 视为新会话，丢弃历史残留 URI
                        if (!snapshotViewerOpen && snapshotView == null && snapshotDismiss == null && snapshotUris.isNotEmpty()) {
                            XposedBridge.log("$TAG snapshot 新会话: 清空残留 ${snapshotUris.size} 个旧条目")
                            snapshotUris.clear()
                            snapshotSources.clear()
                            snapshotBitmaps.clear()
                        }
                        snapshotUris.removeAll(stale)
                        stale.forEach {
                            snapshotSources.remove(it)
                            snapshotBitmaps.remove(it)
                        }
                        snapshotUris.remove(uri)
                        snapshotSources.remove(uri)
                        snapshotBitmaps.remove(uri)
                        snapshotUris.add(uri)
                        snapshotSources[uri] = source
                        snapshotBitmaps[uri] = bitmap
                        while (snapshotUris.size > values.maxCount) {
                            val old = snapshotUris.removeAt(0)
                            snapshotSources.remove(old)
                            snapshotBitmaps.remove(old)
                        }
                        // Android/MIUI 在查看器进程被系统回收时可能丢失 RESTORE_SNAPSHOT。
                        // 若状态已持续一段时间且没有对应气泡窗口，自动解除陈旧门控，
                        // 否则后续分享即使成功保存也会一直被判定为“查看器打开”。
                        if (snapshotViewerOpen && snapshotView == null &&
                            snapshotViewerOpenedAt > 0L &&
                            SystemClock.uptimeMillis() - snapshotViewerOpenedAt > VIEWER_STALE_MS
                        ) {
                            XposedBridge.log("$TAG clearing stale viewer state before showing new snapshot")
                            snapshotViewerOpen = false
                            snapshotViewerOpenedAt = 0L
                        }
                        XposedBridge.log(
                            "$TAG snapshot main state viewer=$snapshotViewerOpen " +
                                "view=${snapshotView != null} dismiss=${snapshotDismiss != null} " +
                                "uris=${snapshotUris.size}"
                        )
                        if (!snapshotViewerOpen || snapshotBubbleAllowedWhileViewerOpen) {
                            showSnapshotBubble(ctx, values)
                        } else {
                            XposedBridge.log("$TAG snapshot bubble deferred while viewer is open")
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG snapshot main failed: $t")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG snapshot load failed: $t")
            } finally {
                // URI grant由广播生命周期持有；完成解码/入队后再释放 PendingResult。
                onFinished?.invoke()
            }
        }
    }

    /** 定时关闭到期：清空本次截图并通知 app 走完整删除链路（删缓存 + 按开关删相册原图）。 */
    private fun scheduleSnapshotAutoClose(ctx: Context, values: BubbleValues) {
        if (!values.autoClose) return
        val dr = Runnable {
            try {
                snapshotUris.clear()
                snapshotSources.clear()
                snapshotBitmaps.clear()
                snapshotBubbleAllowedWhileViewerOpen = false
                dismissSnapshot()
                ctx.sendBroadcast(
                    Intent("com.example.supermi.CLEAR_SNAPSHOTS").setPackage("com.example.supermi")
                )
                sysLog("定时关闭到期，已清空截图并通知 app 清理 自动删原图=${values.autoClean}")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG 定时关闭清理失败: $t")
            }
        }
        snapshotDismiss = dr
        mainHandler.postDelayed(dr, values.ttlMs)
    }

    fun deleteSnapshot(
        uri: Uri,
        origPath: String? = null,
        origUri: Uri? = null,
        origTakenMs: Long? = null,
        origName: String? = null
    ) {
        // 在 system_server（system uid）里删相册原图，绕开 app 的 scoped storage / root 限制
        if (origPath != null) {
            sysLog("system 收到删除请求(带原图): cache=$uri orig=$origPath uri=$origUri")
            bgHandler.post {
                try {
                    deleteOriginalBySystem(origPath, origUri, origTakenMs, origName)
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG system 删原图异常: $t")
                }
            }
        }
        val ctx = systemContext() ?: return
        mainHandler.post {
            try {
                if (snapshotUris.remove(uri)) {
                    snapshotSources.remove(uri)
                    snapshotBitmaps.remove(uri)
                    dismissSnapshot()
                    if (snapshotUris.isNotEmpty() &&
                        (!snapshotViewerOpen || snapshotBubbleAllowedWhileViewerOpen)
                    ) {
                        rebuildSnapshotBubble(ctx)
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG snapshot delete main failed: $t")
            }
        }
    }

    /** system 端仅删除相册原图（不动气泡与 app 缓存），供“删除相册图片”按钮使用。 */
    fun deleteOriginalOnly(
        origPath: String,
        origUri: Uri?,
        origTakenMs: Long? = null,
        origName: String? = null
    ) {
        sysLog("system 收到仅删原图请求: path=$origPath uri=$origUri")
        bgHandler.post {
            try {
                deleteOriginalBySystem(origPath, origUri, origTakenMs, origName)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG system 仅删原图异常: $t")
            }
        }
    }

    /**
     * system uid 单点执行原图删除：精确 MediaStore URI 优先，其次设置目录内的唯一媒体行，
     * 再以已经过 App 安全校验的绝对路径兜底；厂商分享 URI 只在最后用于清理分享缓存。
     */
    private fun deleteOriginalBySystem(path: String, uri: Uri?, takenMs: Long?, name: String?) {
        val ctx = systemContext() ?: run {
            sysLog("system 删原图: 无系统上下文 path=$path")
            return
        }
        val target = File(path)
        if (!target.isAbsolute) {
            sysLog("system 拒绝原图删除: 目标不是绝对路径 path=$path")
            return
        }
        val requestedName = name?.takeIf { it.isNotBlank() } ?: target.name
        var mediaRemoved = false
        var selectedRow: MediaRow? = null

        // content://media/... 是精确媒体行；必须先核对路径/目录、文件名和时间再删除。
        if (uri?.authority.equals("media", ignoreCase = true)) {
            val exactRows = queryMediaRows(ctx, uri!!, null, null)
            val exact = exactRows.singleOrNull { rowMatchesRequest(it, target, requestedName, takenMs) }
            if (exact != null) {
                selectedRow = exact
                mediaRemoved = deleteMediaUri(ctx, uri, "精确 URI")
            } else {
                sysLog("system 精确 MediaStore URI 未通过目标校验: uri=$uri path=$path name=$requestedName")
            }
        }

        // 厂商 FileProvider 或失效的精确 URI：仅在设置目录内按强匹配顺序寻找唯一媒体行。
        if (!mediaRemoved) {
            selectedRow = findMediaRow(ctx, target, requestedName, takenMs)
            if (selectedRow != null) {
                mediaRemoved = deleteMediaUri(ctx, mediaUri(selectedRow.id), "设置目录匹配")
            } else {
                sysLog("system 设置目录内未找到唯一 MediaStore 行: path=$path name=$requestedName takenMs=${takenMs ?: 0}")
            }
        }

        // MediaStore 正常会连同物理文件一起删除；只有文件仍存在时才执行路径兜底。
        var fileGone = !target.exists()
        if (!fileGone) {
            val deleted = try {
                target.delete()
            } catch (t: Throwable) {
                sysLog("system 路径兜底删除异常: $t path=$path")
                false
            }
            fileGone = !target.exists()
            sysLog("system 路径兜底删除: delete=$deleted finalExists=${target.exists()} path=$path")
        }

        // 路径兜底成功但媒体行先前删除失败时，再清理一次精确残留行，不做宽泛时间匹配。
        if (fileGone && !mediaRemoved) {
            val staleRow = selectedRow ?: findExactMediaRow(ctx, target, requestedName)
            if (staleRow != null) {
                mediaRemoved = deleteMediaUri(ctx, mediaUri(staleRow.id), "物理文件删除后的媒体残留")
            }
        }

        // 非 MediaStore URI 表示厂商分享资源，只负责清理 .delete_screen_cache 等分享缓存。
        var shareCacheRemoved = false
        if (uri != null && !uri.authority.equals("media", ignoreCase = true)) {
            shareCacheRemoved = try {
                val count = ctx.contentResolver.delete(uri, null, null)
                sysLog("system 厂商分享缓存删除: count=$count uri=$uri")
                count > 0
            } catch (t: Throwable) {
                sysLog("system 厂商分享缓存删除异常: $t uri=$uri")
                false
            }
        }

        val mediaStillExists = findExactMediaRow(ctx, target, requestedName) != null
        sysLog(
            "system 原图删除结果: success=${!target.exists() && !mediaStillExists} " +
                "fileGone=${!target.exists()} mediaRemoved=$mediaRemoved mediaStillExists=$mediaStillExists " +
                "shareCacheRemoved=$shareCacheRemoved path=$path"
        )
    }

    private data class MediaRow(
        val id: Long,
        val data: String,
        val relativePath: String,
        val displayName: String,
        val dateTaken: Long,
        val dateAddedMs: Long
    )

    private fun mediaUri(id: Long): Uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        id
    )

    private fun deleteMediaUri(ctx: Context, uri: Uri, source: String): Boolean = try {
        val count = ctx.contentResolver.delete(uri, null, null)
        sysLog("system MediaStore 删除[$source]: count=$count uri=$uri")
        count > 0
    } catch (t: Throwable) {
        sysLog("system MediaStore 删除异常[$source]: $t uri=$uri")
        false
    }

    private fun queryMediaRows(
        ctx: Context,
        queryUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        selection: String?,
        args: Array<String>?
    ): List<MediaRow> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media._ID} DESC"
        return try {
            ctx.contentResolver.query(queryUri, projection, selection, args, sortOrder)?.use { c ->
                val found = mutableListOf<MediaRow>()
                while (c.moveToNext()) {
                    val rawData = c.getString(1)
                    val rel = c.getString(2) ?: ""
                    val displayName = c.getString(3) ?: ""
                    val data = if (!rawData.isNullOrBlank()) {
                        rawData
                    } else if (displayName.isNotBlank()) {
                        buildString {
                            append(Environment.getExternalStorageDirectory().absolutePath)
                            append("/")
                            if (rel.isNotBlank()) {
                                append(rel.trimStart('/'))
                                if (!rel.endsWith("/")) append("/")
                            }
                            append(displayName)
                        }
                    } else {
                        ""
                    }
                    found += MediaRow(
                        id = c.getLong(0),
                        data = data,
                        relativePath = rel,
                        displayName = displayName,
                        dateTaken = c.getLong(4),
                        dateAddedMs = c.getLong(5).takeIf { it > 0L }?.times(1000L) ?: 0L
                    )
                }
                found
            } ?: emptyList()
        } catch (t: Throwable) {
            sysLog("system MediaStore 查询异常: $t uri=$queryUri selection=$selection")
            emptyList()
        }
    }

    private fun findMediaRow(ctx: Context, target: File, name: String, takenMs: Long?): MediaRow? {
        findExactMediaRow(ctx, target, name)?.let { return it }
        val relativePath = relativePathFor(target) ?: return null
        if (takenMs == null || takenMs <= 0L) return null
        val from = (takenMs - DELETE_MATCH_WINDOW_MS).coerceAtLeast(0L)
        val to = takenMs + DELETE_MATCH_WINDOW_MS
        val candidates = mutableListOf<MediaRow>()
        candidates += queryMediaRows(
            ctx,
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?",
            args = arrayOf(relativePath, from.toString(), to.toString())
        )
        candidates += queryMediaRows(
            ctx,
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?",
            args = arrayOf(relativePath, (from / 1000L).toString(), (to / 1000L).toString())
        )
        val unique = candidates.distinctBy { it.id }.filter {
            rowTimeMatches(it, takenMs) && normalizedShareName(it.displayName) == normalizedShareName(name)
        }
        return unique.singleOrNull()
    }

    private fun findExactMediaRow(ctx: Context, target: File, name: String): MediaRow? {
        val targetPath = normalizedPath(target.path)
        val byData = queryMediaRows(
            ctx,
            selection = "${MediaStore.MediaColumns.DATA}=?",
            args = arrayOf(target.absolutePath)
        ).filter { normalizedPath(it.data) == targetPath }
        if (byData.size == 1) return byData.first()

        val relativePath = relativePathFor(target) ?: return null
        val names = shareNameCandidates(name)
        val byRelativeName = names.flatMap { candidateName ->
            queryMediaRows(
                ctx,
                selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                args = arrayOf(relativePath, candidateName)
            )
        }.distinctBy { it.id }.filter { normalizedPath(it.data) == targetPath }
        return byRelativeName.singleOrNull()
    }

    private fun rowMatchesRequest(row: MediaRow, target: File, name: String, takenMs: Long?): Boolean {
        if (normalizedPath(row.data) == normalizedPath(target.path)) return true
        val expectedRelativePath = relativePathFor(target) ?: return false
        return row.relativePath.equals(expectedRelativePath, ignoreCase = true) &&
            normalizedShareName(row.displayName) == normalizedShareName(name) &&
            rowTimeMatches(row, takenMs)
    }

    private fun rowTimeMatches(row: MediaRow, takenMs: Long?): Boolean {
        if (takenMs == null || takenMs <= 0L) return true
        return listOf(row.dateTaken, row.dateAddedMs).any {
            it > 0L && kotlin.math.abs(it - takenMs) <= DELETE_MATCH_WINDOW_MS
        }
    }

    private fun relativePathFor(target: File): String? {
        val root = normalizedPath(Environment.getExternalStorageDirectory().absolutePath).trimEnd('/')
        val parent = normalizedPath(target.parentFile?.path ?: return null).trimEnd('/')
        if (parent != root && !parent.startsWith("$root/")) return null
        return parent.removePrefix(root).trimStart('/').let { if (it.isEmpty()) "" else "$it/" }
    }

    private fun normalizedPath(path: String): String = try {
        File(path).canonicalPath.replace('\\', '/')
    } catch (_: Throwable) {
        File(path).absolutePath.replace('\\', '/')
    }

    private fun normalizedShareName(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return stem.removeSuffix("_com.example.supermi").lowercase() + extension.lowercase()
    }

    private fun shareNameCandidates(name: String): Set<String> {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return linkedSetOf(name, stem.removeSuffix("_com.example.supermi") + extension)
            .filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun sysLog(message: String) {
        val ctx = systemContext() ?: return
        if (BubblePrefs.debugEnabled(ctx)) {
            XposedBridge.log("$TAG $message")
            DebugLogStore.append(ctx, message)
        }
    }

    private fun readBubbleValues(ctx: Context): BubbleValues = BubbleValues(
        iconSizeDp = BubblePrefs.iconSizeDp(ctx),
        bgAlpha = BubblePrefs.bgAlpha(ctx),
        bgLight = BubblePrefs.bgLight(ctx),
        bgBorder = BubblePrefs.bgBorder(ctx),
        gap12_2 = BubblePrefs.gap12(ctx, 2),
        gap12_3 = BubblePrefs.gap12(ctx, 3),
        gap23_3 = BubblePrefs.gap23(ctx, 3),
        maxCount = BubblePrefs.snapshotMaxCount(ctx),
        x1 = BubblePrefs.xOffsetDp(ctx, 1),
        x2 = BubblePrefs.xOffsetDp(ctx, 2),
        x3 = BubblePrefs.xOffsetDp(ctx, 3),
        y1 = BubblePrefs.topOffsetDp(ctx, 1),
        y2 = BubblePrefs.topOffsetDp(ctx, 2),
        y3 = BubblePrefs.topOffsetDp(ctx, 3),
        autoClose = BubblePrefs.snapshotAutoClose(ctx),
        ttlMs = BubblePrefs.snapshotTtlMs(ctx),
        autoClean = BubblePrefs.snapshotAutoClean(ctx),
        dismissMs = BubblePrefs.dismissMs(ctx)
    )

    private fun rebuildSnapshotBubble(ctx: Context) {
        val values = snapshotStyle ?: readBubbleValues(ctx).also { snapshotStyle = it }
        showSnapshotBubble(ctx, values)
    }

    /** 查看框退出后，按仍然存在的截图队列恢复气泡。 */
    fun restoreSnapshotBubble() {
        val ctx = systemContext() ?: return
        mainHandler.post {
            snapshotViewerOpen = false
            snapshotViewerOpenedAt = 0L
            snapshotBubbleAllowedWhileViewerOpen = false
            if (snapshotView == null && snapshotUris.isNotEmpty()) {
                rebuildSnapshotBubble(ctx)
            }
        }
    }

    /** 来源 App 在前台时显示可点击气泡，但保留查看器占用状态。 */
    fun showSnapshotBubbleWhileViewerOpen() {
        val ctx = systemContext() ?: return
        mainHandler.post {
            if (snapshotViewerOpen && snapshotUris.isNotEmpty()) {
                snapshotBubbleAllowedWhileViewerOpen = true
                rebuildSnapshotBubble(ctx)
            }
        }
    }

    private fun showSnapshotBubble(ctx: Context, values: BubbleValues) {
        dismissSnapshot()
        if (snapshotUris.isEmpty()) return
        val n = snapshotUris.size.coerceIn(1, 3)
        val size = values.iconSizeDp
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(size / 3), dp(size / 6), dp(size / 3), dp(size / 6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val a = values.bgAlpha * 255 / 100
                val rgb = if (values.bgLight) 0xFF else 0x3C
                setColor(Color.argb(a, rgb, rgb, rgb))
                if (values.bgBorder) {
                    setStroke(
                        dp(1),
                        if (values.bgLight) Color.argb(0x66, 0xFF, 0xFF, 0xFF) else Color.BLACK
                    )
                }
                cornerRadius = dp(size * 2 / 3).toFloat()
            }
        }
        for ((index, itemUri) in snapshotUris.withIndex()) {
            val image = roundedSnapshotImage(ctx, size).apply {
                snapshotBitmaps[itemUri]?.let { setImageDrawable(BitmapDrawable(ctx.resources, it)) }
                setOnClickListener {
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    openSnapshotViewer(
                        ctx, snapshotUris.toList(), index,
                        loc[0], loc[1], width, height
                    )
                }
            }
            val lp = LinearLayout.LayoutParams(dp(size), dp(size))
            lp.marginEnd = if (index + 1 < snapshotUris.size) dp(values.gap12(n)) else 0
            row.addView(image, lp)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(snapshotAvoidX(ctx, n, values))
            y = dp(values.yOffset(n))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTitle("SuperMi Snapshot")
        }
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(row, params)
            snapshotParams = params
            snapshotView = row
            snapshotStyle = values
            XposedBridge.log("$TAG snapshot bubble added x=${params.x} y=${params.y} count=$n")
            scheduleSnapshotAutoClose(ctx, values)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG snapshot bubble add failed: $t")
        }
    }

    private fun dismissSnapshot() {
        snapshotDismiss?.let { mainHandler.removeCallbacks(it) }
        snapshotDismiss = null
        val v = snapshotView
        val ctx = systemContext()
        if (v != null && ctx != null) {
            try {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Throwable) {
            }
        }
        snapshotParams = null
        snapshotView = null
    }

    /** 圆角方形缩略图：CENTER_CROP 裁切，圆角按图标尺寸的 1/4。 */
    private fun roundedSnapshotImage(ctx: Context, size: Int): ImageView {
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(size / 4).toFloat())
                }
            }
        }
    }

    /** 打开多图查看框：传全部 URI、当前下标与所点缩略图的屏幕坐标，用于从气泡位置展开。 */
    private fun openSnapshotViewer(
        ctx: Context,
        uris: List<Uri>,
        index: Int,
        originX: Int,
        originY: Int,
        originW: Int,
        originH: Int
    ) {
        if (uris.isEmpty()) return
        val i = index.coerceIn(0, uris.size - 1)
        try {
            snapshotBubbleAllowedWhileViewerOpen = false
            val clip = ClipData.newRawUri("snapshots", uris[0])
            for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            val intent = Intent().apply {
                setComponent(
                    ComponentName("com.example.supermi", "com.example.supermi.SnapshotViewerActivity")
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = clip
                putStringArrayListExtra("snapshot_uris", ArrayList(uris.map { it.toString() }))
                putStringArrayListExtra(
                    SnapshotViewerActivity.EXTRA_SOURCES,
                    ArrayList(uris.map { snapshotSources[it]?.label ?: "" })
                )
                putStringArrayListExtra(
                    SnapshotViewerActivity.EXTRA_SOURCE_PACKAGES,
                    ArrayList(uris.map { snapshotSources[it]?.packageName ?: "" })
                )
                putExtra("snapshot_index", i)
                putExtra("snapshot_uri", uris[i].toString())
                putExtra(SnapshotViewerActivity.EXTRA_ORIGIN_X, originX)
                putExtra(SnapshotViewerActivity.EXTRA_ORIGIN_Y, originY)
                putExtra(SnapshotViewerActivity.EXTRA_ORIGIN_W, originW)
                putExtra(SnapshotViewerActivity.EXTRA_ORIGIN_H, originH)
            }
            // 先同步标记查看器状态，再启动 Activity，避免不同设备上恢复/隐藏任务乱序。
            snapshotViewerOpen = true
            snapshotViewerOpenedAt = SystemClock.uptimeMillis()
            ctx.startActivity(intent)
            // 查看器已拿到气泡原点坐标；启动成功后移除气泡，避免遮挡查看内容。
            // 用 post 避免在当前 ImageView 点击分发过程中直接移除父窗口。
            mainHandler.post {
                // 若查看器已快速退出并完成恢复，不再执行过期的隐藏任务。
                if (snapshotViewerOpen) dismissSnapshot()
            }
        } catch (t: Throwable) {
            snapshotViewerOpen = false
            snapshotViewerOpenedAt = 0L
            snapshotBubbleAllowedWhileViewerOpen = false
            XposedBridge.log("$TAG openSnapshotViewer failed: $t")
        }
    }

    private fun snapshotWidthDp(values: BubbleValues): Int {
        val n = snapshotUris.size.coerceIn(1, 3)
        val size = values.iconSizeDp
        val gap = if (n > 1) values.gap12(n) else 0
        return n * size + (n - 1) * gap + (size / 3) * 2
    }

    private fun textBubbleWidthDp(values: BubbleValues, count: Int): Int {
        val size = values.iconSizeDp
        val padH = (size / 3).coerceAtLeast(6)
        val marginStart = 3
        val marginEnd0 = if (count > 1) values.gap12(count) else 3
        val marginEnd1 = if (count > 2) values.gap23(count) else 3
        val marginEnd2 = if (count > 2) 3 else 0
        return count * size + marginStart + marginEnd0 + marginEnd1 + marginEnd2 + padH * 2
    }

    /** 复制气泡在场时截图气泡水平让位（优先右移，右缘放不下则左移）；不在场时用设置位置。 */
    private fun snapshotAvoidX(ctx: Context, count: Int, values: BubbleValues): Int {
        val baseX = values.xOffset(count)
        val bubble = bubbleView ?: return baseX
        val density = Resources.getSystem().displayMetrics.density
        val snapshotW = dp(snapshotWidthDp(values))
        val textW = dp(textBubbleWidthDp(values, (bubble as? android.view.ViewGroup)?.childCount?.coerceAtLeast(1) ?: 1))
        val offsetPx = textW / 2 + snapshotW / 2 + dp(14)
        val baseXPx = dp(baseX)
        val screenW = Resources.getSystem().displayMetrics.widthPixels
        val offsetDp = (offsetPx / density).toInt()
        val rightFits = baseXPx + offsetPx + snapshotW / 2 <= screenW / 2
        if (rightFits) return baseX + offsetDp
        val leftFits = baseXPx - offsetPx - snapshotW / 2 >= -screenW / 2
        return if (leftFits) baseX - offsetDp else baseX + offsetDp
    }

    /** 复制气泡出现/消失时，让截图气泡跟随让位或恢复原位。 */
    private fun relocateSnapshot() {
        val v = snapshotView ?: return
        val ctx = systemContext() ?: return
        val params = snapshotParams ?: return
        val values = snapshotStyle ?: return
        val count = snapshotUris.size.coerceIn(1, 3)
        params.x = dp(snapshotAvoidX(ctx, count, values))
        try {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, params)
        } catch (_: Throwable) {
        }
    }

    private fun systemContext(): Context? = SystemContextHolder.context()

    private fun resolveTargets(
        ctx: Context,
        type: ContentClassifier.ContentType,
        query: String,
        customApps: List<String>,
        deepLink: Boolean
    ): List<AppTarget> {
        val key = ResolveCacheKey(type, cacheKey(type, query, customApps), customApps, deepLink)
        val now = System.currentTimeMillis()
        resolveCache[key]?.let { (t, list) ->
            if (now - t < RESOLVE_CACHE_TTL_MS) return list
        }
        resolveCache.remove(key)

        val result = resolveTargetsInner(ctx, type, query, customApps, deepLink)
        resolveCache[key] = now to result
        return result
    }

    private fun resolveTargetsInner(
        ctx: Context,
        type: ContentClassifier.ContentType,
        query: String,
        customApps: List<String>,
        deepLink: Boolean
    ): List<AppTarget> {
        return try {
            val pm = ctx.packageManager
            val intent = buildIntent(type, query)

            when (type) {
                ContentClassifier.ContentType.PLATFORM -> {
                    val t = buildCustomTargets(pm, customApps, type, query, openLauncher = true)
                    if (t.isNotEmpty()) return t
                }
                ContentClassifier.ContentType.PHONE -> {
                    if (customApps.isNotEmpty()) {
                        val t = buildCustomTargets(pm, customApps, type, query, openLauncher = true)
                        if (t.isNotEmpty()) return t
                    }
                    val t = buildCustomTargets(
                        pm, BubblePrefs.phoneApps(ctx), type, query, openLauncher = false
                    )
                    if (t.isNotEmpty()) return t
                }
                ContentClassifier.ContentType.URL -> {
                    val apps = if (customApps.isNotEmpty()) customApps else BubblePrefs.urlApps(ctx)
                    val t = buildCustomTargets(
                        pm, apps, type, query,
                        openLauncher = customApps.isNotEmpty() && !deepLink
                    )
                    if (t.isNotEmpty()) return t
                }
                ContentClassifier.ContentType.ADDRESS -> {
                    val t = buildCustomTargets(
                        pm, BubblePrefs.addrApps(ctx), type, query, openLauncher = false
                    )
                    if (t.isNotEmpty()) return t
                }
            }

            if (intent == null) return emptyList()
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .filter { it.activityInfo != null && it.activityInfo.packageName != null }
                .distinctBy { it.activityInfo.packageName }
                .take(MAX_TARGETS)
                .mapNotNull { info ->
                    val pkg = info.activityInfo.packageName
                    val meta = appMeta(pm, pkg) ?: return@mapNotNull null
                    AppTarget(pkg, meta.label, meta.icon, type, query)
                }
                .toList()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG resolveTargets failed: $t")
            emptyList()
        }
    }

    private fun cacheKey(type: ContentClassifier.ContentType, query: String, customApps: List<String>): String =
        query

    private fun appMeta(pm: PackageManager, pkg: String): AppMeta? {
        val now = System.currentTimeMillis()
        iconCache[pkg]?.let { (t, meta) ->
            if (now - t < ICON_CACHE_TTL_MS) return meta
        }
        val ai = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG app not found: $pkg: $t")
            null
        } ?: return null
        val meta = AppMeta(pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
        iconCache[pkg] = now to meta
        return meta
    }

    private fun buildCustomTargets(
        pm: PackageManager,
        apps: List<String>,
        type: ContentClassifier.ContentType,
        query: String,
        openLauncher: Boolean
    ): List<AppTarget> {
        val targets = mutableListOf<AppTarget>()
        for (pkg in apps.distinct().take(MAX_TARGETS)) {
            val meta = appMeta(pm, pkg) ?: continue
            targets.add(
                AppTarget(
                    pkg,
                    meta.label,
                    meta.icon,
                    type,
                    query,
                    openLauncher
                )
            )
        }
        return targets
    }

    private fun buildIntent(
        type: ContentClassifier.ContentType,
        query: String
    ): Intent? = when (type) {
        ContentClassifier.ContentType.URL -> Intent(Intent.ACTION_VIEW, Uri.parse(query))
        ContentClassifier.ContentType.ADDRESS ->
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
        ContentClassifier.ContentType.PHONE ->
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:" + query))
        ContentClassifier.ContentType.PLATFORM -> null
    }

    private fun showBubble(ctx: Context, targets: List<AppTarget>, values: BubbleValues) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val iconSize = values.iconSizeDp
        val count = targets.size
        val gap12 = values.gap12(count)
        val gap23 = values.gap23(count)
        val padH = (iconSize / 3).coerceAtLeast(6)
        val padV = (iconSize / 6).coerceAtLeast(3)
        val corner = (iconSize * 2 / 3).coerceAtLeast(10)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val a = values.bgAlpha * 255 / 100
                val rgb = if (values.bgLight) 0xFF else 0x3C
                setColor(Color.argb(a, rgb, rgb, rgb))
                if (values.bgBorder) {
                    setStroke(
                        dp(1),
                        if (values.bgLight) Color.argb(0x66, 0xFF, 0xFF, 0xFF) else Color.BLACK
                    )
                }
                cornerRadius = dp(corner).toFloat()
            }
        }

        for ((index, target) in targets.withIndex()) {
            val lp = LinearLayout.LayoutParams(dp(iconSize), dp(iconSize))
            lp.marginStart = if (index == 0) dp(3) else dp(0)
            lp.marginEnd = when (index) {
                0 -> if (count > 1) dp(gap12) else dp(3)
                1 -> if (count > 2) dp(gap23) else dp(3)
                else -> dp(3)
            }
            row.addView(ImageView(ctx).apply {
                setImageDrawable(
                    com.example.supermi.IconUtil.rounded(
                        target.icon, dp(iconSize), dp(iconSize / 4).toFloat(), ctx.resources
                    )
                )
                layoutParams = lp
                setOnClickListener {
                    dismiss()
                    launch(ctx, target)
                }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val n = targets.size.coerceIn(1, 3)
            x = dp(values.xOffset(n))
            y = dp(values.yOffset(n))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTitle("SuperMi QuickLaunch")
        }

        try {
            wm.addView(row, params)
            bubbleView = row
            // 复制气泡优先显示：若截图气泡在场，立即让它让位
            relocateSnapshot()
            val dr = Runnable { dismiss() }
            dismissRunnable = dr
            mainHandler.postDelayed(dr, values.dismissMs)
        } catch (t: Throwable) {
            DebugToast.log("addView failed", t)
            DebugToast.show(ctx, "气泡添加失败: ${t.message}")
        }
    }

    private fun launch(ctx: Context, target: AppTarget) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        if (!target.openLauncher) {
            val deepLink = buildIntent(target.type, target.query)
            if (deepLink != null) {
                if (DebugToast.isDebug()) {
                    XposedBridge.log("$TAG debug deep-link -> ${deepLink.data} to ${target.packageName}")
                }
                deepLink.setPackage(target.packageName)
                deepLink.addFlags(flags)
                try {
                    ctx.startActivity(deepLink)
                    return
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG deep link failed for ${target.packageName}: $t")
                }
            }
        }
        val launcher = ctx.packageManager.getLaunchIntentForPackage(target.packageName) ?: return
        launcher.addFlags(flags)
        try {
            ctx.startActivity(launcher)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG launcher failed for ${target.packageName}: $t")
        }
    }

    private fun dismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val wm = windowManager
        val v = bubbleView
        if (wm != null && v != null) {
            try {
                wm.removeView(v)
            } catch (_: Throwable) {
            }
        }
        windowManager = null
        bubbleView = null
        // 复制气泡消失后，截图气泡恢复原位
        relocateSnapshot()
    }

    private fun dp(value: Int): Int =
        (value * Resources.getSystem().displayMetrics.density).toInt()
}
