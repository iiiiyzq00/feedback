package com.feedback.sdk.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

/** 大图截图查看器，外加一个轻量、零依赖的隐私标注编辑器。 */
internal class ScreenshotEditorDialog(
    context: Context,
    private val source: ByteArray,
    private val editable: Boolean,
    private val onSaved: (ByteArray) -> Unit
) : Dialog(context) {
    private val bitmap: Bitmap? = BitmapFactory.decodeByteArray(source, 0, source.size)
    private lateinit var editor: ScreenshotEditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val decoded = bitmap
        if (decoded == null) {
            dismiss()
            return
        }
        editor = ScreenshotEditorView(context, decoded).apply {
            mode = if (editable) EditMode.RECTANGLE else EditMode.VIEW
        }
        setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 31))
            addView(createHeader(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
            addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            if (editable) addView(createToolbar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        })
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.85f)
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun createHeader() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(12), 0)
        addView(TextView(context).apply {
            text = if (editable) "编辑截图" else "截图大图"
            textSize = 17f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(actionButton("关闭") { dismiss() })
    }

    private fun createToolbar() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(actionButton("▣  圈选") { editor.mode = EditMode.RECTANGLE; editor.invalidate() }, itemParams())
        addView(actionButton("●  黑色涂抹") { editor.mode = EditMode.REDACT; editor.invalidate() }, itemParams())
        addView(actionButton("↶  撤销") { editor.undo() }, itemParams())
        addView(actionButton("清空") { editor.clear() }, itemParams())
        addView(actionButton("保存") { save() }.apply {
            setTextColor(Color.WHITE)
            background = buttonBackground("#1677FF")
        }, itemParams())
    }

    private fun itemParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        marginEnd = dp(6)
    }

    private fun actionButton(label: String, click: () -> Unit) = Button(context).apply {
        text = label
        textSize = 12f
        minWidth = 0
        minHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.rgb(225, 230, 238))
        background = buttonBackground("#343B47")
        setOnClickListener { click() }
    }

    private fun buttonBackground(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(7).toFloat()
    }

    private fun save() {
        if (!editor.hasEdits()) {
            Toast.makeText(context, "请先圈选或涂抹需要标注的区域", Toast.LENGTH_SHORT).show()
            return
        }
        val output = editor.render()
        val stream = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        output.recycle()
        onSaved(stream.toByteArray())
        dismiss()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

private enum class EditMode { VIEW, RECTANGLE, REDACT }

private sealed class ScreenshotMark {
    data class Rectangle(val rect: RectF) : ScreenshotMark()
    data class Redaction(val path: Path) : ScreenshotMark()
}

private class ScreenshotEditorView(context: Context, private val bitmap: Bitmap) : View(context) {
    var mode: EditMode = EditMode.VIEW
    private val marks = mutableListOf<ScreenshotMark>()
    private var activeStart: Pair<Float, Float>? = null
    private var activeRect: RectF? = null
    private var activePath: Path? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val destination = RectF()
    private val rectanglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 59, 48)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val redactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 28f
    }

    init {
        setBackgroundColor(Color.rgb(10, 13, 18))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateMatrix()
        canvas.drawBitmap(bitmap, imageMatrix, null)
        canvas.save()
        canvas.concat(imageMatrix)
        marks.forEach { drawMark(canvas, it) }
        activeRect?.let { canvas.drawRect(it, rectanglePaintForBitmap()) }
        activePath?.let { canvas.drawPath(it, redactPaintForBitmap()) }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == EditMode.VIEW || !destination.contains(event.x, event.y)) return mode != EditMode.VIEW
        val point = floatArrayOf(event.x, event.y)
        inverseMatrix.mapPoints(point)
        val x = point[0].coerceIn(0f, bitmap.width.toFloat())
        val y = point[1].coerceIn(0f, bitmap.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                if (mode == EditMode.RECTANGLE) {
                    activeStart = x to y
                    activeRect = RectF(x, y, x, y)
                } else {
                    activePath = Path().apply { moveTo(x, y) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == EditMode.RECTANGLE) {
                    activeStart?.let { (sx, sy) -> activeRect?.set(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y)) }
                } else activePath?.lineTo(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeRect?.takeIf { it.width() > 3f && it.height() > 3f }?.let { marks += ScreenshotMark.Rectangle(RectF(it)) }
                activePath?.let { marks += ScreenshotMark.Redaction(Path(it)) }
                activeStart = null
                activeRect = null
                activePath = null
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        invalidate()
        return true
    }

    fun undo() {
        if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex)
        invalidate()
    }

    fun clear() {
        marks.clear()
        invalidate()
    }

    fun hasEdits() = marks.isNotEmpty()

    fun render(): Bitmap {
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        marks.forEach { drawMark(canvas, it) }
        return output
    }

    private fun drawMark(canvas: Canvas, mark: ScreenshotMark) {
        when (mark) {
            is ScreenshotMark.Rectangle -> canvas.drawRect(mark.rect, rectanglePaintForBitmap())
            is ScreenshotMark.Redaction -> canvas.drawPath(mark.path, redactPaintForBitmap())
        }
    }

    private fun rectanglePaintForBitmap() = Paint(rectanglePaint).apply {
        strokeWidth = (bitmap.width / 300f).coerceAtLeast(3f)
    }

    private fun redactPaintForBitmap() = Paint(redactPaint).apply {
        strokeWidth = (bitmap.width / 24f).coerceAtLeast(24f)
    }

    private fun updateMatrix() {
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f
        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        imageMatrix.invert(inverseMatrix)
        destination.set(dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale)
    }
}
