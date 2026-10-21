package com.feedback.sdk.ui

import android.app.Dialog
import android.app.ActivityManager
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.PrefillData
import com.feedback.sdk.ScreenshotRecaptureHandler
import com.feedback.sdk.ScreenRecordingHandler
import com.feedback.sdk.FeedbackDraft
import com.feedback.sdk.RecordingRecoveryHandler
import com.feedback.sdk.SubmitResult
import com.feedback.sdk.capture.AttachmentInput
import com.feedback.sdk.capture.SdkLogBuffer
import com.feedback.sdk.capture.ScreenRecordingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 反馈弹窗 — 适配学习机横屏场景
 *
 * 横屏（学习机默认）：居中弹窗，背景保留当前业务页面 + 深色遮罩
 * 窄屏（竖屏手机）：底部弹出（Bottom Sheet 风格）
 *
 * 界面组成（3.12.2a）：
 * 1. 问题类型（必选，单选标签，由服务端动态配置）
 * 2. 问题描述（文本 + 语音共用编辑区）
 * 3. 可选附件（截图/录屏按需选择；打开窗口前 3 分钟日志在后台默认附加）
 * 4. 提交状态提示（已提交/已保存待重试/永久失败）
 */
class FeedbackDialog(
    private val hostActivity: Activity,
    private val prefill: PrefillData? = null,
    capturedScreenshot: ByteArray? = null,
    private val screenshotRecaptureHandler: ScreenshotRecaptureHandler? = null,
    private val screenRecordingHandler: ScreenRecordingHandler? = null,
    private val initialDraft: FeedbackDraft? = null,
    private val recordingRecoveryHandler: RecordingRecoveryHandler? = null,
    private val frozenDiagnosticLog: ByteArray = SdkLogBuffer.snapshot(),
    private val onClosed: (() -> Unit)? = null
) : Dialog(hostActivity) {

    // 状态
    private var selectedIssueType: String? = initialDraft?.issueType ?: prefill?.issueType
    private val issueTypeTags = mutableListOf<TextView>()
    private var attachScreenshot = initialDraft?.attachScreenshot ?: false
    private var capturedScreenshot: ByteArray? = initialDraft?.screenshot ?: capturedScreenshot
    private var screenshotHandedToSubmit = false
    private var temporarilyRemoved = false
    private var hostTaskMovedBehind = false
    private var dialogWindowDetached = false
    private var screenshotContainer: LinearLayout? = null
    private var attachRecording = initialDraft?.attachRecording ?: false
    private var recordingBytes: ByteArray? = initialDraft?.recording
    private val attachLog = true
    private var contactAllowed = initialDraft?.contactAllowed ?: false
    // 由启动器发起的表单会整列隐藏附件。
    // 通用反馈已经提供了显式的系统截屏/录屏入口，因此
    // 整列隐藏会把录屏组件一并去掉，
    // 尽管该能力其实是可用的。
    private val textOnlyMode = prefill?.triggerType == "LAUNCHER" &&
        screenshotRecaptureHandler == null && screenRecordingHandler == null

    // 语音
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var isListening = false
    private var voiceUsed = false

    // 是否为横屏（学习机默认场景）
    private val isLandscape: Boolean by lazy {
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    private val useLandscapeLayout: Boolean by lazy {
        if (!isLandscape) false else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metricsWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width()
            else @Suppress("DEPRECATION") android.util.DisplayMetrics().also { wm.defaultDisplay.getMetrics(it) }.widthPixels
            val hostWidth = (context as? Activity)?.window?.decorView?.width?.takeIf { it > 0 } ?: metricsWidth
            val width = minOf(metricsWidth, hostWidth)
            width >= 1200
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(createLayout())
        setupWindow()
    }

    // ============================================================
    // 布局构建
    // ============================================================

    private fun createLayout(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = if (useLandscapeLayout) dp(28) else dp(20)
            setPadding(pad, dp(18), pad, dp(20))
            background = roundedBackground("#FFFFFF", 16)
        }
        root.addView(createTitleBar())
        if (useLandscapeLayout && textOnlyMode) {
            root.addView(createFormColumn(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            root.addView(View(context).apply { setBackgroundColor(Color.parseColor("#EEF1F5")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(createContactCheckbox(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(createButtonBar())
            })
            return root
        }
        if (useLandscapeLayout) {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                addView(createFormColumn(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = dp(12)
                })
                addView(createAttachmentColumn(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(12)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            root.addView(View(context).apply { setBackgroundColor(Color.parseColor("#EEF1F5")) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(createContactCheckbox(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(createButtonBar())
            })
            return root
        }

        val scrollView = ScrollView(context).apply { setBackgroundColor(Color.WHITE) }
        root.addView(createFormColumn())
        root.addView(createAttachmentColumn())
        root.addView(createContactCheckbox())
        root.addView(createButtonBar())
        scrollView.addView(root)
        return scrollView
    }

    private fun createFormColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        background = roundedBackground("#F8FAFD", 12)
        addView(createSectionLabel("问题类型（必选）"))
        addView(createIssueTypeBar())
        addView(createSectionLabel("问题描述"))
        addView(createDescriptionArea(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (useLandscapeLayout) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (useLandscapeLayout) 1f else 0f
        ))
    }

    private fun createAttachmentColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        background = roundedBackground("#F8FAFD", 12)
        addView(createSectionLabel("问题附件（可选）"))
        addView(createAttachmentOptions())
        addView(TextView(context).apply {
            text = "附件仅在您主动选择后加密上传"
            textSize = 11f
            setTextColor(Color.parseColor("#8A94A3"))
            setPadding(dp(4), dp(5), 0, 0)
        })
    }

    // ============================================================
    // 子组件
    // ============================================================

    /** 标题栏 — 横屏显示返回箭头样式的关闭按钮 */
    private fun createTitleBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))

            addView(View(context), LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(TextView(context).apply {
                text = "问题反馈"
                textSize = if (useLandscapeLayout) 20f else 18f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#1E2A3A"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(36), 1f))

            // 关闭按钮
            addView(TextView(context).apply {
                text = "×"
                textSize = 24f
                gravity = Gravity.CENTER
                contentDescription = "关闭反馈"
                setTextColor(Color.parseColor("#7D8796"))
                background = roundedBackground("#F3F5F8", 18)
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
    }

    /** 分割线标签 */
    private fun createSectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(8), 0, dp(7))
        }
    }

    // ── 问题类型标签栏 ──

    private fun createIssueTypeBar(): LinearLayout {
        val types = FeedbackSDK.currentIssueTypes()
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        types.take(3).forEach { row1.addView(createIssueTag(it)) }
        types.drop(3).forEach { row2.addView(createIssueTag(it)) }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (types.size > 3) addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun createIssueTag(type: String): TextView {
        val tv = TextView(context)
        // 保存引用用于 lambda 内访问（避免 apply 自引用问题）
        val self = tv
        tv.apply {
            text = type
            textSize = 13f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#333333"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(20).toFloat()
                setStroke(1, Color.parseColor("#E0E0E0"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(7)
                bottomMargin = dp(7)
            }
            setOnClickListener {
                // 单选：取消其他选中，高亮当前
                issueTypeTags.forEach { t ->
                    (t.background as? GradientDrawable)?.apply {
                        setColor(Color.parseColor("#F5F5F5"))
                        setStroke(1, Color.parseColor("#E0E0E0"))
                    }
                    t.setTextColor(Color.parseColor("#333333"))
                }
                (self.background as? GradientDrawable)?.apply {
                    setColor(Color.parseColor("#E8F4FD"))
                    setStroke(2, Color.parseColor("#1677FF"))
                }
                self.setTextColor(Color.parseColor("#1677FF"))
                selectedIssueType = type
            }
        }
        issueTypeTags.add(tv)

        // 如果 prefill 带了 issueType，自动选中
        if (type == selectedIssueType) {
            (tv.background as? GradientDrawable)?.apply {
                setColor(Color.parseColor("#E8F4FD"))
                setStroke(2, Color.parseColor("#1677FF"))
            }
            tv.setTextColor(Color.parseColor("#1677FF"))
        }

        return tv
    }

    // ── 描述编辑区（文本 + 语音） ──

    private fun createDescriptionArea(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            // 编辑框
            val descInput = EditText(context).apply {
                hint = "请描述遇到的问题..."
                minLines = if (useLandscapeLayout) 3 else 4
                maxLines = if (useLandscapeLayout) 5 else 7
                gravity = Gravity.TOP or Gravity.START
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.parseColor("#FAFAFA"))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FAFAFA"))
                    cornerRadius = dp(8).toFloat()
                    setStroke(1, Color.parseColor("#E8E8E8"))
                }
                tag = "desc_input"
                setText(initialDraft?.description.orEmpty())
                if (useLandscapeLayout) {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                }
            }
            addView(descInput)

            // 只有当 Android 提供标准 RecognitionService 时才暴露语音输入。
            // 厂商私有的语音服务无法被通用宿主安全调用。
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(6), 0, 0)

                    addView(TextView(context).apply {
                        text = if (isListening) "⏺ 正在聆听..." else "🎤 语音输入"
                        textSize = 12f
                        setTextColor(Color.parseColor(if (isListening) "#1677FF" else "#999999"))
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(4).toFloat()
                            setStroke(1, Color.parseColor(if (isListening) "#1677FF" else "#E0E0E0"))
                        }
                        setOnClickListener { toggleSpeechInput(descInput) }
                        tag = "voice_btn"
                    })
                })
            }
        }
    }

    // ── 附件选项 ──

    private fun createAttachmentOptions(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            screenshotContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(screenshotContainer)
            renderScreenshotAttachment()
            addView(createRecordingCheckbox(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) })
        }
    }

    private fun renderScreenshotAttachment() {
        val container = screenshotContainer ?: return
        container.removeAllViews()
        val bytes = capturedScreenshot
        if (bytes == null) {
            container.addView(TextView(context).apply {
                text = "当前页面未获得可用截图，可继续提交文字或录屏"
                textSize = 12f
                setTextColor(Color.parseColor("#777777"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = roundedBackground("#F6F7F9", 8)
            })
            screenshotRecaptureHandler?.let {
            container.addView(createScreenshotAction(ScreenshotAction.RETAKE, "重新截图") { requestScreenshotRecapture() })
            }
            return
        }

        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { preview ->
            container.addView(ImageView(context).apply {
                setImageBitmap(preview)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "当前页面截图预览，点击查看大图"
                background = roundedBackground("#EEF2F7", 8)
                setOnClickListener { openScreenshotEditor(editable = false) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (useLandscapeLayout) 120 else 170)
                ).apply { bottomMargin = dp(8) }
            })
        }

        container.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(createScreenshotAction(ScreenshotAction.VIEW, "查看") { openScreenshotEditor(editable = false) }, actionParams())
            addView(createScreenshotAction(ScreenshotAction.EDIT, "编辑") { openScreenshotEditor(editable = true) }, actionParams())
            screenshotRecaptureHandler?.let {
                addView(createScreenshotAction(ScreenshotAction.RETAKE, "重新截图") { requestScreenshotRecapture() }, actionParams())
            }
            addView(createScreenshotAction(ScreenshotAction.DELETE, "删除") { deleteScreenshot() }, actionParams())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            bottomMargin = dp(15)
        })
        container.addView(createStyledCheckbox(
            "随反馈上传截图（${bytes.size / 1024} KB）",
            attachScreenshot
        ) { attachScreenshot = it }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(3) })
    }

    private fun actionParams() = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
        marginEnd = dp(4)
    }

    private fun createScreenshotAction(action: ScreenshotAction, label: String, click: () -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { click() }
            addView(ImageView(context).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (action == ScreenshotAction.DELETE) "#E75C64" else "#2D78C8"))
                    shape = GradientDrawable.OVAL
                }
                setImageDrawable(ScreenshotActionDrawable(action, Color.WHITE, dp(17)))
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { bottomMargin = dp(2) })
            addView(TextView(context).apply {
                text = label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#526173"))
            })
        }

    private fun roundedBackground(color: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radius).toFloat()
    }

    private fun deleteScreenshot() {
        capturedScreenshot?.fill(0)
        capturedScreenshot = null
        attachScreenshot = false
        renderScreenshotAttachment()
    }

    private fun openScreenshotEditor(editable: Boolean) {
        val bytes = capturedScreenshot ?: return
        ScreenshotEditorDialog(context, bytes, editable) { edited ->
            val old = capturedScreenshot
            capturedScreenshot = edited
            attachScreenshot = false
            if (old !== edited) old?.fill(0)
            renderScreenshotAttachment()
        }.show()
    }

    private fun requestScreenshotRecapture() {
        val handler = screenshotRecaptureHandler ?: return
        // 只把 Dialog 挪走，在某些 OEM 合成器上透明宿主 Activity 仍是
        // 顶层任务。要把那个任务也挪到后面，然后等真实应用
        // 画面就绪，再向 MediaProjection 要一块新缓冲。
        removeWindowTemporarily(moveHostTaskBehind = true, detachDialogWindow = true)
        hostActivity.window.decorView.postDelayed({
            handler.request { fresh ->
                hostActivity.runOnUiThread {
                    if (fresh != null && fresh.isNotEmpty()) {
                        capturedScreenshot?.fill(0)
                        capturedScreenshot = fresh
                        attachScreenshot = false
                        renderScreenshotAttachment()
                    } else {
                        Toast.makeText(context, "重新截图失败，已保留原截图", Toast.LENGTH_SHORT).show()
                    }
                    restoreWindowAfterTemporaryRemoval()
                }
            }
        }, 350L)
    }

    private fun createRecordingCheckbox(): CheckBox {
        val checkbox = createStyledCheckbox(
            if (recordingBytes != null) "已录制（≤30秒）" else "录屏（≤30秒）",
            attachRecording && recordingBytes != null
        ) { }
        var suppressChange = false
        checkbox.setOnCheckedChangeListener { button, checked ->
            if (suppressChange) return@setOnCheckedChangeListener
            if (!checked) {
                recordingBytes?.fill(0)
                recordingBytes = null
                attachRecording = false
                button.text = "录屏（≤30秒）"
                return@setOnCheckedChangeListener
            }
            val activity = hostActivity
            // 在某些学习机（XT11A/Android 10）上，MediaProjection 会把隐藏的
            // Dialog 图层留在合成画面里。要在申请截屏权限之前
            // 彻底移除该窗口图层。
            val draftBeforeRecording = snapshotDraft()
            removeWindowTemporarily(
                moveHostTaskBehind = screenRecordingHandler != null,
                detachDialogWindow = true
            )
            suppressChange = true
            button.isChecked = false
            suppressChange = false
            button.text = "录制中（可使用悬浮控制）"
            val completed: (ByteArray) -> Unit = { bytes ->
                activity.runOnUiThread {
                    if (canRestoreOriginalHost()) {
                        recordingBytes = bytes
                        attachRecording = true
                        suppressChange = true
                        button.isChecked = true
                        suppressChange = false
                        button.text = "已录制（≤30秒）"
                        restoreWindowAfterTemporaryRemoval()
                    } else {
                        recordingRecoveryHandler?.recover(
                            draftBeforeRecording.copy(recording = bytes, attachRecording = true), null
                        ) ?: bytes.fill(0)
                    }
                }
            }
            val failed: (String) -> Unit = { message ->
                activity.runOnUiThread {
                    if (canRestoreOriginalHost()) {
                        button.text = "录屏（≤30秒）"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        restoreWindowAfterTemporaryRemoval()
                    } else recordingRecoveryHandler?.recover(draftBeforeRecording, message)
                }
            }
            val requested = screenRecordingHandler?.request(
                maxDurationSeconds = FeedbackSDK.currentRecordingDurationSeconds(),
                bitrateKbps = FeedbackSDK.currentRecordingBitrateKbps(),
                started = { },
                completed = completed,
                error = failed
            ) ?: ScreenRecordingCoordinator.request(activity, {}, completed, failed)
            if (!requested) {
                button.text = "录屏（≤30秒）"
                restoreWindowAfterTemporaryRemoval()
                Toast.makeText(context, "已有录屏任务进行中", Toast.LENGTH_SHORT).show()
            }
        }
        return checkbox
    }

    private fun snapshotDraft() = FeedbackDraft(
        description = findEditTextByTag("desc_input")?.text?.toString().orEmpty(),
        issueType = selectedIssueType,
        screenshot = capturedScreenshot,
        attachScreenshot = attachScreenshot,
        recording = recordingBytes,
        attachRecording = attachRecording,
        contactAllowed = contactAllowed
    )

    private fun canRestoreOriginalHost() =
        !hostActivity.isFinishing && !hostActivity.isDestroyed && hostActivity.window.decorView.isAttachedToWindow

    private fun removeWindowTemporarily(
        moveHostTaskBehind: Boolean = false,
        detachDialogWindow: Boolean = true
    ) {
        hostActivity.currentFocus?.let { focused ->
            focused.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
        }
        hostActivity.window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        window?.apply {
            decorView.visibility = View.INVISIBLE
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        temporarilyRemoved = true
        dialogWindowDetached = detachDialogWindow
        if (detachDialogWindow) dismiss()
        hostActivity.window.decorView.invalidate()
        if (moveHostTaskBehind) {
            hostTaskMovedBehind = hostActivity.moveTaskToBack(true)
        }
    }

    private fun restoreWindowAfterTemporaryRemoval() {
        if (hostActivity.isFinishing || hostActivity.isDestroyed) return
        hostActivity.window.clearFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        temporarilyRemoved = false
        val needsShow = dialogWindowDetached
        dialogWindowDetached = false
        if (hostTaskMovedBehind) {
            hostTaskMovedBehind = false
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            runCatching {
                // 只恢复反馈自己的任务。MOVE_TASK_WITH_HOME 在 XT11A 上会
                // 把启动器一起抬到前台，导致重新截屏后被反馈的
                // 业务应用看起来像已经退出。
                activityManager.moveTaskToFront(hostActivity.taskId, 0)
            }.onFailure {
                hostActivity.startActivity(Intent(hostActivity, hostActivity.javaClass).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            hostActivity.window.decorView.postDelayed({
                if (!hostActivity.isFinishing && !hostActivity.isDestroyed) {
                    if (needsShow) show()
                    window?.decorView?.visibility = View.VISIBLE
                }
            }, 250L)
        } else {
            if (needsShow) show()
            window?.decorView?.visibility = View.VISIBLE
        }
    }

    private fun createStyledCheckbox(
        label: String,
        default: Boolean,
        onChange: (Boolean) -> Unit
    ): CheckBox {
        return CheckBox(context).apply {
            text = label
            textSize = 13.5f
            isChecked = default
            setTextColor(Color.parseColor("#555555"))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(0, dp(5), if (useLandscapeLayout) dp(12) else 0, dp(5))
            buttonDrawable = CompactCheckDrawable(dp(21))
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    // ── 联系授权 ──

    private fun createContactCheckbox(): CheckBox {
        return CheckBox(context).apply {
            text = "允许工作人员就此问题联系我"
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            isChecked = contactAllowed
            setPadding(0, dp(6), 0, 0)
            buttonDrawable = CompactCheckDrawable(dp(21))
            setOnCheckedChangeListener { _, checked -> contactAllowed = checked }
        }
    }

    // ── 按钮栏 ──

    private fun createButtonBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)

            // 取消
            addView(Button(context).apply {
                text = "取消"
                textSize = 14f
                minHeight = 0
                minWidth = 0
                setPadding(dp(28), 0, dp(28), 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    cornerRadius = dp(6).toFloat()
                }
                setTextColor(Color.parseColor("#666666"))
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))

            // 提交
            addView(Button(context).apply {
                text = "提交"
                textSize = 14f
                minHeight = 0
                minWidth = 0
                setPadding(dp(30), 0, dp(30), 0)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1677FF"))
                    cornerRadius = dp(6).toFloat()
                }
                setTextColor(Color.WHITE)
                // 用代码创建的 View 在挂载之前没有 LayoutParams。
                // 要显式设置；对空值做类型转换在真机上会崩溃。
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(42)
                ).apply { marginStart = dp(14) }
                setOnClickListener {
                    val descInput = findEditTextByTag("desc_input")
                    val desc = descInput?.text?.toString()?.trim() ?: ""
                    val issueType = selectedIssueType

                    if (issueType == null) {
                        Toast.makeText(context, "请选择问题类型", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (desc.isEmpty() && !attachScreenshot && !attachRecording && !attachLog) {
                        Toast.makeText(context, "请填写描述或选择附件", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // 异步提交
                    val selectedAttachments = buildList {
                        if (attachScreenshot) {
                            capturedScreenshot?.let {
                                screenshotHandedToSubmit = true
                                add(AttachmentInput("SCREENSHOT", "image/jpeg", it))
                            }
                        }
                        if (attachLog) {
                            val logBytes = frozenDiagnosticLog.copyOf()
                            if (logBytes.isNotEmpty()) {
                                add(AttachmentInput("LOG", "text/plain", logBytes))
                            }
                        }
                        if (attachRecording) {
                            recordingBytes?.let {
                                add(AttachmentInput("SCREEN_RECORDING", "video/mp4", it))
                            }
                        }
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        val result = FeedbackSDK.submit(
                            description = desc,
                            prefill = (prefill ?: PrefillData()).copy(
                                issueType = issueType,
                                inputMethods = if (voiceUsed) listOf("TEXT", "VOICE") else listOf("TEXT")
                            ),
                            contactAllowed = contactAllowed,
                            attachments = selectedAttachments
                        )
                        hostActivity.runOnUiThread {
                            val message = when (result) {
                                is SubmitResult.Success -> "已提交，感谢您的反馈"
                                is SubmitResult.NetworkError -> "网络异常，反馈已安全保存，将自动重试"
                                is SubmitResult.ServerError -> if (result.retryable) {
                                    "服务暂时不可用，反馈已安全保存，将自动重试"
                                } else {
                                    "提交失败，请检查内容后重试"
                                }
                                is SubmitResult.Failure -> "提交失败，请稍后重试"
                            }
                            Toast.makeText(hostActivity.applicationContext, message, Toast.LENGTH_LONG).show()
                        }
                    }
                    dismiss()
                }
            })
        }
    }

    override fun dismiss() {
        if (temporarilyRemoved) {
            super.dismiss()
            return
        }
        if (!screenshotHandedToSubmit) capturedScreenshot?.fill(0)
        super.dismiss()
        onClosed?.invoke()
    }

    // ============================================================
    // 语音输入
    // ============================================================

    private fun toggleSpeechInput(targetEditText: EditText) {
        if (isListening) {
            stopSpeechInput()
            return
        }
        startSpeechInput(targetEditText)
    }

    private fun startSpeechInput(targetEditText: EditText) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(hostActivity, arrayOf(Manifest.permission.RECORD_AUDIO), VOICE_PERMISSION_REQUEST)
            Toast.makeText(context, "请允许麦克风权限后再次点击语音输入", Toast.LENGTH_LONG).show()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "当前设备未提供语音识别服务，请使用文字输入", Toast.LENGTH_LONG).show()
            return
        }
        stopSpeechInput(cancel = true)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val current = targetEditText.text.toString()
                    val (newText, newCursor) = SpeechInputText.insert(current, targetEditText.selectionStart, matches[0])
                    targetEditText.setText(newText)
                    targetEditText.setSelection(newCursor)
                    voiceUsed = true
                }
                stopSpeechInput(cancel = false)
            }

            override fun onPartialResults(partialResults: android.os.Bundle?) {
                // 实时预览（当前会话中使用，但提交时只保存最终 description）
            }

            override fun onError(error: Int) {
                stopSpeechInput(cancel = false)
                if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(context, speechErrorMessage(error), Toast.LENGTH_SHORT).show()
                } else if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    Toast.makeText(context, "未识别到有效语音，请重试", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) {
                isListening = true
                updateVoiceButtonState()
            }

            override fun onEndOfSpeech() {
                isListening = false
                updateVoiceButtonState(processing = true)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })

        runCatching { speechRecognizer?.startListening(intent) }.onFailure {
            stopSpeechInput(cancel = true)
            Toast.makeText(context, "语音识别启动失败，请使用文字输入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopSpeechInput(cancel: Boolean = true) {
        isListening = false
        if (cancel) speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        updateVoiceButtonState()
    }

    private fun updateVoiceButtonState(processing: Boolean = false) {
        findTextViewByTag("voice_btn")?.let {
            if (isListening || processing) {
                it.text = if (processing) "⏳ 正在识别..." else "⏺ 正在聆听..."
                it.setTextColor(Color.parseColor("#1677FF"))
                (it.background as? GradientDrawable)?.setStroke(1, Color.parseColor("#1677FF"))
            } else {
                it.text = "🎤 语音输入"
                it.setTextColor(Color.parseColor("#999999"))
                (it.background as? GradientDrawable)?.setStroke(1, Color.parseColor("#E0E0E0"))
            }
        }
    }

    private fun speechErrorMessage(error: Int) = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限，请在系统设置中开启"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络不可用，语音识别失败"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音，请重试"
        else -> "语音识别失败，请重试"
    }

    // ============================================================
    // 窗口配置
    // ============================================================

    private fun setupWindow() {
        window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getMetrics(it) }
                android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            }
            val screenWidth = bounds.width()
            val screenHeight = bounds.height()

            if (useLandscapeLayout) {
                // 学习机横屏 → 居中弹窗，背景保留当前页面 + 深色遮罩
                // 有些学习机启动器会把第三方应用跑在窄兼容窗口里，
                // 即使物理屏幕是横屏。按桌面尺寸算百分比会把
                // 附件/操作列裁掉。
                val compatibilityWindow = screenWidth < 1200
                val widthRatio = if (compatibilityWindow) 0.94 else if (textOnlyMode) 0.38 else 0.55
                val heightRatio = if (compatibilityWindow) 0.90 else 0.72
                val dialogWidth = (screenWidth * widthRatio).toInt()
                val dialogHeight = (screenHeight * heightRatio).toInt() // 双栏无需滚动，保留更多业务页面上下文
                setLayout(dialogWidth, dialogHeight)
                setGravity(Gravity.CENTER)
                // 深色半透明遮罩
                // 圆角由白色内容自己负责。Dialog 窗口本身要保持透明，
                // 变暗由 FLAG_DIM_BEHIND 提供。深色窗口背景会从圆角
                // 漏出来，在 Android 10 学习机上表现为
                // 四个黑色三角。
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                // 点击遮罩外不关闭（用户在业务页面上操作）
                setCanceledOnTouchOutside(false)
                // 添加窗口圆角（Android 12+）
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.6f)
            } else {
                // 窄屏竖屏 → Bottom Sheet 风格（底部弹出）
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                setCanceledOnTouchOutside(true)
                // 底部弹出动画
                window?.attributes?.windowAnimations = android.R.style.Animation_Dialog
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopSpeechInput()
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private fun findEditTextByTag(tag: String): EditText? {
        return (findViewById<View>(android.R.id.content) ?: window?.decorView)
            ?.findViewWithTag<View>(tag) as? EditText
    }

    private fun findTextViewByTag(tag: String): TextView? {
        return (findViewById<View>(android.R.id.content) ?: window?.decorView)
            ?.findViewWithTag<View>(tag) as? TextView
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val VOICE_PERMISSION_REQUEST = 41832
    }
}
