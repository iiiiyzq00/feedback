package com.feedback.childartdemo

import android.graphics.Color
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.feedback.sdk.FeedbackListener
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.PrefillData
import com.feedback.sdk.SubmitResult
import com.feedback.sdk.capture.AttachmentInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.MotionEvent

class MainActivity : AppCompatActivity() {
    private lateinit var canvas: DrawingView
    private lateinit var status: TextView
    private var selectedColorName = "PURPLE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPage())
        FeedbackSDK.setListener(object : FeedbackListener {
            override fun onQueued(clientFeedbackId: String) = showStatus("反馈已进入安全队列")
            override fun onAccepted(clientFeedbackId: String, receiptId: String) = showStatus("服务端已接收 · ${receiptId.take(8)}")
            override fun onPermanentFailure(clientFeedbackId: String, reasonCode: String) = showStatus("提交失败 · $reasonCode")
        })
        window.decorView.postDelayed({ handleBatchIntent(intent) }, 1_500L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleBatchIntent(intent) }
    }

    private fun buildPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(16), dp(22), dp(18))
        setBackgroundColor(Color.parseColor("#FFF8EE"))
        addView(header(), LinearLayout.LayoutParams(MATCH, dp(70)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toolbox(), LinearLayout.LayoutParams(dp(220), MATCH).apply { marginEnd = dp(16) })
            canvas = DrawingView(this@MainActivity).apply {
                background = rounded(Color.WHITE, 22)
                onStrokeFinished = {
                    status.text = "已画 ${strokeCount()} 笔 · 自动保存"
                    FeedbackSDK.recordHostLog("INFO", "drawing_stroke_saved", "stroke_count=${strokeCount()}")
                }
            }
            addView(canvas, LinearLayout.LayoutParams(0, MATCH, 1f))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text("🎨 童画星球", 25f, "#503B4D", true))
        addView(text("  今天画一只快乐的小动物吧！", 13f, "#9A758D"), LinearLayout.LayoutParams(0, WRAP, 1f))
        status = text("新画布已准备", 12f, "#75616F").apply {
            gravity = Gravity.CENTER; background = rounded(Color.parseColor("#FCE4EC"), 16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        addView(status)
        addView(action("反馈问题", "#FF7A68") { openFeedback() }, LinearLayout.LayoutParams(dp(132), dp(44)).apply { marginStart = dp(12) })
    }

    private fun toolbox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(18), dp(17), dp(16))
        background = rounded(Color.WHITE, 20)
        addView(text("我的画笔", 18f, "#503B4D", true))
        addView(text("选择喜欢的颜色", 11f, "#A08698").apply { setPadding(0, dp(3), 0, dp(14)) })
        listOf(
            "PURPLE" to "#5C6BC0", "CORAL" to "#FF7043", "SUN" to "#FBC02D",
            "GRASS" to "#43A047", "SKY" to "#29B6F6", "INK" to "#455A64"
        ).chunked(2).forEach { row ->
            addView(LinearLayout(this@MainActivity).apply {
                row.forEach { (name, color) ->
                    addView(View(this@MainActivity).apply {
                        background = rounded(Color.parseColor(color), 22)
                        setOnClickListener {
                            selectedColorName = name
                            canvas.brushColor = Color.parseColor(color)
                            status.text = "已选择 $name 画笔"
                            FeedbackSDK.recordHostLog("INFO", "brush_selected", "brush=$name")
                        }
                    }, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(12); bottomMargin = dp(12) })
                }
            })
        }
        addView(text("画笔粗细", 12f, "#75616F").apply { setPadding(0, dp(8), 0, dp(8)) })
        addView(LinearLayout(this@MainActivity).apply {
            addView(action("细", "#8D7A88") { canvas.brushWidth = 7f }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(7) })
            addView(action("粗", "#8D7A88") { canvas.brushWidth = 18f }, LinearLayout.LayoutParams(0, dp(42), 1f))
        })
        addView(Space(this@MainActivity), LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(action("清空画布", "#B0A2AB") {
            canvas.clearDrawing(); status.text = "画布已清空"
            FeedbackSDK.recordHostLog("INFO", "canvas_cleared")
        }, LinearLayout.LayoutParams(MATCH, dp(44)))
    }

    private fun openFeedback() {
        FeedbackSDK.recordHostLog("INFO", "embedded_feedback_opened", "module=DRAWING_CANVAS")
        FeedbackSDK.launch(this, PrefillData(
            packageName = packageName,
            appVersion = BuildConfig.VERSION_NAME,
            functionModule = "DRAWING_CANVAS",
            triggerType = "IN_APP_BUTTON",
            networkType = "WIFI",
            businessContext = mapOf(
                "data_classification" to "SIMULATED_CHILD_ART_DATA",
                "drawing_id" to "SIMULATED_DRAWING_001",
                "brush" to selectedColorName,
                "stroke_count" to canvas.strokeCount().toString(),
                "demo_scenario" to "EMBEDDED_SDK_REAL_DEVICE_VALIDATION"
            )
        ))
    }

    private fun handleBatchIntent(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.getBooleanExtra("open_feedback_demo", false)) {
            intent.removeExtra("open_feedback_demo")
            openFeedback()
            return
        }
        if (intent.getBooleanExtra("run_key_info_validation", false)) {
            intent.removeExtra("run_key_info_validation")
            FeedbackSDK.setDeviceSerialNumber(intent.getStringExtra("managed_device_sn"))
            intent.removeExtra("managed_device_sn")
            CoroutineScope(Dispatchers.IO).launch {
                val result = FeedbackSDK.submit(
                    description = "SDK key information validation",
                    prefill = PrefillData(
                        packageName = packageName,
                        appVersion = BuildConfig.VERSION_NAME,
                        functionModule = "DRAWING_CANVAS",
                        issueType = "功能异常",
                        triggerType = "DEBUG_KEY_INFO_VALIDATION",
                        networkType = "WIFI"
                    ),
                    contactAllowed = true
                )
                runOnUiThread { showStatus(if (result is SubmitResult.Success) "关键信息验证提交成功" else "关键信息验证已进入本地队列") }
            }
            return
        }
        if (intent.getBooleanExtra("run_device_validation_24", false)) {
            intent.removeExtra("run_device_validation_24")
            runDeviceValidation(24)
            return
        }
        if (intent.getBooleanExtra("run_device_validation_49", false)) {
            intent.removeExtra("run_device_validation_49")
            runDeviceValidation(49)
            return
        }
        if (!intent.getBooleanExtra("run_batch_validation", false)) return
        intent.removeExtra("run_batch_validation")
        val screenshot = capturePageJpeg()
        val descriptions = listOf(
            "画布首次打开时工具栏加载较慢",
            "切换粗画笔后绘制偶尔出现短暂卡顿",
            "选择天空蓝后状态提示颜色名称不直观",
            "连续绘制多条线时页面响应较慢",
            "清空画布按钮的位置不够容易发现",
            "快速切换画笔颜色时画布出现卡顿",
            "返回绘画页面后笔迹数量提示没有变化",
            "粗细画笔切换后预览内容显示错误",
            "横屏画布右侧边缘排版出现偏移",
            "点击反馈按钮后弹窗出现速度较慢"
        )
        CoroutineScope(Dispatchers.IO).launch {
            var success = 0
            descriptions.forEachIndexed { index, description ->
                FeedbackSDK.recordHostLog("INFO", "batch_validation_item", "app=child_art index=${index + 1}")
                val extra = if (index in setOf(2, 6, 9)) listOf(
                    AttachmentInput("SCREENSHOT", "image/jpeg", screenshot.copyOf())
                ) else emptyList()
                val result = FeedbackSDK.submit(description, PrefillData(
                    packageName = packageName,
                    appVersion = BuildConfig.VERSION_NAME,
                    functionModule = "DRAWING_CANVAS",
                    issueType = listOf("功能异常", "卡顿闪退", "内容错误")[index % 3],
                    triggerType = "DEBUG_BATCH_V2",
                    networkType = "WIFI",
                    businessContext = mapOf(
                        "data_classification" to "SIMULATED_CHILD_ART_DATA",
                        "batch_name" to "CHILD_ART_REALTIME_10",
                        "sequence" to (index + 1).toString()
                    )
                ), attachments = extra)
                if (result is SubmitResult.Success) success++
            }
            showStatus("批量验收完成 $success/10")
        }
    }

    private fun runDeviceValidation(total: Int) {
        val screenshotIndexes = setOf(8, 17, 26, 35, 44)
        val themes = listOf(
            "切换工具时出现短暂卡顿", "画笔状态刷新不及时", "画布提示与实际内容不一致",
            "横屏排版出现轻微偏移", "连续绘制响应较慢", "颜色提示不够清晰", "反馈入口响应较慢"
        )
        CoroutineScope(Dispatchers.IO).launch {
            var success = 0
            repeat(total) { index ->
                val screenshot = performDrawingOperation(index, index in screenshotIndexes)
                FeedbackSDK.recordHostLog("INFO", "device_validation_operation", "app=child_art sequence=${index + 1}")
                val result = FeedbackSDK.submit(
                    description = "儿童绘画真机操作${index + 1}：${themes[index % themes.size]}",
                    prefill = PrefillData(
                        packageName = packageName,
                        appVersion = BuildConfig.VERSION_NAME,
                        functionModule = "DRAWING_CANVAS",
                        issueType = listOf("功能异常", "卡顿闪退", "内容错误")[index % 3],
                        triggerType = "DEVICE_REAL_100",
                        networkType = "WIFI",
                        businessContext = mapOf(
                            "data_classification" to "SIMULATED_CHILD_ART_DATA",
                            "validation_run" to "DEVICE_REAL_100",
                            "sequence" to (index + 1).toString(),
                            "stroke_count" to (index + 1).toString()
                        )
                    ),
                    attachments = screenshot?.let { listOf(AttachmentInput("SCREENSHOT", "image/jpeg", it)) }.orEmpty()
                )
                if (result is SubmitResult.Success) success++
            }
            showStatus("真机操作批次完成 $success/$total")
        }
    }

    private fun performDrawingOperation(index: Int, capture: Boolean): ByteArray? {
        val latch = CountDownLatch(1)
        var screenshot: ByteArray? = null
        runOnUiThread {
            val colors = intArrayOf(0xFF5C6BC0.toInt(), 0xFFFF7043.toInt(), 0xFFFBC02D.toInt(), 0xFF43A047.toInt(), 0xFF29B6F6.toInt())
            canvas.brushColor = colors[index % colors.size]
            val width = canvas.width.coerceAtLeast(600).toFloat()
            val height = canvas.height.coerceAtLeast(400).toFloat()
            val x1 = 40f + (index * 29 % (width.toInt() - 120))
            val y1 = 40f + (index * 37 % (height.toInt() - 120))
            val now = System.currentTimeMillis()
            canvas.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1, y1, 0))
            canvas.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, x1 + 70f, y1 + 45f, 0))
            canvas.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x1 + 110f, y1 + 70f, 0))
            window.decorView.post {
                if (capture) screenshot = capturePageJpeg()
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "drawing operation render timeout" }
        return screenshot
    }

    private fun capturePageJpeg(): ByteArray {
        val root = window.decorView.rootView
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun showStatus(message: String) = runOnUiThread {
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    private fun action(label: String, color: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD); background = rounded(Color.parseColor(color), 13); setOnClickListener { onClick() }
    }
    private fun text(value: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.parseColor(color)); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
