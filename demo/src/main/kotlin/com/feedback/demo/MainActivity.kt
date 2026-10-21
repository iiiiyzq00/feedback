package com.feedback.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.feedback.sdk.FeedbackListener
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.PrefillData
import com.feedback.sdk.config.SDKConfig
import com.feedback.sdk.diagnostics.CompatibilityDiagnostics
import com.feedback.demo.universal.ForegroundAppDetector
import com.feedback.demo.universal.SdkUniversalFeedbackGateway
import com.feedback.demo.universal.UniversalFeedbackContext
import com.feedback.demo.universal.UniversalFeedbackGateway
import com.feedback.demo.universal.UniversalFeedbackOverlayService
import com.feedback.demo.universal.UniversalLaunchRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 横屏学习机业务场景验证壳：用于验证通用反馈 SDK，而非生产业务实现。 */
class MainActivity : AppCompatActivity() {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private lateinit var content: LinearLayout
    private lateinit var logView: TextView
    private lateinit var nav: LinearLayout
    private lateinit var universalGateway: UniversalFeedbackGateway
    private var selected = Scenario.COURSE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildShell())
        showScenario(selected)
        universalGateway = SdkUniversalFeedbackGateway(
            serverUrl = BuildConfig.SDK_SERVER_URL,
            appSecret = BuildConfig.COURSE_SDK_SECRET,
            serverPublicKeyPem = TransportPublicKey.pemOrNull(),
            allowInsecurePrivateNetworkForTesting = BuildConfig.DEBUG
        )
        FeedbackSDK.setListener(object : FeedbackListener {
            override fun onQueued(clientFeedbackId: String) = log("已入队 ${clientFeedbackId.take(8)}")
            override fun onAccepted(clientFeedbackId: String, receiptId: String) =
                log("上报成功 ${receiptId.take(8)}")
            override fun onPermanentFailure(clientFeedbackId: String, reasonCode: String) =
                log("上报失败 $reasonCode")
        })
        window.decorView.post { handleUniversalIntent(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        window.decorView.post { handleUniversalIntent(intent) }
    }

    override fun onResume() {
        super.onResume()
        startOverlayIfPermitted()
    }

    private fun buildShell() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.rgb(244, 247, 251))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(buildNavigation(), LinearLayout.LayoutParams(0, MATCH, 2.2f).apply {
            marginEnd = dp(14)
        })
        content = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = card(Color.WHITE, 14)
        }
        addView(ScrollView(this@MainActivity).apply { addView(content) },
            LinearLayout.LayoutParams(0, MATCH, 5.2f).apply { marginEnd = dp(14) })
        addView(buildLogPanel(), LinearLayout.LayoutParams(0, MATCH, 2.6f))
    }

    private fun buildNavigation() = LinearLayout(this).apply {
        nav = this
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(16), dp(14), dp(16))
        background = card(Color.WHITE, 14)
        addView(label("开发验证", 20f, Color.rgb(24, 38, 62), true))
        addView(label("真实 App · SDK 链路", 11f, Color.rgb(112, 126, 148)).apply {
            setPadding(0, dp(3), 0, dp(14))
        })
        Scenario.entries.forEach { scenario ->
            addView(TextView(this@MainActivity).apply {
                tag = "nav_${scenario.name}"
                text = scenario.title
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.rgb(54, 69, 91))
                setPadding(dp(12), 0, dp(8), 0)
                background = card(Color.rgb(247, 249, 253), 9)
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(48)).apply { bottomMargin = dp(7) }
                setOnClickListener { showScenario(scenario) }
            })
        }
        addView(Space(this@MainActivity), LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(label("Debug · 真机验证", 10f, Color.rgb(112, 126, 148)))
    }

    private fun buildLogPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = card(Color.WHITE, 14)
        addView(label("SDK 验证日志", 16f, Color.rgb(30, 42, 61), true))
        addView(label("截图在弹窗前采集；录屏时隐藏弹窗", 10f, Color.GRAY).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        logView = label("等待操作…\n", 11f, Color.DKGRAY).apply {
            typeface = Typeface.MONOSPACE
        }
        addView(ScrollView(this@MainActivity).apply { addView(logView) },
            LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(action("启用全局反馈入口", Color.rgb(22, 119, 255)) {
            enableUniversalEntry()
        }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8) })
        addView(action("运行兼容性诊断", Color.rgb(69, 102, 145)) {
            CoroutineScope(Dispatchers.IO).launch {
                CompatibilityDiagnostics.run(
                    this@MainActivity,
                    "${BuildConfig.SDK_SERVER_URL}/actuator/health"
                ).forEach { item -> log("${if (item.ok) "✓" else "!"} ${item.name}: ${item.detail}") }
            }
        })
    }

    private fun enableUniversalEntry() {
        if (!ForegroundAppDetector.hasUsageAccess(this)) {
            log("需要开启“使用情况访问权限”以识别前台 App")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            log("需要开启悬浮窗权限以显示全局入口")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startOverlayIfPermitted()
        log("全局反馈入口已启用")
        Toast.makeText(this, "全局反馈入口已启用", Toast.LENGTH_SHORT).show()
    }

    private fun startOverlayIfPermitted() {
        if (Settings.canDrawOverlays(this) && ForegroundAppDetector.hasUsageAccess(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, UniversalFeedbackOverlayService::class.java)
            )
        }
    }

    private fun handleUniversalIntent(intent: Intent) {
        if (intent.action != ACTION_OPEN_UNIVERSAL_FEEDBACK && intent.data?.scheme != "feedback") return
        val detector = ForegroundAppDetector(this)
        val context = UniversalLaunchRegistry.consume(intent.getStringExtra(EXTRA_INTERNAL_LAUNCH_TOKEN))
            ?: detector.detect(excludedPackages = setOf(packageName))
            ?: detector.untrustedHint(intent.data?.getQueryParameter(EXTRA_SOURCE_PACKAGE))
        if (context == null) {
            log("无法可靠识别来源 App；请开启使用情况访问权限后重试")
            Toast.makeText(this, "无法识别来源 App", Toast.LENGTH_LONG).show()
            return
        }
        log("统一入口：${context.sourceAppName ?: context.sourcePackage}")
        log("来源：${context.sourcePackage} · ${context.detectionMethod} · trusted=${context.trustedSource}")
        universalGateway.openFeedback(this, context)
        intent.action = null
        intent.data = null
    }

    private fun showScenario(scenario: Scenario) {
        selected = scenario
        content.removeAllViews()
        Scenario.entries.forEach {
            nav.findViewWithTag<TextView>("nav_${it.name}")?.apply {
                background = card(if (it == scenario) Color.rgb(225, 239, 255) else Color.rgb(247, 249, 253), 9)
                setTextColor(if (it == scenario) Color.rgb(26, 103, 205) else Color.rgb(54, 69, 91))
                setTypeface(typeface, if (it == scenario) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        val app = resolveRealApp(scenario)
        content.addView(label(scenario.title, 24f, Color.rgb(24, 38, 62), true))
        content.addView(label("验证设备中真实安装的应用与既有 SDK/后端链路", 12f, Color.GRAY).apply {
            setPadding(0, dp(5), 0, dp(16))
        })
        if (app == null) {
            content.addView(verticalCard("未找到可验证的真实应用", "请先在学习机安装对应应用"))
            log("${scenario.title}：未发现真实 App")
            return
        }
        content.addView(realAppCard(app))
        content.addView(Space(this), LinearLayout.LayoutParams(MATCH, dp(16)))
        content.addView(action("打开真实 App", Color.rgb(46, 91, 158)) {
            packageManager.getLaunchIntentForPackage(app.packageName)?.let(::startActivity)
                ?: Toast.makeText(this, "应用无法启动", Toast.LENGTH_SHORT).show()
        }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(9) })
        content.addView(action("验证反馈与后端链路", Color.rgb(35, 112, 232)) { launchFeedback(scenario, app) })
        content.addView(label(
            "使用既有 ${scenario.appKey} 配置；真实包名和版本进入加密载荷。",
            10f, Color.GRAY
        ).apply { setPadding(0, dp(8), 0, 0) })
        log("真实 App：${app.label} · ${app.packageName}")
    }

    private fun realAppCard(app: RealApp) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = strokedCard(Color.rgb(249, 251, 254), Color.rgb(226, 233, 243), 12)
        addView(ImageView(this@MainActivity).apply { setImageDrawable(app.icon) },
            LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(16) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(app.label, 18f, Color.rgb(30, 42, 61), true))
            addView(label(app.packageName, 11f, Color.rgb(112, 126, 148)).apply { setPadding(0, dp(4), 0, 0) })
            addView(label("版本 ${app.version ?: "未知"}", 11f, Color.rgb(112, 126, 148)))
        })
    }

    private fun verticalCard(vararg lines: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = card(Color.rgb(247, 249, 253), 10)
        lines.forEachIndexed { index, text ->
            addView(label(text, if (index == 0) 17f else 13f,
                if (index == 0) Color.rgb(30, 42, 61) else Color.rgb(75, 86, 105), index == 0
            ).apply { setPadding(0, dp(7), 0, dp(7)) })
        }
    }

    private fun launchFeedback(scenario: Scenario, app: RealApp) {
        FeedbackSDK.init(application, SDKConfig(
            appId = scenario.appKey,
            appKey = scenario.appKey,
            appSecret = scenario.secret(),
            serverUrl = BuildConfig.SDK_SERVER_URL,
            serverPublicKeyPem = TransportPublicKey.pemOrNull(),
            allowInsecurePrivateNetworkForTesting = BuildConfig.DEBUG
        ))
        FeedbackSDK.launch(this, PrefillData(
            packageName = app.packageName,
            appVersion = app.version,
            functionModule = scenario.module,
            triggerType = "LAUNCHER",
            networkType = "WIFI",
            businessContext = scenario.context
        ))
        log("打开反馈：${scenario.module}")
    }

    private fun resolveRealApp(scenario: Scenario): RealApp? {
        val installed = installedRealApps()
        return installed.firstOrNull { it.packageName in scenario.preferredPackages }
            ?: installed.getOrNull(scenario.ordinal % installed.size.coerceAtLeast(1))
    }

    private fun installedRealApps(): List<RealApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0).mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (pkg == packageName ||
                !(pkg.startsWith("com.seewo.studystation.") || pkg.startsWith("com.seewo.eclass."))
            ) null else RealApp(
                label = info.loadLabel(packageManager).toString(),
                packageName = pkg,
                version = runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull(),
                icon = info.loadIcon(packageManager)
            )
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }

    private fun action(text: String, color: Int, click: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        background = card(color, 8)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48))
        setOnClickListener { click() }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun strokedCard(color: Int, stroke: Int, radius: Int) = card(color, radius).apply {
        setStroke(dp(1), stroke)
    }

    private data class RealApp(
        val label: String,
        val packageName: String,
        val version: String?,
        val icon: android.graphics.drawable.Drawable
    )

    private fun log(message: String) = runOnUiThread {
        if (::logView.isInitialized) {
            logView.append("${timeFormat.format(Date())}  $message\n")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private enum class Scenario(
        val icon: String,
        val title: String,
        val subtitle: String,
        val appKey: String,
        val module: String,
        val context: Map<String, String>,
        val preferredPackages: Set<String>
    ) {
        COURSE("▶", "课程链路", "真实课程应用", "course", "COURSE_PLAYBACK",
            mapOf("course_id" to "course-demo-101", "lesson_id" to "lesson-03", "playback_second" to "1122", "validation" to "real_app"),
            setOf("com.seewo.studystation.microcourse")),
        QUESTION_BANK("✎", "题库链路", "真实题库应用", "question-bank", "QUESTION_DETAIL",
            mapOf("question_id" to "question-demo-12", "subject" to "MATH", "answer_stage" to "ANSWERING", "validation" to "real_app"),
            setOf("com.seewo.studystation.questionsearch")),
        WRONG_BOOK("✓", "错题本链路", "真实错题本应用", "wrong-book", "WRONG_BOOK_CORRECTION",
            mapOf("wrong_item_id" to "wrong-demo-08", "correction_status" to "PENDING", "validation" to "real_app"),
            setOf("com.seewo.studystation.wrongbook")),
        AI_TUTOR("✦", "AI 助教链路", "真实 AI 应用", "ai-tutor", "AI_TUTOR_CHAT",
            mapOf("trace_id" to "trace-demo-203", "model_version" to "edu-v2", "turn" to "3", "validation" to "real_app"),
            setOf("com.seewo.studystation.ai.partner")),
        APP_STORE("▣", "应用商店链路", "真实应用商店", "app-store", "APP_STORE_DOWNLOAD",
            mapOf("application_id" to "geometry-board", "target_version" to "2.3.0", "download_stage" to "DOWNLOADING", "validation" to "real_app"),
            setOf("com.seewo.studystation.update", "com.seewo.studystation.appstore"));

        fun secret(): String = when (this) {
            COURSE -> BuildConfig.COURSE_SDK_SECRET
            QUESTION_BANK -> BuildConfig.QUESTION_BANK_SDK_SECRET
            WRONG_BOOK -> BuildConfig.WRONG_BOOK_SDK_SECRET
            AI_TUTOR -> BuildConfig.AI_TUTOR_SDK_SECRET
            APP_STORE -> BuildConfig.APP_STORE_SDK_SECRET
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val ACTION_OPEN_UNIVERSAL_FEEDBACK = "com.feedback.action.OPEN_UNIVERSAL_FEEDBACK"
        const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
        const val EXTRA_INTERNAL_LAUNCH_TOKEN = "internalLaunchToken"
    }
}
