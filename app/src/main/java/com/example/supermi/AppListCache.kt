package com.example.supermi

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 应用列表缓存：元数据存 filesDir JSON，圆角图标存 cacheDir PNG，进程内存兜底；支持后台全量重建。 */
object AppListCache {

    private const val META_FILE = "applist_cache.json"
    private const val ICON_DIR = "app_icons"
    private const val ICON_SIZE_DP = 40

    data class CachedApp(val label: String, val pkg: String, val isSystem: Boolean)

    @Volatile
    private var memory: List<CachedApp>? = null

    @Volatile
    private var refreshing = false

    fun cached(ctx: Context): List<CachedApp>? {
        memory?.let { return it }
        return try {
            val meta = File(ctx.filesDir, META_FILE)
            if (!meta.exists()) return null
            val arr = JSONArray(meta.readText())
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                CachedApp(o.getString("label"), o.getString("pkg"), o.optBoolean("system"))
            }
            if (list.isEmpty()) null else list.also { memory = it }
        } catch (_: Throwable) {
            null
        }
    }

    fun isRefreshing(): Boolean = refreshing

    /** 后台重建应用列表；已在刷新则跳过。 */
    fun refreshAsync(ctx: Context, onDone: () -> Unit = {}) {
        if (refreshing) {
            onDone()
            return
        }
        refreshing = true
        Thread {
            try {
                rebuild(ctx)
            } catch (_: Throwable) {
            } finally {
                refreshing = false
            }
            onDone()
        }.start()
    }

    /** 全量查询 PackageManager，重建元数据并保存图标，返回列表。 */
    fun rebuild(ctx: Context): List<CachedApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = try {
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo?.packageName }
                .distinct()
                .mapNotNull { pkg ->
                    val ai: ApplicationInfo = try {
                        pm.getApplicationInfo(pkg, 0)
                    } catch (_: Throwable) {
                        return@mapNotNull null
                    }
                    Triple(ai, pm.getApplicationLabel(ai).toString(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
                .sortedBy { it.second }
                .mapNotNull { (ai, label, isSys) ->
                    val bmp = roundIcon(ctx.resources, pm.getApplicationIcon(ai))
                    saveIcon(ctx, ai.packageName, bmp)
                    CachedApp(label, ai.packageName, isSys)
                }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
        save(ctx, list)
        return list
    }

    fun save(ctx: Context, apps: List<CachedApp>) {
        try {
            memory = apps
            val arr = JSONArray()
            for (a in apps) {
                arr.put(JSONObject().apply {
                    put("label", a.label)
                    put("pkg", a.pkg)
                    put("system", a.isSystem)
                })
            }
            File(ctx.filesDir, META_FILE).writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    fun roundIcon(resources: Resources, d: Drawable): Bitmap {
        val sizePx = (ICON_SIZE_DP * resources.displayMetrics.density).toInt()
        val dd = IconUtil.rounded(d, sizePx, (sizePx / 4).toFloat(), resources)
        return (dd as? BitmapDrawable)?.bitmap ?: runCatching {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, sizePx, sizePx)
            d.draw(c)
            bmp
        }.getOrNull() ?: Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private fun iconFile(ctx: Context, pkg: String): File {
        return File(ctx.cacheDir, "$ICON_DIR/${pkg.hashCode().toString(16)}.png")
    }

    fun saveIcon(ctx: Context, pkg: String, bmp: Bitmap) {
        try {
            val f = iconFile(ctx, pkg)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (_: Throwable) {
        }
    }

    fun loadIcon(ctx: Context, pkg: String): Drawable? {
        return try {
            val f = iconFile(ctx, pkg)
            if (!f.exists()) return null
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            BitmapDrawable(ctx.resources, bmp)
        } catch (_: Throwable) {
            null
        }
    }

    fun invalidate(ctx: Context) {
        memory = null
        try {
            File(ctx.filesDir, META_FILE).delete()
        } catch (_: Throwable) {
        }
        try {
            File(ctx.cacheDir, ICON_DIR).deleteRecursively()
        } catch (_: Throwable) {
        }
    }
}