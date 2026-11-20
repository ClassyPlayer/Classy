package com.themoon.y1.controllers.coverflow

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The iPod floor reflection of a Cover Flow cover, drawn directly from the cover bitmap (no reflection bitmap):
 * the bottom rows of the cover, mirrored, from [CoverFlowGeometry.REFLECTION_TOP_ALPHA] opacity at the top
 * to 0 at the bottom (linear).
 *
 * One rectangle with a ComposeShader (cover BitmapShader DST_IN a LinearGradient), which Android 4.4 draws
 * with the GPU when the view draws it directly (not into a software bitmap). Each card keeps one drawable:
 * [setCover] makes only the cover shader again, and the gradient is made again only when the bounds change.
 *
 * The intrinsic size is the reflection part of a [emptyCoverSize] cover (256 x 104 for a thumbnail).
 * With no cover, it draws nothing.
 */
class CoverReflectionDrawable(emptyCoverSize: Int) : Drawable() {
    private val intrinsicWidth = emptyCoverSize
    private val intrinsicHeight = CoverFlowGeometry.reflectionRows(emptyCoverSize)

    var cover: Bitmap? = null
        private set
    private var coverShader: BitmapShader? = null
    private var fade: LinearGradient? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val shaderBounds = Rect()

    /** Shows [bitmap] (null: draws nothing). Returns false when it already shows it. */
    fun setCover(bitmap: Bitmap?): Boolean {
        if (bitmap === cover) return false
        cover = bitmap
        coverShader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        paint.shader = null
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val bmp = cover ?: return
        val shader = coverShader ?: return
        val b = bounds
        if (b.isEmpty) return
        if (b != shaderBounds) {
            fade = LinearGradient(
                0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                CoverFlowGeometry.REFLECTION_TOP_ALPHA shl 24, 0, Shader.TileMode.CLAMP
            )
            shaderBounds.set(b)
            paint.shader = null
        }
        if (paint.shader == null) {
            val t = CoverFlowGeometry.reflectionTransform(
                bmp.width, bmp.height, b.left.toFloat(), b.top.toFloat(), b.width().toFloat(), b.height().toFloat()
            )
            shaderMatrix.setScale(t.scaleX, t.scaleY)
            shaderMatrix.postTranslate(t.translateX, t.translateY)
            shader.setLocalMatrix(shaderMatrix)
            // DST_IN: the cover pixels, with the opacity of the gradient.
            paint.shader = ComposeShader(shader, fade!!, PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(b, paint)
    }

    override fun getIntrinsicWidth(): Int = intrinsicWidth

    override fun getIntrinsicHeight(): Int = intrinsicHeight

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
