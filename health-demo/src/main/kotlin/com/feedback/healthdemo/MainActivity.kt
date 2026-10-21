package com.feedback.healthdemo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var currentModule = Module.OVERVIEW
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logHandler = Handler(Looper.getMainLooper())
    private val logHeartbeat = object : Runnable {
        override fun run() {
            FeedbackSDK.recordHostLog("INFO", "health_demo_heartbeat", "screen=${currentModule.sdkModule}")
            logHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPage())
        FeedbackSDK.setListener(object : FeedbackListener {
            override fun onQueued(clientFeedbackId: String) = updateStatus("已进入安全上传队列 · ${clientFeedbackId.take(8)}")
            override fun onAccepted(clientFeedbackId: String, receiptId: String) =
                updateStatus("服务端已接收 · ${receiptId.take(8)}")
            override fun onPermanentFailure(clientFeedbackId: String, reasonCode: String) =
                updateStatus("上报失败 · $reasonCode")
        })
        FeedbackSDK.recordHostLog("INFO", "health_demo_opened", "version=${BuildConfig.VERSION_NAME}")
        logHandler.postDelayed(logHeartbeat, 30_000L)
        window.decorView.postDelayed({ handleBatchIntent(intent) }, 1_500L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleBatchIntent(intent) }
    }

    override fun onDestroy() {
        logHandler.removeCallbacks(logHeartbeat)
        super.onDestroy()
    }

    private fun buildPage(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        setBackgroundColor(rgb("F3F7F6"))
        addView(sidebar(), LinearLayout.LayoutParams(dp(220), MATCH).apply { marginEnd = dp(18) })
        addView(dashboard(), LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    private fun sidebar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(20), dp(18), dp(18))
        background = shape(Color.WHITE, 20)
        addView(text("轻养健康", 23f, rgb("183B37"), true))
        addView(text("今日状态不错", 12f, rgb("72908B")).apply { setPadding(0, dp(4), 0, dp(24)) })
        Module.entries.forEach { module ->
            addView(text(module.label, 15f, if (module == currentModule) Color.WHITE else rgb("496762"), module == currentModule).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, 0, 0)
                background = shape(if (module == currentModule) rgb("2D7C74") else Color.TRANSPARENT, 12)
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    currentModule = module
                    FeedbackSDK.recordHostLog("INFO", "health_module_opened", "module=${module.sdkModule}")
                    setContentView(buildPage())
                }
            })
        }
        addView(Space(this@MainActivity), LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(text("演示账号", 11f, rgb("89A09C")))
        addView(text("SIMULATED_HEALTH_USER_001", 10f, rgb("496762"), true))
    }

    private fun dashboard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header())
        addView(ScrollView(this@MainActivity).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(metricRow())
                addView(healthInsight())
            })
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(currentModule.title, 27f, rgb("183B37"), true))
            addView(text("7月31日 · 数据为演示数据", 12f, rgb("72908B")))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        status = text("SDK 已就绪", 12f, rgb("496762")).apply {
            gravity = Gravity.CENTER
            background = shape(rgb("DDEEEB"), 16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        addView(status)
        addView(button("补传历史") { retryHistorical() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(44)).apply { marginStart = dp(12) }
        })
        addView(button("反馈问题") { openFeedback() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(44)).apply { marginStart = dp(12) }
        })
    }

    private fun metricRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(18), 0, dp(14))
        listOf(
            Metric("今日步数", "7,842", "步", "目标完成 78%", "4FAE9D"),
            Metric("静息心率", "72", "次/分", "处于正常范围", "EF8B72"),
            Metric("昨夜睡眠", "7.4", "小时", "深睡 2.1 小时", "748AC7"),
            Metric("今日饮水", "1,350", "毫升", "还需 650 毫升", "54A9C6")
        ).forEachIndexed { index, item ->
            addView(metricCard(item), LinearLayout.LayoutParams(0, dp(174), 1f).apply {
                if (index > 0) marginStart = dp(12)
            })
        }
    }

    private fun metricCard(metric: Metric) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(15))
        background = shape(Color.WHITE, 18)
        addView(text(metric.name, 13f, rgb("66817C")))
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.BOTTOM
            addView(text(metric.value, 31f, rgb("183B37"), true))
            addView(text(metric.unit, 11f, rgb("72908B")).apply { setPadding(dp(6), 0, 0, dp(6)) })
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(View(this@MainActivity).apply { background = shape(rgb(metric.color), 3) }, LinearLayout.LayoutParams(MATCH, dp(5)))
        addView(text(metric.note, 11f, rgb("72908B")).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun healthInsight() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(22), dp(20), dp(22), dp(20))
        background = shape(Color.WHITE, 18)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("健康趋势", 18f, rgb("183B37"), true))
            addView(text("近 7 天活动量", 12f, rgb("72908B")).apply { setPadding(0, dp(3), 0, dp(16)) })
            val values = listOf(44, 68, 55, 82, 74, 91, 78)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.BOTTOM
                values.forEachIndexed { index, value ->
                    addView(View(this@MainActivity).apply { background = shape(rgb(if (index == 6) "2D7C74" else "B8D8D2"), 7) },
                        LinearLayout.LayoutParams(0, dp(value), 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
                }
            }, LinearLayout.LayoutParams(MATCH, dp(100)))
            addView(text("一       二       三       四       五       六       日", 11f, rgb("89A09C")).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(0, MATCH, 1.35f).apply { marginEnd = dp(28) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(17))
            background = shape(rgb("EEF7F5"), 15)
            addView(text("今日建议", 17f, rgb("205B54"), true))
            addView(text("你的久坐时间略长，建议下午安排一次 15 分钟轻量步行。", 14f, rgb("496762")).apply {
                setPadding(0, dp(12), 0, dp(12)); setLineSpacing(0f, 1.25f)
            })
            addView(text("所有指标均为 SIMULATED_ 演示数据", 10f, rgb("72908B")))
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    private fun openFeedback() {
        FeedbackSDK.recordHostLog("INFO", "embedded_feedback_opened", "module=${currentModule.sdkModule}")
        FeedbackSDK.launch(this, PrefillData(
            packageName = packageName,
            appVersion = BuildConfig.VERSION_NAME,
            functionModule = currentModule.sdkModule,
            triggerType = "IN_APP_BUTTON",
            networkType = networkType(),
            inputMethods = listOf("TEXT", "SCREENSHOT"),
            businessContext = mapOf(
                "data_classification" to "SIMULATED_HEALTH_DATA",
                "screen_code" to currentModule.name,
                "health_profile_id" to "SIMULATED_PROFILE_001",
                "metric_scope" to currentModule.metricScope,
                "demo_scenario" to "EMBEDDED_SDK_REAL_DEVICE_VALIDATION"
            )
        ))
    }

    private fun retryHistorical() {
        updateStatus("正在检查本地历史队列")
        CoroutineScope(Dispatchers.IO).launch {
            val count=runCatching { FeedbackSDK.retryHistoricalFailures() }.getOrElse {
                runOnUiThread { updateStatus("历史补传启动失败") };return@launch
            }
            runOnUiThread { updateStatus(if(count>0) "已重新排队 $count 条历史反馈" else "没有可补传的历史反馈") }
        }
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
                        functionModule = "HEALTH_OVERVIEW",
                        issueType = "功能异常",
                        triggerType = "DEBUG_KEY_INFO_VALIDATION",
                        networkType = networkType()
                    ),
                    contactAllowed = true
                )
                runOnUiThread { updateStatus(if (result is SubmitResult.Success) "关键信息验证提交成功" else "关键信息验证已进入本地队列") }
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
            "健康总览首次打开时卡片加载较慢",
            "切换到运动记录后偶尔出现短暂卡顿",
            "今日步数卡片与趋势图展示不一致",
            "睡眠分析页面返回总览时响应较慢",
            "身体指标模块的说明文字不够清晰",
            "连续切换健康模块时页面出现卡顿",
            "饮水数据刷新后提示没有及时变化",
            "健康建议卡片偶尔显示内容错误",
            "总览页面截图区域排版出现偏移",
            "点击反馈按钮后弹窗出现速度较慢"
        )
        CoroutineScope(Dispatchers.IO).launch {
            var success = 0
            descriptions.forEachIndexed { index, description ->
                FeedbackSDK.recordHostLog("INFO", "batch_validation_item", "app=health index=${index + 1}")
                val extra = if (index in setOf(1, 5, 8)) listOf(
                    AttachmentInput("SCREENSHOT", "image/jpeg", screenshot.copyOf())
                ) else emptyList()
                val result = FeedbackSDK.submit(description, PrefillData(
                    packageName = packageName,
                    appVersion = BuildConfig.VERSION_NAME,
                    functionModule = "HEALTH_OVERVIEW",
                    issueType = listOf("功能异常", "卡顿闪退", "内容错误")[index % 3],
                    triggerType = "DEBUG_BATCH_V2",
                    networkType = networkType(),
                    businessContext = mapOf(
                        "data_classification" to "SIMULATED_HEALTH_DATA",
                        "batch_name" to "HEALTH_REALTIME_10",
                        "sequence" to (index + 1).toString()
                    )
                ), attachments = extra)
                if (result is SubmitResult.Success) success++
            }
            updateStatus("批量验收完成 $success/10")
        }
    }

    private fun runDeviceValidation(total: Int) {
        val screenshotIndexes = setOf(8, 17, 26, 35, 44)
        val themes = listOf(
            "切换模块时出现短暂卡顿", "卡片数据刷新不及时", "趋势图与卡片内容不一致",
            "横屏排版出现轻微偏移", "返回总览响应较慢", "状态提示不够清晰", "反馈入口响应较慢"
        )
        CoroutineScope(Dispatchers.IO).launch {
            var success = 0
            repeat(total) { index ->
                val screenshot = performHealthOperation(index, index in screenshotIndexes)
                FeedbackSDK.recordHostLog("INFO", "device_validation_operation", "app=health sequence=${index + 1}")
                val result = FeedbackSDK.submit(
                    description = "健康管理真机操作${index + 1}：${themes[index % themes.size]}",
                    prefill = PrefillData(
                        packageName = packageName,
                        appVersion = BuildConfig.VERSION_NAME,
                        functionModule = Module.entries[index % Module.entries.size].sdkModule,
                        issueType = listOf("功能异常", "卡顿闪退", "内容错误")[index % 3],
                        triggerType = "DEVICE_REAL_100",
                        networkType = networkType(),
                        businessContext = mapOf(
                            "data_classification" to "SIMULATED_HEALTH_DATA",
                            "validation_run" to "DEVICE_REAL_100",
                            "sequence" to (index + 1).toString()
                        )
                    ),
                    attachments = screenshot?.let { listOf(AttachmentInput("SCREENSHOT", "image/jpeg", it)) }.orEmpty()
                )
                if (result is SubmitResult.Success) success++
            }
            updateStatus("真机操作批次完成 $success/$total")
        }
    }

    private fun performHealthOperation(index: Int, capture: Boolean): ByteArray? {
        val latch = CountDownLatch(1)
        var screenshot: ByteArray? = null
        runOnUiThread {
            currentModule = Module.entries[index % Module.entries.size]
            setContentView(buildPage())
            window.decorView.post {
                if (capture) screenshot = capturePageJpeg()
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "health operation render timeout" }
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

    private fun networkType(): String {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "OFFLINE"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    private fun updateStatus(message: String) = runOnUiThread {
        if (::status.isInitialized) status.text = "${timeFormat.format(Date())}  $message"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun button(label: String, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD); background = shape(rgb("2D7C74"), 13); setOnClickListener { action() }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun rgb(hex: String) = Color.parseColor("#$hex")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class Metric(val name: String, val value: String, val unit: String, val note: String, val color: String)
    private enum class Module(val label: String, val title: String, val sdkModule: String, val metricScope: String) {
        OVERVIEW("健康总览", "早上好，演示用户", "HEALTH_OVERVIEW", "STEPS,HEART_RATE,SLEEP,HYDRATION"),
        ACTIVITY("运动记录", "运动与活动", "ACTIVITY_RECORDS", "STEPS,ACTIVE_MINUTES"),
        VITALS("身体指标", "身体指标", "HEALTH_VITALS", "HEART_RATE,WEIGHT"),
        SLEEP("睡眠分析", "睡眠分析", "SLEEP_ANALYSIS", "SLEEP_DURATION,SLEEP_STAGE")
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
