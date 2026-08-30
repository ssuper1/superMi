package com.example.supermi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.util.AttributeSet
import kotlin.math.abs
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * 可缩放图片视图：初始适配屏幕，支持双指缩放、拖动、双击放大/还原。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    /**
     * 未放大状态下的水平快速滑动回调。
     * 参数 left=true 表示手指向左滑（下一张，气泡里更靠右/更新的一张），
     * left=false 表示向右滑（上一张，更靠左/更旧的一张）。
     * 返回 true 表示已消费本次滑动。
     */
    var onHorizontalFling: ((left: Boolean) -> Boolean)? = null

    /**
     * 未放大状态下的垂直快速滑动回调，参数 up=true 表示向上滑。
     * 返回 true 表示已消费本次滑动。
     */
    var onVerticalFling: ((up: Boolean) -> Boolean)? = null

    /** 单击确认回调（双击放大判定失败后触发），用于切换操作栏显隐。 */
    var onSingleTap: (() -> Boolean)? = null

    private val matrix = Matrix()
    private val start = PointF()
    private var lastFocus = PointF()
    private var mode = 0
    private var scaleGestureSeen = false
    private var lastZoomAt = 0L
    private var userScaled = false
    private var bitmapW = 0
    private var bitmapH = 0
    private var baseScale = 1f
    private var needsBaseFit = false
    private var baseInsetPx = 0f
    private var cornerRadiusPx = 0f
    private val clipPath = Path()
    private val imageRect = RectF()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleGestureSeen = true
                lastZoomAt = SystemClock.uptimeMillis()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                lastZoomAt = SystemClock.uptimeMillis()
                zoomTo(detector.scaleFactor, detector.focusX, detector.focusY)
                if (currentScale() > baseScale * 1.01f) userScaled = true
                return true
            }
        }
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                lastZoomAt = SystemClock.uptimeMillis()
                Log.d("SuperMi", "查看框双击 zoom=${currentScale() / baseScale} userScaled=$userScaled")
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || mode == 2 || scaleDetector.isInProgress || scaleGestureSeen) return false
                if (userScaled) return false
                if (SystemClock.uptimeMillis() - lastZoomAt < 1500L) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                Log.d(
                    "SuperMi",
                    "查看框 fling dx=$dx dy=$dy zoom=${currentScale() / baseScale} " +
                        "userScaled=$userScaled mode=$mode scaleSeen=$scaleGestureSeen"
                )
                // 竖滑优先：气泡/查看框从顶部展开，向上快速滑动用于关闭查看框
                if (abs(dy) > abs(dx) * 1.4f && dy < -60f && velocityY < -1600f) {
                    return onVerticalFling?.invoke(true) ?: false
                }
                if (abs(dx) < 140f || abs(dx) < abs(dy) * 1.4f || abs(velocityX) < 500f) {
                    return false
                }
                return onHorizontalFling?.invoke(dx < 0) ?: false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                return onSingleTap?.invoke() ?: false
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setBitmap(bitmap: Bitmap) {
        bitmapW = bitmap.width
        bitmapH = bitmap.height
        needsBaseFit = true
        userScaled = false
        setImageBitmap(bitmap)
        if (width > 0 && height > 0) fitToBase()
    }

    /** 初始（未缩放）适配时保留的内边距；放大后不再受此限制，可铺满视图。 */
    fun setBaseInset(px: Float) {
        baseInsetPx = px
        if (bitmapW > 0 && bitmapH > 0 && width > 0 && height > 0) fitToBase()
    }

    /** 圆角作用于图片本身的绘制区域，放大/平移时跟随图片，而不是只裁外层 View。 */
    fun setCornerRadius(radiusPx: Float) {
        cornerRadiusPx = radiusPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (bitmapW > 0 && bitmapH > 0 && cornerRadiusPx > 0f) {
            imageRect.set(0f, 0f, bitmapW.toFloat(), bitmapH.toFloat())
            imageMatrix.mapRect(imageRect)
            if (imageRect.width() > 0f && imageRect.height() > 0f) {
                val radius = minOf(
                    cornerRadiusPx,
                    imageRect.width() / 2f,
                    imageRect.height() / 2f
                )
                clipPath.reset()
                clipPath.addRoundRect(imageRect, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clipPath)
                super.onDraw(canvas)
                canvas.restore()
                return
            }
        }
        super.onDraw(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsBaseFit && bitmapW > 0 && bitmapH > 0) fitToBase()
    }

    /** 以完整可见的方式适配到视图内并居中。 */
    private fun fitToBase() {
        if (bitmapW <= 0 || bitmapH <= 0 || width <= 0 || height <= 0) return
        val availW = (width - baseInsetPx * 2f).coerceAtLeast(1f)
        val availH = (height - baseInsetPx * 2f).coerceAtLeast(1f)
        baseScale = minOf(availW / bitmapW, availH / bitmapH)
        val tx = baseInsetPx + (availW - bitmapW * baseScale) / 2f
        val ty = baseInsetPx + (availH - bitmapH * baseScale) / 2f
        matrix.reset()
        matrix.postScale(baseScale, baseScale)
        matrix.postTranslate(tx, ty)
        imageMatrix = matrix
        needsBaseFit = false
        userScaled = false
    }

    private fun toggleZoom(fx: Float, fy: Float) {
        if (bitmapW <= 0 || bitmapH <= 0) return
        val values = FloatArray(9)
        matrix.getValues(values)
        if (isZoomed()) {
            fitToBase()
        } else {
            userScaled = true
            zoomTo(baseScale * 3f / values[Matrix.MSCALE_X], fx, fy)
        }
    }

    /** 是否处于大幅放大状态：双击放大/还原本用这个判断。 */
    private fun isZoomed(): Boolean {
        return currentScale() > baseScale * 1.5f
    }

    private fun zoomTo(factor: Float, fx: Float, fy: Float) {
        if (bitmapW <= 0 || bitmapH <= 0) return
        matrix.postScale(factor, factor, fx, fy)
        clampMatrix()
        imageMatrix = matrix
    }

    /** 限制缩放倍数与拖动范围，防止图片被拖出屏幕或缩得太小。 */
    private fun clampMatrix() {
        val values = FloatArray(9)
        matrix.getValues(values)
        var sx = values[Matrix.MSCALE_X]
        val min = baseScale
        val max = baseScale * 6f
        if (sx < min) {
            matrix.postScale(min / sx, min / sx, width / 2f, height / 2f)
            sx = min
        } else if (sx > max) {
            matrix.postScale(max / sx, max / sx, width / 2f, height / 2f)
            sx = max
        }

        matrix.getValues(values)
        val scaledW = bitmapW * sx
        val scaledH = bitmapH * sx
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]

        val minTx = if (scaledW <= width) (width - scaledW) / 2f else width - scaledW
        val maxTx = if (scaledW <= width) (width - scaledW) / 2f else 0f
        val minTy = if (scaledH <= height) (height - scaledH) / 2f else height - scaledH
        val maxTy = if (scaledH <= height) (height - scaledH) / 2f else 0f
        tx = tx.coerceIn(minTx, maxTx)
        ty = ty.coerceIn(minTy, maxTy)

        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        matrix.setValues(values)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1
                scaleGestureSeen = false
                start.set(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = 2
                lastFocus.set(
                    (event.getX(0) + event.getX(1)) / 2f,
                    (event.getY(0) + event.getY(1)) / 2f
                )
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount >= 2) {
                    val other = if (event.actionIndex == 0) 1 else 0
                    val x = event.getX(other)
                    val y = event.getY(other)
                    lastFocus.set(x, y)
                    start.set(x, y)
                    mode = 1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 1 && !scaleDetector.isInProgress) {
                    matrix.postTranslate(event.x - start.x, event.y - start.y)
                    clampMatrix()
                    imageMatrix = matrix
                    start.set(event.x, event.y)
                } else if (mode == 2) {
                    val fx = (event.getX(0) + event.getX(1)) / 2f
                    val fy = (event.getY(0) + event.getY(1)) / 2f
                    matrix.postTranslate(fx - lastFocus.x, fy - lastFocus.y)
                    clampMatrix()
                    imageMatrix = matrix
                    lastFocus.set(fx, fy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }
}
