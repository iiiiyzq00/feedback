package com.feedback.demo

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.feedback.demo.universal.ForegroundAppDetector
import com.feedback.demo.universal.ScreenCapturePermissionActivity
import com.feedback.demo.universal.ScreenCaptureSession
import com.feedback.demo.universal.UniversalFeedbackLauncher
import com.feedback.demo.universal.UniversalFeedbackOverlayService

/** 独立通用反馈 App 的生产入口。 */
class HomeActivity : AppCompatActivity() {
    private lateinit var captureStatus: TextView
    private var developerTapCount = 0
    private var lastDeveloperTapAt = 0L
    private val captureListener: (ScreenCaptureSession.State) -> Unit = { runOnUiThread { renderCaptureStatus(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "通用反馈"
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        ScreenCaptureSession.addListener(captureListener)
    }

    override fun onStop() {
        ScreenCaptureSession.removeListener(captureListener)
        super.onStop()
    }

    private fun buildContent() = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Color.rgb(244, 247, 252))
        addView(LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(26))
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@HomeActivity).apply {
                    text = "?"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    background = card(Color.rgb(35, 112, 232), 14)
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(13) })
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("反馈中心", 24f, Color.rgb(24, 38, 62), true))
                    addView(text("选择应用，快速反馈", 13f, Color.rgb(112, 126, 148)))
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            })

            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(15), dp(18), dp(16))
                background = card(Color.WHITE, 14)
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("跨应用反馈", 16f, Color.rgb(30, 45, 67), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    captureStatus = text("", 12f, Color.DKGRAY)
                    captureStatus.contentDescription = "截图授权状态"
                    captureStatus.setPadding(dp(12), dp(8), dp(4), dp(8))
                    captureStatus.setOnClickListener {
                        if (!BuildConfig.DEBUG || ScreenCaptureSession.state != ScreenCaptureSession.State.READY) {
                            developerTapCount = 0
                            return@setOnClickListener
                        }
                        val now = System.currentTimeMillis()
                        developerTapCount = if (now - lastDeveloperTapAt <= 3_000L) developerTapCount + 1 else 1
                        lastDeveloperTapAt = now
                        if (developerTapCount >= 8) {
                            developerTapCount = 0
                            startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                        }
                    }
                    addView(captureStatus)
                })
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(smallAction("开启悬浮入口") { enableOverlay() },
                        LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(10) })
                    addView(smallAction("授权截图") {
                        startActivity(Intent(this@HomeActivity, ScreenCapturePermissionActivity::class.java))
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(13)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(20)
            })

            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(section("选择应用"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("点击“反馈”提交问题", 12f, Color.rgb(125, 137, 155)))
            })
            val apps = installedLearningApps()
            if (apps.isEmpty()) addView(text("暂无可反馈应用", 14f, Color.GRAY))
            apps.forEach { app -> addView(appRow(app)) }

        })
    }

    private fun enableOverlay() {
        if (!ForegroundAppDetector.hasUsageAccess(this)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "请允许通用反馈读取前台应用", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "请允许显示悬浮反馈入口", Toast.LENGTH_LONG).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, UniversalFeedbackOverlayService::class.java))
        Toast.makeText(this, "悬浮反馈入口已开启", Toast.LENGTH_SHORT).show()
    }

    private fun installedLearningApps(): List<LearningApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg !in LEARNING_APP_PACKAGES) null else LearningApp(
                    label = info.loadLabel(packageManager).toString(),
                    packageName = pkg,
                    version = runCatching { packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull(),
                    icon = info.loadIcon(packageManager)
                )
            }.distinctBy { it.packageName }.sortedBy { it.label }
    }

    private fun appRow(app: LearningApp) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(12), dp(12), dp(12))
        background = strokedCard(Color.WHITE, Color.rgb(231, 236, 244), 13)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(ImageView(this@HomeActivity).apply {
            setImageDrawable(app.icon)
            contentDescription = "${app.label}图标"
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(13) })
        addView(LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(app.label, 16f, Color.rgb(30, 42, 61), true))
            app.version?.let { addView(text("版本 $it", 11f, Color.rgb(132, 143, 159))) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(outlineAction("打开") {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch != null) startActivity(launch) else Toast.makeText(this@HomeActivity, "该应用无法启动", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(72), dp(40)).apply { marginEnd = dp(8) })
        addView(smallAction("反馈") {
            UniversalFeedbackLauncher.open(
                this@HomeActivity,
                ForegroundAppDetector(this@HomeActivity).selectedApp(app.packageName),
                captureScreen = false
            )
        }, LinearLayout.LayoutParams(dp(72), dp(40)))
    }

    private fun renderCaptureStatus(state: ScreenCaptureSession.State) {
        if (!::captureStatus.isInitialized) return
        captureStatus.text = when (state) {
            ScreenCaptureSession.State.READY -> "● 截图已就绪"
            ScreenCaptureSession.State.STARTING -> "● 授权中"
            ScreenCaptureSession.State.STOPPED -> "○ 截图未授权"
        }
        captureStatus.setTextColor(if (state == ScreenCaptureSession.State.READY) Color.rgb(24, 130, 78) else Color.DKGRAY)
    }

    private fun section(value: String) = text(value, 18f, Color.rgb(24, 38, 62), true).pad(bottom = 10)
    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value; setTextColor(Color.WHITE); textSize = 14f; background = card(Color.rgb(22, 119, 255), 9)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(10) }
    }
    private fun smallAction(value: String, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; minHeight = 0; minWidth = 0; setTextColor(Color.WHITE); background = card(Color.rgb(35, 112, 232), 9); setOnClickListener { click() }
    }
    private fun outlineAction(value: String, click: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; minHeight = 0; minWidth = 0
        setTextColor(Color.rgb(46, 91, 158)); background = strokedCard(Color.WHITE, Color.rgb(199, 213, 234), 9)
        setOnClickListener { click() }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD); gravity = Gravity.START
    }
    private fun TextView.pad(top: Int = 0, bottom: Int = 0) = apply { setPadding(0, dp(top), 0, dp(bottom)) }
    private fun card(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun strokedCard(color: Int, stroke: Int, radius: Int) = card(color, radius).apply { setStroke(dp(1), stroke) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private data class LearningApp(
        val label: String,
        val packageName: String,
        val version: String?,
        val icon: android.graphics.drawable.Drawable
    )

    companion object {
        private val LEARNING_APP_PACKAGES = setOf(
            "com.seewo.studystation.microcourse", "com.seewo.studystation.wrongbook",
            "com.seewo.studystation.questionsearch", "com.seewo.studystation.bookgallery",
            "com.seewo.studystation.chinese", "com.seewo.studystation.ai.partner",
            "com.seewo.studystation.cnpicbook", "com.seewo.studystation.english",
            "com.seewo.studystation.enlightenment", "com.seewo.studystation.mathbeginner",
            "com.seewo.studystation.pinyin", "com.seewo.eclass.enarticleanalysis"
        )
    }
}
