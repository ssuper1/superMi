package com.example.supermi.xposed

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import de.robv.android.xposed.XposedBridge

object OverlayBubble {

    private const val TAG = "SuperMi"
    private const val SHOW_DURATION_MS = 6000L
    private const val DEDUP_WINDOW_MS = 3000L
    private const val MAX_TARGETS = 3
    private const val RESOLVE_CACHE_SIZE = 16
    private const val RESOLVE_CACHE_TTL_MS = 30_000L

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
                mainHandler.post {
                    dismiss()
                    showBubble(ctx, finalTargets)
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG show failed: $t")
            }
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
        when {
            type == ContentClassifier.ContentType.URL && customApps.isEmpty() ->
                Uri.parse(query).host ?: query
            else -> ""
        }

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

    private fun showBubble(ctx: Context, targets: List<AppTarget>) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val iconSize = BubblePrefs.iconSizeDp(ctx)
        val gap = (iconSize / 8).coerceAtLeast(2)
        val padH = (iconSize / 3).coerceAtLeast(6)
        val padV = (iconSize / 6).coerceAtLeast(3)
        val corner = (iconSize * 2 / 3).coerceAtLeast(10)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#D93C3C3C"))
                cornerRadius = dp(corner).toFloat()
            }
        }

        for (target in targets) {
            val lp = LinearLayout.LayoutParams(dp(iconSize), dp(iconSize))
            lp.marginStart = dp(gap)
            lp.marginEnd = dp(gap)
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
            x = dp(BubblePrefs.xOffsetDp(ctx))
            y = dp(BubblePrefs.topOffsetDp(ctx))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTitle("SuperMi QuickLaunch")
        }

        try {
            wm.addView(row, params)
            bubbleView = row
            val dr = Runnable { dismiss() }
            dismissRunnable = dr
            mainHandler.postDelayed(dr, SHOW_DURATION_MS)
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
    }

    private fun dp(value: Int): Int =
        (value * Resources.getSystem().displayMetrics.density).toInt()
}
