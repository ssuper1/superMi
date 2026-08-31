package com.example.supermi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.supermi.xposed.BubblePrefs
import java.util.LinkedHashMap
import java.util.Random

/**
 * 截图多图查看框：从气泡位置放大入场/收回气泡退场，
 * 底部操作栏可点图隐藏，上滑返回，左右滑动切换（不循环）。
 */
class SnapshotViewerActivity : ComponentActivity() {

    private val uris = mutableListOf<Uri>()
    private val sources = mutableListOf<String>()
    private val sourcePackages = mutableListOf<String>()
    private var index = 0
    @Volatile
    private var sideActionBusy = false
    private var loadSeq = 0
    private var sourceOpenPendingClose = false
    private var sourceOpenStopped = false
    private val bitmapCache =
        object : LinkedHashMap<Uri, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, Bitmap>): Boolean =
                size > 3
        }

    private var originX = -1
    private var originY = -1
    private var originW = 0
    private var originH = 0
    private var hasOrigin = false
    private var openingStarted = false
    private var exiting = false
    private var barVisible = true
    private var blurBackground = false
    private var blurRadiusPx = 0
    private var restoreBubbleSent = false
    private var blurBackgroundAttached = false
    private var backPressedCallback: OnBackPressedCallback? = null
    /** 当前入场/退场过渡；返回时必须取消入场动画，避免两个动画同时改写同一组属性。 */
    private var transitionAnimator: ValueAnimator? = null
    private lateinit var ambientBackground: AmbientBackgroundDrawable

    private lateinit var root: LinearLayout
    private lateinit var area: FrameLayout
    private lateinit var viewA: ZoomableImageView
    private lateinit var viewB: ZoomableImageView
    private lateinit var front: ZoomableImageView
    private lateinit var failText: TextView
    private lateinit var sideBtn: TextView
    private lateinit var closeBtn: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var bar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val list = intent.getStringArrayListExtra(EXTRA_URIS)
        if (!list.isNullOrEmpty()) {
            uris.addAll(list.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() })
        } else {
            intent.getStringExtra(EXTRA_URI)?.let { u ->
                runCatching { Uri.parse(u) }.getOrNull()?.let { uris.add(it) }
            }
        }
        intent.getStringArrayListExtra(EXTRA_SOURCES)?.let { sources.addAll(it) }
        intent.getStringArrayListExtra(EXTRA_SOURCE_PACKAGES)?.let { sourcePackages.addAll(it) }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
        if (uris.isEmpty()) {
            finish()
            return
        }

        originX = intent.getIntExtra(EXTRA_ORIGIN_X, -1)
        originY = intent.getIntExtra(EXTRA_ORIGIN_Y, -1)
        originW = intent.getIntExtra(EXTRA_ORIGIN_W, 0)
        originH = intent.getIntExtra(EXTRA_ORIGIN_H, 0)
        hasOrigin = originX >= 0 && originY >= 0 && originW > 0 && originH > 0
        blurBackground = BubblePrefs.snapshotBgBlur(this)
        // 普通模式使用氛围背景；模糊模式使用更明显的系统背景模糊，
        // 再叠加半透明材质层、高光和细砂噪点，尽量接近系统级磨砂玻璃。
        // 设备不支持跨窗口模糊时，这个值会被系统忽略，仍保留材质层作为降级效果。
        blurRadiusPx = if (blurBackground) dp(44) else 0

        val window = window
        // 查看框只绘制在系统栏下方，顶部状态栏保持透明露出底层界面
        // 关闭窗口自带的系统栏避让，统一由下面 inset 监听应用一份边距，避免普通机型出现双重空白
        // Android 15/16 对 target 35+ 强制 edge-to-edge。通过 AndroidX 统一设置，
        // 避免直接改 decorView/systemUiVisibility 在不同版本上互相覆盖。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        if (blurBackground) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        // 黑底/半透明蒙版都应配浅色状态栏/导航栏图标，去掉主题里的深色图标标志
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            ambientBackground = AmbientBackgroundDrawable()
            ambientBackground.dynamic = !blurBackground
            ambientBackground.blurGlass = blurBackground
            ambientBackground.cornerRadiusPx = if (blurBackground) dp(20).toFloat() else 0f
            background = ambientBackground
        }

        area = FrameLayout(this)
        fun newImageView(): ZoomableImageView {
            val cornerDp = BubblePrefs.snapshotCornerDpFresh(this)
            return ZoomableImageView(this).apply {
                onVerticalFling = { up ->
                    if (up) {
                        finishWithExit()
                        true
                    } else false
                }
                onSingleTap = {
                    toggleBar()
                    true
                }
                onHorizontalFling = { left ->
                    // 左滑看右一张（气泡里更靠右/更新），右滑看左一张（更靠左/更旧）
                    val target = index + (if (left) 1 else -1)
                    if (target !in uris.indices) false else {
                        showIndex(target)
                        true
                    }
                }
                setCornerRadius(dp(cornerDp).toFloat())
                // 图片更贴近底部按钮行
                setBaseInset(dp(4).toFloat())
                // 仅模糊模式增加轻微悬浮层次，普通模式保持原样。
                if (blurBackground) elevation = dp(6).toFloat()
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            dp(cornerDp).toFloat()
                        )
                    }
                }
            }
        }
        viewA = newImageView()
        viewB = newImageView()
        front = viewA
        // 深色磨砂：在系统模糊之上平铺一层细砂噪点，形成磨砂玻璃的颗粒观感；
        // 非模糊模式保持原有纯黑底，这一层不参与布局
        val noise = View(this)
        noise.visibility = if (blurBackground) View.VISIBLE else View.GONE
        if (blurBackground) {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val rand = Random()
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val v = 55 + rand.nextInt(36)
                    bmp.setPixel(x, y, Color.argb(6 + rand.nextInt(5), v, v, v))
                }
            }
            val bd = BitmapDrawable(resources, bmp)
            bd.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            noise.background = bd
        }
        area.addView(
            noise,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // 层级：噪点层最底，failText 在上，两张图片最顶；切换时用 bringChildToFront 控制谁在前
        failText = TextView(this).apply {
            text = "图片无法读取"
            setTextColor(0xFF9CA3AF.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        area.addView(
            failText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        )
        area.addView(
            viewA,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        area.addView(
            viewB,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            area,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            // 玻璃面板：上缘保留透光感、下缘加深以托住按钮，同时保留一条柔和高光边；
            // 黑底模式改用浅炭灰 + 顶部细线，和纯黑背景拉开层次
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                if (blurBackground) {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(0x301C1C1E.toInt(), 0x9A14171B.toInt())
                } else {
                    // 普通模式保持原先明确的深黑操作区，不让氛围色冲淡底部层次。
                    setColor(0xFF151619.toInt())
                }
                setStroke(dp(1), if (blurBackground) 0x55FFFFFF else 0x33FFFFFF.toInt())
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        // 左侧：随“自动删除相册原图”开关切换的按钮
        val autoClean = BubblePrefs.snapshotAutoClean(this)
        sideBtn = TextView(this).apply {
            text = if (autoClean) "保存到相册" else "删除相册图片"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            // 复用样式文档的毛玻璃片：20% 白填充 + 40% 白描边
            background = glass(12)
            setOnClickListener { onSideAction() }
        }
        val sideCell = FrameLayout(this)
        sideCell.addView(
            sideBtn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
        )
        bar.addView(
            sideCell,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )

        // 居中：关闭
        closeBtn = TextView(this).apply {
            text = "取消展示"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(26), dp(8), dp(26), dp(8))
            background = glass(12)
            setOnClickListener { closeCurrent() }
        }
        val centerCell = FrameLayout(this)
        centerCell.addView(
            closeBtn,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        bar.addView(
            centerCell,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.6f)
        )

        // 右侧：多图时显示页码，保持「取消展示」居中
        pageIndicator = TextView(this).apply {
            setTextColor(0xFFE7E9EE.toInt())
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x66000000.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setOnClickListener { openSourceApp() }
        }
        val rightCell = FrameLayout(this)
        rightCell.addView(
            pageIndicator,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            )
        )
        bar.addView(
            rightCell,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
        // 模糊模式背景提升到 Decor 层，确保从状态栏上沿连续覆盖到屏幕底部，
        // 不受查看区图片留白和内容 inset 影响；普通模式保持原有 root 层级。
        if (blurBackground) {
            root.background = ColorDrawable(Color.TRANSPARENT)
        }
        root.visibility = View.INVISIBLE
        registerBackCallback()
        overridePendingTransition(0, 0)

        applySystemBarInsets()
        showIndex(index, force = true)
        // 布局完成后按「取消展示」按钮实际高度校准一次图标尺寸
        root.post {
            if (closeBtn.height > 0) updatePageIndicator()
            if (blurBackground) {
                val decorLoc = IntArray(2)
                val areaLoc = IntArray(2)
                window.decorView.getLocationOnScreen(decorLoc)
                area.getLocationOnScreen(areaLoc)
                ambientBackground.panelBottomPx =
                    (areaLoc[1] + area.height - decorLoc[1])
                        .coerceIn(1, root.height).toFloat()
                ambientBackground.invalidateSelf()
            }
        }
    }

    /**
     * MIUI 的透明窗口不会自动给内容避让状态栏/导航栏，这里把查看区整体下移，
     * 顶部保留系统状态栏区域；底部不再留白，底栏延伸到导航栏区域，
     * 按钮用栏内 padding 抬到手势小白条上方。
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // 将刘海/挖孔安全区并入系统栏 inset，避免 Android 16 edge-to-edge 下内容压到 cutout。
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val top = if (bars.top > 0) bars.top else systemDimen("status_bar_height")
            val bottom = if (bars.bottom > 0) bars.bottom else systemDimen("navigation_bar_height")
            v.setPadding(0, top, 0, 0)
            bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bottom + dp(2))
            Log.d(TAG, "查看框 insets top=$top bottom=$bottom")
            insets
        }
        // 部分 MIUI 透明窗口首次不会派发 insets，attach 后再主动应用一次资源高度兜底
        root.post {
            if (root.paddingTop == 0 && root.paddingBottom == 0) {
                val bottom = systemDimen("navigation_bar_height")
                root.setPadding(0, systemDimen("status_bar_height"), 0, 0)
                bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bottom + dp(2))
            }
        }
    }

    private fun systemDimen(name: String): Int = runCatching {
        val id = resources.getIdentifier(name, "dimen", "android")
        if (id != 0) resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    /** 切换到第 i 张：降采样加载，按方向滑入（原位切换淡入）并刷新页码。 */
    private fun showIndex(i: Int, force: Boolean = false) {
        if (uris.isEmpty()) return
        val target = i.coerceIn(0, uris.size - 1)
        if (!force && target == index) return
        val oldIndex = index
        index = target
        // 下一张从右侧滑入、上一张从左侧滑入；首次打开/原位切换/关闭后切邻图不滑动
        val fromX: Float? = when {
            force || target == oldIndex -> null
            target > oldIndex -> front.width.toFloat()
            else -> -front.width.toFloat()
        }
        updatePageIndicator()
        val seq = ++loadSeq
        val u = uris[index]
        failText.visibility = View.GONE
        bitmapCache[u]?.let { cached ->
            showBitmap(cached, fromX)
            preloadAround(index)
            return
        }
        Thread {
            val bitmap = decodeSampled(
                u,
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            )
            runOnUiThread {
                if (seq != loadSeq) return@runOnUiThread
                if (bitmap != null) {
                    bitmapCache[u] = bitmap
                    showBitmap(bitmap, fromX)
                    preloadAround(index)
                } else {
                    viewA.setImageBitmap(null)
                    viewB.setImageBitmap(null)
                    viewA.visibility = View.GONE
                    viewB.visibility = View.GONE
                    failText.visibility = View.VISIBLE
                    if (!openingStarted) {
                        root.visibility = View.VISIBLE
                        attachBlurBackgroundIfNeeded()
                        openingStarted = true
                        playEnter()
                    }
                }
            }
        }.start()
    }

    /** 右下角显示来源 App 图标，多图时后附页码；长按/无障碍读出应用名，点击可打开来源应用。 */
    private fun updatePageIndicator() {
        val source = sources.getOrNull(index)?.takeIf { it.isNotBlank() }
        val pkg = sourcePackages.getOrNull(index)?.takeIf { it.isNotBlank() }
        val icon = pkg?.let { loadSourceIcon(it) }
        val iconSize = pageIndicatorIconSize()
        icon?.setBounds(0, 0, iconSize, iconSize)
        pageIndicator.setCompoundDrawables(icon, null, null, null)
        pageIndicator.compoundDrawablePadding = if (icon != null) dp(5) else 0
        pageIndicator.text = if (uris.size > 1) "${index + 1}/${uris.size}" else null
        pageIndicator.visibility =
            if (icon != null || uris.size > 1) View.VISIBLE else View.GONE
        pageIndicator.isClickable = pkg != null
        pageIndicator.isEnabled = pkg != null
        pageIndicator.contentDescription = source
        pageIndicator.tooltipText = source
    }

    /** 来源图标尺寸：跟随「取消展示」按钮实际高度，首次布局未完成时用 32dp 兜底。 */
    private fun pageIndicatorIconSize(): Int {
        val h = closeBtn.height
        return if (h > 0) h else dp(32)
    }

    /** 来源 App 图标：优先用应用列表缓存里的圆角图，缓存缺失时现取并套用项目统一的圆角样式。 */
    private fun loadSourceIcon(pkg: String): Drawable? {
        AppListCache.loadIcon(this, pkg)?.let { return it }
        return runCatching {
            val size = dp(72)
            IconUtil.rounded(
                packageManager.getApplicationIcon(pkg),
                size,
                (size / 4).toFloat(),
                resources
            )
        }.getOrNull()
    }

    /** 点击来源 App 名称：用包名拉起对应应用，找不到启动入口时提示。 */
    private fun openSourceApp() {
        val pkg = sourcePackages.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return
        try {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (BubblePrefs.snapshotOpenSourceClose(this)) {
                    sourceOpenStopped = false
                    sourceOpenPendingClose = true
                }
                startActivity(launch)
            } else {
                Toast.makeText(this, "无法打开来源应用", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            sourceOpenPendingClose = false
            sourceOpenStopped = false
            Toast.makeText(this, "无法打开来源应用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        if (sourceOpenPendingClose) sourceOpenStopped = true
    }

    override fun onResume() {
        super.onResume()
        // 从来源 App 返回：按设置直接关闭查看框，露出打开查看框前的界面
        if (sourceOpenStopped) {
            sourceOpenStopped = false
            sourceOpenPendingClose = false
            if (!isFinishing && !exiting) {
                restoreBubbleAfterClose()
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onDestroy() {
        // 处理系统返回、来源 App 返回等非 finishWithExit 路径。
        restoreBubbleAfterClose()
        transitionAnimator?.cancel()
        transitionAnimator = null
        backPressedCallback?.remove()
        backPressedCallback = null
        super.onDestroy()
    }

    /** 使用 AndroidX 返回分发器兼容 Android 12–16，并统一导向自定义退场动画。 */
    private fun registerBackCallback() {
        backPressedCallback?.remove()
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithExit()
            }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
    }

    private fun restoreBubbleAfterClose() {
        if (restoreBubbleSent || uris.isEmpty()) return
        restoreBubbleSent = true
        try {
            sendBroadcast(
                Intent("com.example.supermi.RESTORE_SNAPSHOT")
                    .setPackage("android"),
                "com.example.supermi.permission.SHOW_SNAPSHOT"
            )
        } catch (_: Throwable) {
        }
    }

    /** 从气泡原点缩小态放大到全屏，背景/系统栏同步淡入；模糊模式还联动模糊半径。 */
    private fun playEnter() {
        transitionAnimator?.cancel()
        val s0: Float
        val tx0: Float
        val ty0: Float
        if (hasOrigin) {
            val areaLoc = IntArray(2)
            area.getLocationOnScreen(areaLoc)
            val acx = areaLoc[0] + area.width / 2f
            val acy = areaLoc[1] + area.height / 2f
            val ocx = originX + originW / 2f
            val ocy = originY + originH / 2f
            s0 = (originW.toFloat() / area.width).coerceIn(0.05f, 1f)
            tx0 = ocx - acx
            ty0 = ocy - acy
            area.pivotX = area.width / 2f
            area.pivotY = area.height / 2f
        } else {
            s0 = 1f
            tx0 = 0f
            ty0 = 0f
        }
        area.scaleX = s0
        area.scaleY = s0
        area.translationX = tx0
        area.translationY = ty0
        area.alpha = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 340L
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { va ->
            val f = va.animatedFraction
            val s = s0 + (1f - s0) * f
            area.scaleX = s
            area.scaleY = s
            area.translationX = tx0 * (1f - f)
            area.translationY = ty0 * (1f - f)
            area.alpha = f
            ambientBackground.progress = f
            ambientBackground.invalidateSelf()
            if (blurBackground) {
                val lp = window.attributes
                lp.blurBehindRadius = (blurRadiusPx * f).toInt()
                window.attributes = lp
            }
            if (f >= 0.35f && !barVisible) {
                showBar()
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (exiting) return
                area.translationX = 0f
                area.translationY = 0f
                area.scaleX = 1f
                area.scaleY = 1f
                area.alpha = 1f
                ambientBackground.progress = 1f
                ambientBackground.invalidateSelf()
                if (blurBackground) {
                    val lp = window.attributes
                    lp.blurBehindRadius = blurRadiusPx
                    window.attributes = lp
                }
                showBar()
                if (transitionAnimator === animation) transitionAnimator = null
            }
        })
        transitionAnimator = anim
        anim.start()
    }

    /** 退场：图片缩回气泡原点、背景/系统栏淡回透明，动画结束关闭页面。 */
    private fun finishWithExit() {
        if (exiting) return
        exiting = true
        // 预测性返回回调和旧式 onBackPressed 可能在同一条返回链路中都到达；
        // 除了 exiting 防重入，还要取消尚未结束的入场过渡，防止它继续改写 alpha/scale。
        transitionAnimator?.cancel()
        transitionAnimator = null
        viewA.animate().cancel()
        viewB.animate().cancel()
        area.animate().cancel()
        bar.animate().cancel()
        // 打断滑动时旧图可能停在半路，退场前只保留当前页
        val other = if (front === viewA) viewB else viewA
        clearView(other)
        front.translationX = 0f
        front.alpha = 1f
        area.translationX = 0f
        area.translationY = 0f
        area.scaleX = 1f
        area.scaleY = 1f
        area.alpha = 1f

        // 退场目标固定在动画开始前的位置，避免逐帧重算导致只飞到半路
        val areaLoc = IntArray(2)
        area.getLocationOnScreen(areaLoc)
        val areaCenterX = areaLoc[0] + area.width / 2f
        val areaCenterY = areaLoc[1] + area.height / 2f
        val moveX = originX + originW / 2f - areaCenterX
        val moveY = originY + originH / 2f - areaCenterY
        // 气泡靠近屏幕中部时不做长距离平移，直接中心缩小淡出，避免微小漂移
        val nearCenter = hasOrigin &&
            Math.abs(moveX) <= area.width * 0.2f &&
            Math.abs(moveY) <= area.height * 0.2f
        val sEnd = if (hasOrigin) {
            (originW.toFloat() / area.width).coerceIn(0.05f, 1f)
        } else 0.72f
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = if (hasOrigin) 280L else 220L
        anim.interpolator = AccelerateInterpolator()
        anim.addUpdateListener { va ->
            val f = va.animatedFraction
            val s = 1f + (sEnd - 1f) * f
            area.scaleX = s
            area.scaleY = s
            if (hasOrigin && !nearCenter) {
                area.translationX = moveX * f
                area.translationY = moveY * f
            }
            area.alpha = (1f - f).coerceAtLeast(0f)
            ambientBackground.progress = 1f - f
            ambientBackground.invalidateSelf()
            if (blurBackground) {
                val lp = window.attributes
                lp.blurBehindRadius = (blurRadiusPx * (1f - f)).toInt()
                window.attributes = lp
            }
            bar.alpha = (1f - f).coerceAtLeast(0f)
            bar.translationY = (1f - f).coerceAtLeast(0f) * dp(24)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (transitionAnimator === animation) transitionAnimator = null
                restoreBubbleAfterClose()
                finish()
                overridePendingTransition(0, 0)
            }
        })
        transitionAnimator = anim
        anim.start()
    }

    /** 底部操作栏淡入。 */
    private fun showBar() {
        if (barVisible) return
        barVisible = true
        bar.visibility = View.VISIBLE
        bar.alpha = 0f
        bar.translationY = dp(24).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    /** 点击图片切换底部操作栏显隐。 */
    private fun toggleBar() {
        if (barVisible) {
            barVisible = false
            bar.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(180L)
                .withEndAction { bar.visibility = View.INVISIBLE }
                .start()
        } else {
            showBar()
        }
    }

    /**
     * 双层页切换：新图从对应方向滑入，旧图被同步往反方向推走，
     * 两张图全程不透明，动画结束才清空旧图，避免切换时闪黑。
     */
    private fun showBitmap(bitmap: Bitmap, fromX: Float?) {
        ambientBackground.animateAccentColor(sampleAmbientColor(bitmap))
        if (!blurBackgroundAttached) attachBlurBackgroundIfNeeded()
        root.visibility = View.VISIBLE
        val incoming = if (front === viewA) viewB else viewA
        val outgoing = front
        // 可能打断上一次滑动：先把两边动画停掉、归位，避免残留位移叠到新动画上
        incoming.animate().cancel()
        outgoing.animate().cancel()
        outgoing.translationX = 0f
        outgoing.alpha = 1f
        incoming.setBitmap(bitmap)
        incoming.visibility = View.VISIBLE
        incoming.translationX = fromX ?: 0f
        incoming.alpha = 1f
        area.bringChildToFront(incoming)
        front = incoming
        if (fromX == null) {
            clearView(outgoing)
            if (!openingStarted) {
                openingStarted = true
                playEnter()
            }
        } else {
            outgoing.animate().translationX(-fromX)
                .setDuration(SWIPE_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            incoming.animate().alpha(1f).translationX(0f)
                .setDuration(SWIPE_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { clearView(outgoing) }
                .start()
        }
    }

    /** 预加载左右相邻图到内存缓存，滑动切换不再等磁盘解码。 */
    private fun preloadAround(i: Int) {
        if (uris.size <= 1) return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        for (j in intArrayOf(i - 1, i + 1)) {
            if (j !in uris.indices) continue
            val u = uris[j]
            if (bitmapCache.containsKey(u)) continue
            Thread {
                val b = decodeSampled(u, w, h)
                if (b != null) {
                    runOnUiThread {
                        if (u in uris && !bitmapCache.containsKey(u)) {
                            bitmapCache[u] = b
                        } else {
                            b.recycle()
                        }
                    }
                }
            }.start()
        }
    }

    /** 清空下层图片，恢复默认状态等待下次复用。 */
    private fun clearView(v: ZoomableImageView) {
        v.setImageBitmap(null)
        v.alpha = 1f
        v.translationX = 0f
        v.visibility = View.GONE
    }

    private fun attachBlurBackgroundIfNeeded() {
        if (!blurBackground || blurBackgroundAttached) return
        window.decorView.background = ambientBackground
        blurBackgroundAttached = true
    }

    /** 从当前截图提取低频主色，只用于背景氛围，不改变原图颜色。 */
    private fun sampleAmbientColor(bitmap: Bitmap): Int {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val c = bitmap.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                count++
            }
        }
        if (count == 0L) return Color.rgb(40, 42, 48)
        // 降低饱和度并压暗，避免鲜艳背景喧宾夺主。
        return Color.rgb(
            ((r / count) * 0.62f + 18f).toInt().coerceIn(18, 110),
            ((g / count) * 0.62f + 20f).toInt().coerceIn(20, 110),
            ((b / count) * 0.62f + 24f).toInt().coerceIn(24, 120)
        )
    }

    /** 低成本氛围背景：主色渐变 + 中心柔光 + 边缘暗角。 */
    private class AmbientBackgroundDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var accent = Color.rgb(40, 42, 48)
        private var accentAnimator: ValueAnimator? = null
        var dynamic = false
        var blurGlass = false
        var cornerRadiusPx = 0f
        var panelBottomPx = 0f
        var progress = 0f

        fun setAccentColor(color: Int) {
            accentAnimator?.cancel()
            accent = color
            invalidateSelf()
        }

        fun animateAccentColor(color: Int) {
            accentAnimator?.cancel()
            if (!blurGlass) {
                setAccentColor(color)
                return
            }
            val start = accent
            accentAnimator = ValueAnimator.ofObject(ArgbEvaluator(), start, color).apply {
                duration = 220L
                addUpdateListener {
                    accent = it.animatedValue as Int
                    invalidateSelf()
                }
                start()
            }
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val alpha = (progress.coerceIn(0f, 1f) * 255f).toInt()
            if (blurGlass && cornerRadiusPx > 0f) {
                canvas.save()
                val clip = Path().apply {
                    addRoundRect(
                        RectF(0f, 0f, w, h),
                        cornerRadiusPx,
                        cornerRadiusPx,
                        Path.Direction.CW
                    )
                }
                canvas.clipPath(clip)
            }
            if (!dynamic && !blurGlass) {
                paint.shader = null
                paint.color = Color.argb(alpha, 17, 17, 17)
                canvas.drawRect(0f, 0f, w, h, paint)
                return
            }
            if (!dynamic) {
                // 毛玻璃模式：外圈保持暗黑，内层是从状态栏上沿开始的圆角亮面。
                paint.shader = null
                paint.color = Color.argb((alpha * 0.44f).toInt(), 7, 8, 11)
                canvas.drawRect(0f, 0f, w, h, paint)
                val inset = (cornerRadiusPx * 0.6f).coerceAtLeast(8f)
                // 底边与实际图片区域(area)对齐，不再用固定 h-inset 导致内层偏矮。
                val panelBottom = if (panelBottomPx > 0f) panelBottomPx else h
                val panel = RectF(inset, 0f, w - inset, panelBottom)
                paint.shader = LinearGradient(
                    0f, 0f, 0f, panel.bottom,
                    Color.argb((alpha * 0.20f).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)),
                    Color.argb((alpha * 0.14f).toInt(), 24, 25, 30),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(panel, cornerRadiusPx, cornerRadiusPx, paint)
                // 内层仅保留很轻的中心提亮，避免再次形成明显横向分层。
                val glow = Color.argb((alpha * 0.06f).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
                paint.shader = RadialGradient(w * 0.5f, h * 0.34f, w * 0.72f, glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(panel, cornerRadiusPx, cornerRadiusPx, paint)
                paint.shader = null
                if (blurGlass && cornerRadiusPx > 0f) {
                    canvas.restore()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f
                    paint.color = Color.argb((alpha * 0.16f).toInt(), 255, 255, 255)
                    canvas.drawRoundRect(
                        0.75f, 0.75f, w - 0.75f, h - 0.75f,
                        cornerRadiusPx, cornerRadiusPx, paint
                    )
                    paint.style = Paint.Style.FILL
                }
                return
            }
            val top = Color.argb(alpha, Color.red(accent), Color.green(accent), Color.blue(accent))
            val bottom = Color.argb(alpha, 3, 4, 6)
            paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                h,
                intArrayOf(top, Color.argb(alpha, 12, 13, 17), bottom),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)

            // 图片中部留一点色彩呼吸感，底栏和边缘仍保持足够对比度。
            val glow = Color.argb((alpha * 0.42f).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            paint.shader = RadialGradient(w * 0.5f, h * 0.38f, w * 0.75f, glow, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.5f, h * 0.42f, w * 0.82f, Color.TRANSPARENT, Color.argb((alpha * 0.72f).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            if (blurGlass && cornerRadiusPx > 0f) canvas.restore()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** 按屏幕尺寸降采样解码，避免超大图占满内存。 */
    private fun decodeSampled(uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= maxW &&
                    opts.outHeight / (sample * 2) >= maxH
                ) {
                    sample *= 2
                }
                contentResolver.openInputStream(uri)?.use { input2 ->
                    BitmapFactory.decodeStream(
                        input2,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 关闭当前图：删缓存并通知气泡移除，随后切到相邻图；只剩一张则关闭查看框。 */
    private fun closeCurrent() {
        val removed = index
        val u = uris.getOrNull(removed) ?: return
        try {
            SnapshotStore.delete(this, u)
        } catch (_: Throwable) {
        }
        uris.removeAt(removed)
        bitmapCache.remove(u)
        if (removed < sources.size) sources.removeAt(removed)
        if (removed < sourcePackages.size) sourcePackages.removeAt(removed)
        if (uris.isEmpty()) {
            finishWithExit()
            return
        }
        val next = if (removed >= uris.size) uris.size - 1 else removed
        showIndex(next, force = true)
    }

    /** 左侧按钮动作：按开关分派到“保存到相册”或“删除相册图片”，不关闭查看框。 */
    private fun onSideAction() {
        if (sideActionBusy) return
        val u = uris.getOrNull(index) ?: return
        val autoClean = BubblePrefs.snapshotAutoClean(this)
        val label = if (autoClean) "保存到相册" else "删除相册图片"
        sideActionBusy = true
        Thread {
            try {
                val msg = if (autoClean) {
                    SnapshotStore.saveToGallery(this, u)
                } else {
                    SnapshotStore.deleteGalleryOnly(this, u)
                }
                runOnUiThread {
                    Toast.makeText(this, "$label: $msg", Toast.LENGTH_SHORT).show()
                }
            } finally {
                sideActionBusy = false
            }
        }.start()
    }

    /** 圆角胶囊背景。 */
    private fun pill(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** 样式文档的毛玻璃片：20% 白填充、40% 白描边、12dp 圆角。 */
    private fun glass(radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x33FFFFFF.toInt())
            setStroke(dp(1), 0x66FFFFFF.toInt())
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val SWIPE_ANIM_MS = 120L
        private const val TAG = "SuperMi"

        const val EXTRA_URI = "snapshot_uri"
        const val EXTRA_URIS = "snapshot_uris"
        const val EXTRA_INDEX = "snapshot_index"
        const val EXTRA_ORIGIN_X = "snapshot_origin_x"
        const val EXTRA_ORIGIN_Y = "snapshot_origin_y"
        const val EXTRA_ORIGIN_W = "snapshot_origin_w"
        const val EXTRA_ORIGIN_H = "snapshot_origin_h"
        const val EXTRA_SOURCES = "snapshot_sources"
        const val EXTRA_SOURCE_PACKAGES = "snapshot_source_packages"
    }
}
