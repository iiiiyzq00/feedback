package com.feedback.childartdemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

class DrawingView(context: Context) : View(context) {
    private data class Stroke(val path: Path, val color: Int, val width: Float)
    private val strokes = mutableListOf<Stroke>()
    private var active: Stroke? = null
    var brushColor: Int = Color.parseColor("#5C6BC0")
    var brushWidth: Float = 12f
    var onStrokeFinished: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setBackgroundColor(Color.WHITE)
        elevation = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        (strokes + listOfNotNull(active)).forEach {
            paint.color = it.color
            paint.strokeWidth = it.width
            canvas.drawPath(it.path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = Stroke(Path().apply { moveTo(event.x, event.y) }, brushColor, brushWidth)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                active?.path?.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active?.let(strokes::add)
                active = null
                invalidate()
                onStrokeFinished?.invoke()
            }
        }
        return true
    }

    fun clearDrawing() {
        strokes.clear()
        active = null
        invalidate()
    }

    fun strokeCount(): Int = strokes.size
}
