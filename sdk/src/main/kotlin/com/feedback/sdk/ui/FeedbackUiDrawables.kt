package com.feedback.sdk.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

internal enum class ScreenshotAction { VIEW, EDIT, RETAKE, DELETE }

internal class CompactCheckDrawable(private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var checked = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = state.contains(android.R.attr.state_checked)
        if (next == checked) return false
        checked = next
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val box = RectF(bounds.left + 1f, bounds.top + 1f, bounds.right - 1f, bounds.bottom - 1f)
        paint.style = Paint.Style.FILL
        paint.color = if (checked) Color.parseColor("#1677FF") else Color.WHITE
        canvas.drawRoundRect(box, size * .22f, size * .22f, paint)
        if (!checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * .085f).coerceAtLeast(1.5f)
            paint.color = Color.parseColor("#AAB3C0")
            canvas.drawRoundRect(box, size * .22f, size * .22f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * .12f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.WHITE
            val path = Path().apply {
                moveTo(bounds.left + size * .25f, bounds.top + size * .52f)
                lineTo(bounds.left + size * .43f, bounds.top + size * .70f)
                lineTo(bounds.left + size * .76f, bounds.top + size * .31f)
            }
            canvas.drawPath(path, paint)
        }
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal class ScreenshotActionDrawable(
    private val action: ScreenshotAction,
    private val color: Int,
    private val size: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@ScreenshotActionDrawable.color
        style = Paint.Style.STROKE
        strokeWidth = (size * .08f).coerceAtLeast(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val l = bounds.left.toFloat(); val t = bounds.top.toFloat()
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        when (action) {
            ScreenshotAction.VIEW -> {
                val eye = Path().apply {
                    moveTo(l + w * .12f, t + h * .52f)
                    cubicTo(l + w * .30f, t + h * .22f, l + w * .70f, t + h * .22f, l + w * .88f, t + h * .52f)
                    cubicTo(l + w * .70f, t + h * .82f, l + w * .30f, t + h * .82f, l + w * .12f, t + h * .52f)
                }
                canvas.drawPath(eye, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(l + w * .5f, t + h * .52f, w * .11f, paint)
                paint.style = Paint.Style.STROKE
            }
            ScreenshotAction.EDIT -> {
                canvas.drawLine(l + w * .22f, t + h * .78f, l + w * .68f, t + h * .32f, paint)
                canvas.drawLine(l + w * .68f, t + h * .32f, l + w * .80f, t + h * .44f, paint)
                canvas.drawLine(l + w * .22f, t + h * .78f, l + w * .39f, t + h * .73f, paint)
                canvas.drawRect(l + w * .15f, t + h * .15f, l + w * .85f, t + h * .85f, paint)
            }
            ScreenshotAction.RETAKE -> {
                canvas.drawArc(RectF(l + w * .19f, t + h * .19f, l + w * .81f, t + h * .81f), -55f, 285f, false, paint)
                val arrow = Path().apply {
                    moveTo(l + w * .70f, t + h * .15f)
                    lineTo(l + w * .84f, t + h * .23f)
                    lineTo(l + w * .73f, t + h * .35f)
                }
                canvas.drawPath(arrow, paint)
            }
            ScreenshotAction.DELETE -> {
                canvas.drawLine(l + w * .30f, t + h * .31f, l + w * .36f, t + h * .82f, paint)
                canvas.drawLine(l + w * .70f, t + h * .31f, l + w * .64f, t + h * .82f, paint)
                canvas.drawLine(l + w * .36f, t + h * .82f, l + w * .64f, t + h * .82f, paint)
                canvas.drawLine(l + w * .25f, t + h * .27f, l + w * .75f, t + h * .27f, paint)
                canvas.drawLine(l + w * .42f, t + h * .18f, l + w * .58f, t + h * .18f, paint)
            }
        }
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
