package com.example.supermi

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class CornerPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cornerPx = 0
    private val path = Path()
    private val rect = RectF()
    private val round = RectF()
    private val contentRect = RectF()
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF141A24.toInt() }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E2836.toInt() }
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B3646.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF56A6F5.toInt() }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
        color = 0xFF2E86DE.toInt()
    }

    fun setCornerDp(v: Int) {
        cornerPx = dp(v)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dp(8)
        val maxW = (width - pad * 2).coerceAtLeast(1)
        val maxH = (height - pad * 2).coerceAtLeast(1)
        val ratio = 0.52f
        val rectH = min(maxH.toFloat(), maxW / ratio)
        val rectW = rectH * ratio
        rect.set(
            (width - rectW) / 2f,
            (height - rectH) / 2f,
            (width + rectW) / 2f,
            (height + rectH) / 2f
        )

        path.reset()
        path.addRoundRect(rect, cornerPx.toFloat(), cornerPx.toFloat(), Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, screenPaint)

        round.set(rect.left, rect.top, rect.right, rect.top + dp(28))
        canvas.drawRoundRect(round, dp(3).toFloat(), dp(3).toFloat(), headerPaint)
        canvas.drawCircle(rect.left + dp(18), rect.top + dp(14), dp(3).toFloat(), dotPaint)

        val photoTop = rect.top + dp(44)
        val photoBottom = rect.top + dp(44) + rect.width() * 0.68f
        contentRect.set(rect.left + dp(14), photoTop, rect.right - dp(14), photoBottom)
        photoPaint.shader = LinearGradient(
            contentRect.left,
            contentRect.top,
            contentRect.right,
            contentRect.bottom,
            intArrayOf(0xFF56A6F5.toInt(), 0xFF2E86DE.toInt(), 0xFF1B5FB8.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(contentRect, dp(6).toFloat(), dp(6).toFloat(), photoPaint)
        photoPaint.shader = null

        var lineTop = photoBottom + dp(12)
        val lineH = dp(6).toFloat()
        for (w in floatArrayOf(0.86f, 0.62f, 0.74f)) {
            contentRect.set(
                rect.left + dp(14),
                lineTop,
                rect.left + dp(14) + rect.width() * w,
                lineTop + lineH
            )
            canvas.drawRoundRect(contentRect, lineH / 2, lineH / 2, textPaint)
            lineTop += dp(12)
        }

        contentRect.set(
            rect.left + dp(14),
            rect.bottom - dp(30),
            rect.right - dp(14),
            rect.bottom - dp(10)
        )
        canvas.drawRoundRect(contentRect, dp(7).toFloat(), dp(7).toFloat(), headerPaint)
        canvas.restore()

        canvas.drawPath(path, strokePaint)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
