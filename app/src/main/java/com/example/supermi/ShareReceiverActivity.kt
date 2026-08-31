package com.example.supermi

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import com.example.supermi.xposed.DebugLogStore
import com.example.supermi.xposed.BubblePrefs

class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        diag(
            "收到分享: action=${intent.action}, type=${intent.type}, " +
                "caller=${callingInfo(intent)}, extras=${describeExtras(intent.extras)}, " +
                "clip=${clipInfo(intent)}, component=${intent.component?.flattenToString() ?: "无"}"
        )
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            ?: intent.clipData?.getItemAt(0)?.uri
        if (uri != null) {
            diag(
                "分享URI: scheme=${uri.scheme}, authority=${uri.authority}, " +
                    "path=${uri.path}, segments=${uri.pathSegments}, uri=$uri"
            )
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            val mime = intent.type ?: contentResolver.getType(uri) ?: "image/*"
            Thread {
                val saved = SnapshotStore.save(
                    applicationContext, uri, mime,
                    BubblePosProvider.snapshotMaxCount,
                    BubblePosProvider.snapshotAutoClean
                )
                if (saved != null) {
                    val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    val cacheUri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", saved.file
                    )
                    try {
                        grantUriPermission("android", cacheUri, grant)
                        diag("已授予 system_server 读取缓存 URI: $cacheUri")
                    } catch (t: Throwable) {
                        // Android 16/部分 ROM 可能拒绝按 package 授权；事件中同时带路径兜底。
                        diag("授予 system_server 缓存 URI 失败: $t uri=$cacheUri")
                    }
                    val event = Intent(ACTION_SHOW_SNAPSHOT).apply {
                        setPackage("android")
                        putExtra(EXTRA_ID, saved.id)
                        putExtra(EXTRA_URI, cacheUri.toString())
                        putExtra(EXTRA_MIME, saved.mime)
                        putExtra(SnapshotStore.EXTRA_CACHE_PATH, saved.file.absolutePath)
                        putExtra(SnapshotStore.EXTRA_ORIG_PATH, saved.origPath.orEmpty())
                        putExtra(
                            SnapshotStore.EXTRA_ORIG_NAME,
                            saved.origPath?.substringAfterLast('/')
                                ?.takeIf { it.isNotBlank() }
                                ?: uri.lastPathSegment.orEmpty()
                        )
                        putExtra(SnapshotStore.EXTRA_TAKEN_MS, saved.takenMs ?: 0L)
                        // 同时把 URI 放入 ClipData，确保 Android 16 将 grant 传递给动态 receiver。
                        clipData = ClipData.newRawUri("snapshot", cacheUri)
                        addFlags(grant)
                    }
                    try {
                        sendBroadcast(event, PERMISSION_SHOW_SNAPSHOT)
                        diag("截图已保存并发送事件: ${saved.file} (${saved.file.length()} bytes)")
                    } catch (t: Throwable) { Log.e("SuperMi", "snapshot event failed", t) }
                } else runOnUiThread { Toast.makeText(this, "截图读取失败", Toast.LENGTH_SHORT).show() }
                runOnUiThread { finish() }
            }.start()
        } else { diag("分享中没有找到图片 URI"); finish() }
    }

    private fun diag(message: String) {
        Log.d("SuperMi", message)
        if (AppConfig.read(this)["debug"] == "1") {
            DebugLogStore.append(this, message)
            if (BubblePrefs.debugToastEnabled(this)) runOnUiThread {
                Toast.makeText(this, message.take(180), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun describeExtras(extras: Bundle?): String {
        if (extras == null) return "无"
        return extras.keySet().sorted().joinToString(" | ") { key ->
            val raw = try {
                extras.get(key)
            } catch (_: Throwable) {
                null
            }
            "$key=${raw ?: "null"}"
        }
    }

    private fun callingInfo(intent: Intent): String {
        val names = listOf(
            "android.support.v4.app.EXTRA_CALLING_PACKAGE",
            "androidx.core.app.EXTRA_CALLING_PACKAGE",
            "android.support.v4.app.EXTRA_CALLING_ACTIVITY",
            "androidx.core.app.EXTRA_CALLING_ACTIVITY",
            "sourceFrom"
        )
        return names.mapNotNull { key ->
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { "$key=$it" }
        }.joinToString(", ").ifBlank { "无" }
    }

    private fun clipInfo(intent: Intent): String {
        val clip = intent.clipData ?: return "无"
        val desc = clip.description
        val label = desc.label?.toString().orEmpty()
        val mimes = (0 until desc.mimeTypeCount).joinToString(",") { i ->
            desc.getMimeType(i) ?: "null"
        }
        val uris = (0 until clip.itemCount).joinToString(";") { i ->
            clip.getItemAt(i).uri?.toString() ?: "null"
        }
        return "label=$label, mimes=$mimes, uris=$uris"
    }

    companion object {
        const val ACTION_SHOW_SNAPSHOT = "com.example.supermi.SHOW_SNAPSHOT"
        const val EXTRA_ID = "snapshot_id"
        const val EXTRA_URI = "snapshot_uri"
        const val EXTRA_MIME = "snapshot_mime"
        const val EXTRA_PERMISSION = "snapshot_permission"
        const val PERMISSION_SHOW_SNAPSHOT = "com.example.supermi.permission.SHOW_SNAPSHOT"
    }
}
