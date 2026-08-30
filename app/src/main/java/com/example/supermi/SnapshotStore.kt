package com.example.supermi

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.supermi.xposed.BubblePrefs
import com.example.supermi.xposed.DebugLogStore
import java.io.File
import java.io.IOException
import java.util.UUID

object SnapshotStore {
    private const val DIR = "supermi_snapshots"
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val ORIG_SUFFIX = ".orig"
    const val EXTRA_ORIG_PATH = "snapshot_orig_path"
    const val EXTRA_ORIG_URI = "snapshot_orig_uri"
    const val EXTRA_TAKEN_MS = "snapshot_taken_ms"
    const val EXTRA_ORIG_TAKEN_MS = "snapshot_orig_taken_ms"
    const val EXTRA_ORIG_NAME = "snapshot_orig_name"
    /** Android 16 某些 ROM 上 system_server 读取 FileProvider URI 的 grant 不稳定，作为受保护的本地路径兜底。 */
    const val EXTRA_CACHE_PATH = "snapshot_cache_path"

    data class Saved(
        val id: String,
        val file: File,
        val mime: String,
        val origPath: String? = null,
        val takenMs: Long? = null
    )

    /** `.orig` 伴生文件内容：相册原图位置 + 可选的保存副本/本次保留标记。 */
    data class OrigInfo(
        val path: String,
        val uri: String,
        val savedPath: String? = null,
        val savedUri: String? = null,
        val takenMs: Long? = null,
        val name: String? = null,
        val keepOnClose: Boolean = false
    )

    fun save(context: Context, uri: Uri, mime: String?, maxCount: Int = 1, deleteOriginal: Boolean = true): Saved? {
        val dir = File(context.noBackupFilesDir, DIR).apply { mkdirs() }
        // App 内缓存始终只保留最新 maxCount 张；deleteOriginal 只控制是否同步删除相册原图
        cleanup(context, dir, maxCount.coerceIn(1, 3), deleteOriginal)
        val id = "snap_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val ext = when {
            mime?.contains("png") == true -> "png"
            mime?.contains("webp") == true -> "webp"
            mime?.contains("heic") == true -> "heic"
            else -> "jpg"
        }
        val file = File(dir, "$id.$ext")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (file.length() == 0L || file.length() > MAX_BYTES) {
                file.delete()
                return null
            }
            // 记录相册原图的位置，供“自动删除原图”使用
            val origPath = resolveOriginalPath(context, uri)
            diag(context, "原图反查: uri=$uri -> ${origPath ?: "未解析到原图路径"}")
            val takenMs = resolveCaptureTime(context, uri, origPath)
            diag(context, "截图时间: ${takenMs ?: "未解析到截图时间"}")
            if (origPath != null) {
            writeOrigInfo(file, origPath, uri.toString(), takenMs, origPath?.substringAfterLast('/'))
            }
            // 保存后再清理一次：让磁盘与气泡同步，只保留最新 maxCount 张
            cleanup(context, dir, maxCount.coerceIn(1, 3), deleteOriginal)
            Saved(id, file, mime ?: "image/*", origPath, takenMs)
        } catch (_: Throwable) {
            file.delete()
            null
        }
    }

    fun cleanup(context: Context, dir: File, keep: Int = 3, deleteOriginal: Boolean = true) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(ORIG_SUFFIX) } ?: return
        for (f in files.sortedByDescending { it.lastModified() }.drop(keep.coerceAtLeast(0))) {
            deleteFileWithOriginal(context, f, deleteOriginal)
        }
    }

    /** 清空全部暂存截图：缓存全删，相册原图按“自动删除相册原图”开关处理。 */
    fun deleteAll(context: Context) {
        val dir = File(context.noBackupFilesDir, DIR)
        cleanup(context, dir, 0, BubblePrefs.snapshotAutoClean(context))
    }

    /**
     * 删除一条截图记录对应的文件。
     * 传入的 uri 可能是 FileProvider 的 content:// 或 file://，
     * 统一按文件名映射回截图目录后删除，避免直接依赖 content uri 的路径。
     */
    fun delete(context: Context, uri: Uri): Boolean {
        return try {
            val target = resolveCacheFile(context, uri)
            if (target == null) {
                diag(context, "删除请求被拒绝: uri=$uri 不在缓存目录内")
                return false
            }
            val autoClean = BubblePrefs.snapshotAutoClean(context)
            diag(context, "删除请求: uri=$uri target=${target.canonicalPath} 自动删原图=$autoClean")
            deleteFileWithOriginal(context, target, autoClean)
            true
        } catch (t: Throwable) {
            diag(context, "删除异常: $t")
            false
        }
    }

    /** 各机型常见截图目录；未手动配置时按此顺序自动检测。 */
    fun autoDetectScreenshotDir(context: Context): File? {
        val root = try {
            Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        } catch (_: Throwable) {
            "/storage/emulated/0"
        }
        val candidates = listOf(
            "$root/Pictures/Screenshots",
            "$root/DCIM/Screenshots",
            "$root/Pictures/截屏",
            "$root/DCIM/截屏"
        )
        val hit = candidates.firstOrNull { File(it).isDirectory }
        return if (hit != null) File(hit) else candidates.firstOrNull()?.let(::File)
    }

    /** 删除原图时使用的截屏目录：手动配置优先，未配置则自动检测。 */
    private fun screenshotDirForDelete(context: Context): File? {
        val manual = BubblePrefs.snapshotDirFresh(context).trim().trimEnd('/')
        if (manual.isNotEmpty()) {
            val f = File(manual)
            if (f.isAbsolute) return f
            diag(context, "删原图: 手动目录不是绝对路径，忽略 path=$manual")
        }
        return autoDetectScreenshotDir(context)
    }

    /** 把相对文件名对齐到截屏目录内，MIUI 只给裸文件名时也能定位。 */
    private fun resolveOriginalForDelete(context: Context, orig: OrigInfo): File? {
        val base = screenshotDirForDelete(context) ?: return null
        val f = File(orig.path)
        return if (f.isAbsolute) f else File(base, orig.path)
    }

    private fun shouldDeleteOriginal(context: Context, orig: OrigInfo, target: File?): Boolean {
        if (target == null) {
            diag(context, "跳过删原图: 无法定位截屏目录内文件 name=${orig.name ?: orig.path}")
            return false
        }
        val base = screenshotDirForDelete(context) ?: run {
            diag(context, "跳过删原图: 未找到系统截屏目录 path=${orig.path}")
            return false
        }
        val basePath = try {
            base.canonicalPath
        } catch (_: Throwable) {
            base.absolutePath
        }
        val filePath = try {
            target.canonicalPath
        } catch (_: Throwable) {
            target.absolutePath
        }
        if (filePath != basePath && !filePath.startsWith(basePath + File.separator)) {
            diag(context, "跳过删原图: 不在截屏目录内 path=$filePath dir=$basePath")
            return false
        }
        val takenMs = orig.takenMs ?: target.lastModified().takeIf { it > 0L } ?: 0L
        val ageMs = System.currentTimeMillis() - takenMs
        if (takenMs <= 0L || ageMs > BubblePrefs.SNAPSHOT_RECENT_MS) {
            diag(context, "跳过删原图: 非最近截图 takenMs=$takenMs ageMs=$ageMs path=$filePath")
            return false
        }
        diag(context, "允许删原图: path=$filePath takenMs=$takenMs 距今=${ageMs / 1000}秒")
        return true
    }

    private fun deleteFileWithOriginal(context: Context, file: File, deleteOriginal: Boolean) {
        val orig = readOrigInfo(file)
        val keep = orig?.keepOnClose == true
        diag(
            context,
            "删缓存 ${file.name}: 自动删原图=$deleteOriginal keepOnClose=$keep, 原图记录=" +
                (orig?.let { "path=${it.path} uri=${it.uri} saved=${it.savedPath ?: "无"}" } ?: "无")
        )
        var origToNotify: OrigInfo? = null
        if (deleteOriginal && !keep) {
            try {
                orig?.let { info ->
                    val target = resolveOriginalForDelete(context, info)
                    if (shouldDeleteOriginal(context, info, target)) {
                        val effective = if (target != null && !File(info.path).isAbsolute) {
                            info.copy(path = target.absolutePath)
                        } else {
                            info
                        }
                        origToNotify = effective
                        deleteOriginalFile(context, effective.path, effective.uri)
                    }
                }
            } catch (t: Throwable) {
                diag(context, "删原图异常: $t")
            }
        }
        File(file.parentFile, file.name + ORIG_SUFFIX).delete()
        file.delete()
        diag(context, "删缓存完成: ${file.name} 剩余存在=${file.exists()}")
        // 通知 system_server：气泡移除该图；若开了自动删原图且能反查到原图，则由系统权限代删相册原图
        val cacheUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
        } catch (_: Throwable) {
            null
        }
        notifySystemDelete(context, cacheUri, origToNotify)
    }

    private fun notifySystemDelete(context: Context, cacheUri: String?, orig: OrigInfo?) {
        if (cacheUri == null && orig == null) return
        try {
            val intent = Intent("com.example.supermi.DELETE_SNAPSHOT").apply {
                setPackage("android")
                cacheUri?.let { putExtra("snapshot_uri", it) }
                if (orig != null) {
                    putExtra(EXTRA_ORIG_PATH, orig.path)
                    putExtra(EXTRA_ORIG_URI, orig.uri)
                    putExtra(EXTRA_ORIG_TAKEN_MS, orig.takenMs ?: 0L)
                    putExtra(EXTRA_ORIG_NAME, orig.name ?: "")
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.sendBroadcast(intent, "com.example.supermi.permission.SHOW_SNAPSHOT")
            diag(context, "已通知 system_server 删除: cache=${cacheUri ?: "无"} orig=${orig?.path ?: "无"}")
        } catch (t: Throwable) {
            diag(context, "通知 system_server 删除失败: $t")
        }
    }

    /** 从分享 URI 反查相册原图路径：media 项查 _data，失败用 RELATIVE_PATH+DISPLAY_NAME 拼接，file:// 直接取路径。 */
    private fun resolveOriginalPath(context: Context, uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    val projection = arrayOf(
                        MediaStore.MediaColumns.DATA,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME
                    )
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@use null
                        val data = c.getString(0)
                        if (!data.isNullOrBlank()) {
                            data
                        } else {
                            val rel = c.getString(1) ?: ""
                            val name = c.getString(2) ?: ""
                            if (name.isBlank()) {
                                null
                            } else {
                                buildString {
                                    append(Environment.getExternalStorageDirectory().absolutePath)
                                    append("/")
                                    if (rel.isNotBlank()) {
                                        append(rel.trimStart('/'))
                                        if (!rel.endsWith("/")) append("/")
                                    }
                                    append(name)
                                }
                            }
                        }
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeOrigInfo(
        file: File,
        path: String,
        mediaUri: String,
        takenMs: Long?,
        name: String? = null
    ) {
        try {
            val old = readOrigInfo(file)
            val lines = mutableListOf("path=$path", "uri=$mediaUri")
            takenMs?.let { lines += "taken_ms=$it" }
            name?.takeIf { it.isNotBlank() }?.let { lines += "name=$it" }
            old?.savedPath?.takeIf { it.isNotBlank() }?.let { lines += "saved_path=$it" }
            old?.savedUri?.takeIf { it.isNotBlank() }?.let { lines += "saved_uri=$it" }
            if (old?.keepOnClose == true) lines += "keep_on_close=true"
            old?.name?.takeIf { it.isNotBlank() && name.isNullOrBlank() }?.let { lines += "name=$it" }
            File(file.parentFile, file.name + ORIG_SUFFIX).writeText(lines.joinToString("\n") + "\n")
        } catch (_: Throwable) {
        }
    }

    private fun readOrigInfo(file: File): OrigInfo? {
        return try {
            val side = File(file.parentFile, file.name + ORIG_SUFFIX)
            if (!side.exists()) return null
            var path: String? = null
            var uri: String? = null
            var savedPath: String? = null
            var savedUri: String? = null
            var takenMs: Long? = null
            var name: String? = null
            var keepOnClose = false
            for (line in side.readLines()) {
                val i = line.indexOf('=')
                if (i <= 0) continue
                when (line.substring(0, i)) {
                    "path" -> path = line.substring(i + 1)
                    "uri" -> uri = line.substring(i + 1)
                    "saved_path" -> savedPath = line.substring(i + 1)
                    "saved_uri" -> savedUri = line.substring(i + 1)
                    "taken_ms" -> takenMs = line.substring(i + 1).toLongOrNull()
                    "name" -> name = line.substring(i + 1)
                    "keep_on_close" -> keepOnClose = line.substring(i + 1).equals("true", ignoreCase = true)
                }
            }
            if (path.isNullOrBlank()) {
                null
            } else {
                OrigInfo(path, uri ?: "", savedPath, savedUri, takenMs, name, keepOnClose)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 保存“保存到相册”副本的位置到伴生文件（保留原图记录）。 */
    private fun writeSavedInfo(file: File, savedPath: String, savedUri: String) {
        try {
            val old = readOrigInfo(file)
            val lines = mutableListOf<String>()
            old?.path?.let { lines += "path=$it" }
            old?.uri?.let { lines += "uri=$it" }
            old?.takenMs?.let { lines += "taken_ms=$it" }
            old?.name?.takeIf { it.isNotBlank() }?.let { lines += "name=$it" }
            if (old?.keepOnClose == true) lines += "keep_on_close=true"
            lines += "saved_path=$savedPath"
            lines += "saved_uri=$savedUri"
            File(file.parentFile, file.name + ORIG_SUFFIX).writeText(lines.joinToString("\n") + "\n")
        } catch (_: Throwable) {
        }
    }

    /** 移除副本记录（用于“删除相册图片”后），保留原图记录。 */
    private fun clearSavedInfo(file: File) {
        try {
            val old = readOrigInfo(file) ?: return
            val lines = buildString {
                append("path=${old.path}\n")
                append("uri=${old.uri}\n")
                old.takenMs?.let { append("taken_ms=$it\n") }
                old.name?.takeIf { it.isNotBlank() }?.let { append("name=$it\n") }
            }
            File(file.parentFile, file.name + ORIG_SUFFIX).writeText(lines)
        } catch (_: Throwable) {
        }
    }

    /** 反查截图拍摄时间：优先 MediaStore 的 DATE_TAKEN/DATE_ADDED，失败再从系统截图文件名解析。 */
    private fun resolveCaptureTime(context: Context, uri: Uri, origPath: String?): Long? {
        return resolveMediaDate(context, uri) ?: parseScreenshotName(origPath ?: uri.lastPathSegment)
    }

    private fun resolveMediaDate(context: Context, uri: Uri): Long? {
        return try {
            if (uri.scheme != "content") return null
            val projection = arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val taken = c.getLong(0)
                if (taken > 0L) taken else c.getLong(1).takeIf { it > 0L }?.times(1000L)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 兼容常见的系统截图文件名，例如 Screenshot_2026-08-30-09-16-51-53_hash.jpg。 */
    private fun parseScreenshotName(s: String?): Long? {
        val name = s?.substringAfterLast('/') ?: return null
        val dash = Regex("""Screenshot_(\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-?(\d{2,3})?_""")
            .find(name)
        val compact = Regex("""Screenshot_(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})-?(\d{3})?_""")
            .find(name)
        val m = dash?.groupValues ?: compact?.groupValues ?: return null
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.clear()
            cal.set(
                m[1].toInt(),
                m[2].toInt() - 1,
                m[3].toInt(),
                m[4].toInt(),
                m[5].toInt(),
                m[6].toInt()
            )
            val frac = m.getOrNull(7).orEmpty()
            cal.set(java.util.Calendar.MILLISECOND, when {
                frac.length == 3 -> frac.toInt()
                frac.length == 2 -> frac.toInt() * 10
                else -> 0
            })
            cal.timeInMillis
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 把 FileProvider/file URI 安全映射回截图目录中的缓存文件；不在目录内返回 null。
     */
    private fun resolveCacheFile(context: Context, uri: Uri): File? {
        return try {
            val dir = File(context.noBackupFilesDir, DIR)
            val canonicalDir = dir.canonicalPath
            val name = uri.lastPathSegment?.let { File(it).name } ?: return null
            val target = File(dir, name)
            val canonicalTarget = target.canonicalPath
            if (canonicalTarget.startsWith(canonicalDir + File.separator)) target else null
        } catch (_: Throwable) {
            null
        }
    }

    /** 写入“本次关闭不删相册原图”标记，保留原有原图/副本记录。 */
    private fun writeKeepOnClose(file: File): Boolean {
        return try {
            val old = readOrigInfo(file) ?: return false
            val lines = buildString {
                append("path=${old.path}\n")
                append("uri=${old.uri}\n")
                old.takenMs?.let { append("taken_ms=$it\n") }
                old.name?.takeIf { it.isNotBlank() }?.let { append("name=$it\n") }
                old.savedPath?.takeIf { it.isNotBlank() }?.let { append("saved_path=$it\n") }
                old.savedUri?.takeIf { it.isNotBlank() }?.let { append("saved_uri=$it\n") }
                append("keep_on_close=true\n")
            }
            File(file.parentFile, file.name + ORIG_SUFFIX).writeText(lines)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 开启“自动删除相册原图”时，让这张截图在本次关闭查看框时被保留。
     * 有原图记录时只写 keep_on_close 标记，不再生成 SuperMi_ 副本；只有无法反查到原图时才退回 MediaStore 重建副本。
     */
    fun saveToGallery(context: Context, snapshotUri: Uri): String {
        return try {
            val file = resolveCacheFile(context, snapshotUri)
            if (file == null || !file.exists()) {
                diag(context, "保存到相册: 缓存不存在 uri=$snapshotUri")
                return "截图缓存不存在"
            }
            val info = readOrigInfo(file)
            if (info != null) {
                if (info.keepOnClose) {
                    diag(context, "保存到相册: 已标记本次保留 cache=${file.name}")
                    return "已在本次关闭时保留原图"
                }
                if (writeKeepOnClose(file)) {
                    diag(context, "保存到相册(本次保留): cache=${file.name} orig=${info.path}")
                    return "已保留，本次关闭不删除原图"
                }
                diag(context, "保存到相册: keep 标记写入失败 cache=${file.name}")
                return "保留失败"
            }
            // 无原图记录：没有可保留的相册原图，退回旧逻辑重建副本
            val insertUri = insertToGallery(context, file) ?: return "保存失败"
            val savedPath = resolveOriginalPath(context, insertUri) ?: insertUri.toString()
            writeSavedInfo(file, savedPath, insertUri.toString())
            diag(context, "保存到相册完成: cache=${file.name} saved=$savedPath uri=$insertUri")
            "已保存到相册"
        } catch (t: Throwable) {
            diag(context, "保存到相册异常: $t")
            "保存失败"
        }
    }

    private fun insertToGallery(context: Context, file: File): Uri? {
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SuperMi_${file.name}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/Screenshots"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val insertUri = try {
            context.contentResolver.insert(collection, values)
        } catch (t: Throwable) {
            diag(context, "保存到相册 insert 异常: $t")
            null
        } ?: run {
            diag(context, "保存到相册: MediaStore 插入返回 null")
            return null
        }
        return try {
            context.contentResolver.openOutputStream(insertUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("openOutputStream null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(insertUri, values, null, null)
            insertUri
        } catch (t: Throwable) {
            try {
                context.contentResolver.delete(insertUri, null, null)
            } catch (_: Throwable) {
            }
            diag(context, "保存到相册写入失败: $t")
            null
        }
    }

    /**
     * 只删除相册里的图片（原图 + 曾“保存到相册”的副本），保留 app 内缓存和气泡。
     * 供“关闭自动删除”时临时改变主意使用。
     */
    fun deleteGalleryOnly(context: Context, snapshotUri: Uri): String {
        return try {
            val file = resolveCacheFile(context, snapshotUri)
            if (file == null || !file.exists()) {
                diag(context, "删除相册图片: 缓存不存在 uri=$snapshotUri")
                return "截图缓存不存在"
            }
            val info = readOrigInfo(file)
            if (info == null) {
                diag(context, "删除相册图片: 无原图记录 uri=$snapshotUri")
                return "没有可删除的相册图片"
            }
            var targets = 0
            if (info.path.isNotBlank()) {
                targets++
                deleteOriginalFile(context, info.path, info.uri)
                notifySystemDeleteOriginal(context, info.path, info.uri, info.takenMs, info.name)
            }
            if (!info.savedPath.isNullOrBlank() && info.savedPath != info.path) {
                targets++
                deleteOriginalFile(context, info.savedPath, info.savedUri ?: "")
                notifySystemDeleteOriginal(context, info.savedPath, info.savedUri ?: "", info.takenMs, info.name)
                clearSavedInfo(file)
            }
            diag(context, "删除相册图片完成: cache=${file.name} 目标数=$targets")
            "已删除相册图片"
        } catch (t: Throwable) {
            diag(context, "删除相册图片异常: $t")
            "删除失败"
        }
    }

    /** 通知 system_server 只删相册原图（不带 snapshot_uri，气泡与缓存不受影响）。 */
    private fun notifySystemDeleteOriginal(
        context: Context,
        path: String,
        mediaUri: String,
        takenMs: Long?,
        name: String?
    ) {
        try {
            val intent = Intent("com.example.supermi.DELETE_SNAPSHOT").apply {
                setPackage("android")
                putExtra(EXTRA_ORIG_PATH, path)
                if (mediaUri.isNotBlank()) putExtra(EXTRA_ORIG_URI, mediaUri)
                putExtra(EXTRA_ORIG_TAKEN_MS, takenMs ?: 0L)
                putExtra(EXTRA_ORIG_NAME, name ?: "")
            }
            context.sendBroadcast(intent, "com.example.supermi.permission.SHOW_SNAPSHOT")
            diag(context, "已通知 system_server 仅删相册原图: path=$path uri=$mediaUri takenMs=$takenMs name=$name")
        } catch (t: Throwable) {
            diag(context, "通知 system_server 仅删原图失败: $t")
        }
    }

    /** 删除相册原图并刷新媒体库：优先直接删，失败走 root(su rm + content delete)，最后扫描兜底。 */
    private fun deleteOriginalFile(context: Context, path: String, mediaUri: String) {
        var stillExists = false
        val f = File(path)
        if (!f.isAbsolute) {
            // MIUI FileProvider 只给了相对文件名，直接删可能删错位置，交 system 按 MediaStore 反查
            diag(context, "删原图: 相对路径跳过文件删除，交给 system 反查相册 path=$path")
        } else {
            try {
                if (f.exists()) {
                    val direct = f.delete()
                    stillExists = !direct
                    diag(context, "删原图 File.delete=$direct path=$path")
                } else {
                    diag(context, "删原图: 文件已不存在 path=$path")
                }
            } catch (t: Throwable) {
                stillExists = true
                diag(context, "删原图 File.delete 异常: $t")
            }
            if (stillExists) {
                val rootOk = runRoot(context, "rm -f -- ${shellQuote(path)}")
                diag(context, "删原图 root rm=$rootOk path=$path")
            }
        }
        if (mediaUri.isNotBlank()) {
            var removed = false
            try {
                val count = context.contentResolver.delete(Uri.parse(mediaUri), null, null)
                removed = count > 0
                diag(context, "删原图 media delete count=$count uri=$mediaUri")
            } catch (t: Throwable) {
                diag(context, "删原图 media delete 异常: $t")
            }
            if (!removed) {
                val rootOk = runRoot(context, "content delete --uri ${shellQuote(mediaUri)}")
                diag(context, "删原图 root content delete=$rootOk uri=$mediaUri")
            }
        }
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            diag(context, "删原图完成: path=$path 最终存在=${File(path).exists()} uri=$mediaUri")
        } catch (t: Throwable) {
            diag(context, "删原图 scan 异常: $t")
        }
    }

    private fun diag(context: Context, message: String) {
        Log.d("SuperMi", message)
        if (AppConfig.read(context)["debug"] == "1") {
            DebugLogStore.append(context, message)
        }
    }

    private fun runRoot(context: Context, cmd: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val ok = p.waitFor() == 0
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        diag(context, "root 命令 exit=$ok out=${out.take(300)} cmd=$cmd")
        ok
    } catch (t: Throwable) {
        diag(context, "root 命令异常: $t cmd=$cmd")
        false
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
