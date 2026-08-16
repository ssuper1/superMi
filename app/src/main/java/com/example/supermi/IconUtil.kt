package com.example.supermi

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

object IconUtil {

    /** 将任意 Drawable 绘制为指定尺寸的圆角图标（自适应图标同样适用） */
    fun rounded(drawable: Drawable, sizePx: Int, radiusPx: Float, resources: Resources): Drawable {
        if (sizePx <= 0) return drawable
        return try {
            val src = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(src)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(c)

            val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(
                RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                radiusPx,
                radiusPx,
                paint
            )
            BitmapDrawable(resources, out)
        } catch (_: Throwable) {
            drawable
        }
    }
}
