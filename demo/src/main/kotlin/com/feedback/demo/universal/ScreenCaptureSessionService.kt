package com.feedback.demo.universal

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.feedback.demo.HomeActivity
import java.io.ByteArrayOutputStream
import java.io.File

/** 持有用户授权的屏幕镜像，只在反馈触发之后才真正生成一帧。 */
class ScreenCaptureSessionService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var capturePending = false
    private var captureRequestId = 0L
    private var pendingContextToken: String? = null
    private var pendingForEditor = false
    private var recordingDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recording = false
    private var stoppingRecording = false
    private var recordingPaused = false
    private var recordingControls: View? = null
    private var recordingControlWindowManager: WindowManager? = null
    private var pauseControl: TextView? = null
    private var recordingControlParams: WindowManager.LayoutParams? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWindowX = 0
    private var dragStartWindowY = 0
    private var controlDragged = false

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("feedback-single-frame-capture").also { it.start() }
        worker = Handler(workerThread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_CAPTURE -> worker.post {
                requestCapture(
                    intent.getStringExtra(EXTRA_CONTEXT_TOKEN),
                    intent.getBooleanExtra(EXTRA_EDITOR_CAPTURE, false)
                )
            }
            ACTION_START_RECORDING -> worker.postDelayed({ startRecording(intent) }, 450L)
            ACTION_STOP_RECORDING -> worker.post { finishRecording() }
            ACTION_PAUSE_RECORDING -> worker.post { pauseRecording() }
            ACTION_RESUME_RECORDING -> worker.post { resumeRecording() }
            ACTION_CANCEL_RECORDING -> worker.post { cancelRecording() }
            ACTION_STOP -> stopSession()
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startSession(intent: Intent) {
        if (projection != null) {
            ScreenCaptureSession.update(ScreenCaptureSession.State.READY)
            return
        }
        startCaptureForeground()
        try {
            val resultData = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else intent.getParcelableExtra(EXTRA_RESULT_DATA)
            requireNotNull(resultData)
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED), resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = stopSession()
            }, worker)
            // 独立宿主可能运行在 OEM 兼容窗口里（XT11A 在该窗口
            // 上报 700x1080，而物理屏幕是 1920x1080）。
            // MediaProjection 镜像的是物理屏幕，因此这里若使用资源
            // 尺寸，会把截到的帧裁切或旋转，并可能在一张可用图像
            // 到达表单之前就超时。
            val metrics = physicalDisplayMetrics()
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (capturePending) runCatching {
                            completeCapture(image.planes[0], width, height)
                        }
                    } finally { image.close() }
                }, worker)
            }
            display = projection?.createVirtualDisplay(
                "UniversalFeedbackSingleFrame", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, worker
            )
            ScreenCaptureSession.update(ScreenCaptureSession.State.READY)
        } catch (_: Exception) {
            stopSession()
        }
    }

    private fun requestCapture(contextToken: String?, forEditor: Boolean) {
        if (projection == null || recording || (!forEditor && contextToken == null)) {
            if (forEditor) deliverEditorCapture(null) else openForm(contextToken, null)
            return
        }
        val requestId = ++captureRequestId
        capturePending = false
        // 捕获命令与 ImageReader 回调共用同一个 Worker Looper。
        // 先解绑并排空，这样在浮层入口消失之前合成的帧
        // 就无法满足新的请求。
        runCatching { display?.surface = null }
        runCatching { reader?.acquireLatestImage()?.close() }
        pendingContextToken = contextToken
        pendingForEditor = forEditor
        worker.postDelayed({
            if (requestId != captureRequestId) return@postDelayed
            capturePending = true
            runCatching { display?.surface = reader?.surface }
        }, FRAME_RESET_DELAY_MS)
        worker.postDelayed({
            if (requestId == captureRequestId && capturePending) {
                capturePending = false
                if (pendingForEditor) deliverEditorCapture(null) else openForm(pendingContextToken, null)
                pendingContextToken = null
                pendingForEditor = false
            }
        }, CAPTURE_TIMEOUT_MS)
    }

    @Suppress("DEPRECATION")
    private fun startRecording(intent: Intent) {
        if (projection == null) {
            deliverRecordingError("页面截图授权已失效，请重新授权后录屏")
            return
        }
        if (recording || recorder != null) {
            deliverRecordingError("已有录屏任务进行中")
            return
        }
        try {
            val metrics = resources.displayMetrics
            val landscape = metrics.widthPixels >= metrics.heightPixels
            val width = if (landscape) 1280 else 720
            val height = if (landscape) 720 else 1280
            val durationMs = intent.getIntExtra(EXTRA_RECORDING_DURATION, 30).coerceIn(1, 120) * 1_000L
            val bitrate = intent.getIntExtra(EXTRA_RECORDING_BITRATE, 1_000).coerceIn(100, 8_000) * 1_000
            recordingFile = File(cacheDir, "universal-feedback-recording-${System.currentTimeMillis()}.mp4")
            val activeRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            activeRecorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(24)
                setVideoEncodingBitRate(bitrate)
                setOutputFile(recordingFile!!.absolutePath)
                setMaxDuration(durationMs.toInt())
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) worker.post { finishRecording() }
                }
                prepare()
            }
            recorder = activeRecorder
            recordingDisplay = projection?.createVirtualDisplay(
                "UniversalFeedbackRecording", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                activeRecorder.surface, null, worker
            )
            activeRecorder.start()
            recording = true
            recordingPaused = false
            stoppingRecording = false
            showRecordingNotification()
            Handler(mainLooper).post { showRecordingControls() }
            Handler(mainLooper).post { setFeedbackEntryVisible(false) }
            Handler(mainLooper).post { recordingCallbacks?.started?.invoke() }
            worker.postDelayed({ if (recording) finishRecording() }, durationMs)
        } catch (error: Exception) {
            cleanupRecording()
            deliverRecordingError(error.message ?: "录屏启动失败")
        }
    }

    private fun finishRecording() {
        if (!recording || stoppingRecording) return
        stoppingRecording = true
        try {
            recorder?.stop()
            val file = recordingFile ?: error("录屏文件不存在")
            val bytes = file.readBytes()
            require(bytes.isNotEmpty()) { "录屏文件为空" }
            cleanupRecording()
            startCaptureForeground()
            val callbacks = recordingCallbacks
            recordingCallbacks = null
            Handler(mainLooper).post { callbacks?.completed?.invoke(bytes) }
        } catch (error: Exception) {
            cleanupRecording()
            startCaptureForeground()
            deliverRecordingError(error.message ?: "停止录屏失败")
        }
    }

    private fun pauseRecording() {
        if (!recording || recordingPaused || Build.VERSION.SDK_INT < 24) return
        runCatching { recorder?.pause() }
            .onSuccess {
                recordingPaused = true
                Handler(mainLooper).post {
                    pauseControl?.text = "▶"
                    pauseControl?.contentDescription = "继续录屏"
                    showRecordingNotification()
                }
            }
            .onFailure { deliverNonTerminalRecordingMessage("暂停录屏失败") }
    }

    private fun resumeRecording() {
        if (!recording || !recordingPaused || Build.VERSION.SDK_INT < 24) return
        runCatching { recorder?.resume() }
            .onSuccess {
                recordingPaused = false
                Handler(mainLooper).post {
                    pauseControl?.text = "Ⅱ"
                    pauseControl?.contentDescription = "暂停录屏"
                    showRecordingNotification()
                }
            }
            .onFailure { deliverNonTerminalRecordingMessage("继续录屏失败") }
    }

    private fun cancelRecording() {
        if (!recording && recorder == null) return
        cleanupRecording()
        startCaptureForeground()
        deliverRecordingError("已取消录屏")
    }

    private fun deliverNonTerminalRecordingMessage(message: String) {
        Handler(mainLooper).post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupRecording() {
        recording = false
        recordingPaused = false
        stoppingRecording = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { recordingDisplay?.release() }
        recordingFile?.delete()
        recorder = null
        recordingDisplay = null
        recordingFile = null
        Handler(mainLooper).post { hideRecordingControls() }
    }

    private fun deliverRecordingError(message: String) {
        val callbacks = recordingCallbacks
        recordingCallbacks = null
        Handler(mainLooper).post { callbacks?.error?.invoke(message) }
    }

    private fun completeCapture(plane: android.media.Image.Plane, width: Int, height: Int) {
        require(plane.pixelStride > 0 && plane.rowStride >= plane.pixelStride * width)
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            try {
                ByteArrayOutputStream().use { output ->
                    check(cropped.compress(Bitmap.CompressFormat.JPEG, 75, output))
                    output.toByteArray().also { check(it.isNotEmpty()) }
                }
            } finally { cropped.recycle() }
        } finally { bitmap.recycle() }
        capturePending = false
        if (pendingForEditor) deliverEditorCapture(bytes)
        else openForm(pendingContextToken, CapturedScreenshotRegistry.issue(bytes))
        pendingContextToken = null
        pendingForEditor = false
    }

    private fun deliverEditorCapture(bytes: ByteArray?) {
        val callback = editorCallback
        editorCallback = null
        Handler(mainLooper).post { callback?.invoke(bytes) }
    }

    private fun openForm(contextToken: String?, screenshotToken: String?) {
        startActivity(Intent(this, UniversalFeedbackActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(UniversalFeedbackActivity.EXTRA_CONTEXT_TOKEN, contextToken)
            putExtra(UniversalFeedbackActivity.EXTRA_SCREENSHOT_TOKEN, screenshotToken)
        })
    }

    private fun startCaptureForeground() {
        val stop = PendingIntent.getService(this, 81, Intent(this, javaClass).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("通用反馈截图已就绪")
            .setContentText("仅在您主动触发反馈时截取一帧")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止截图授权", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun showRecordingNotification() {
        val pauseAction = if (recordingPaused) ACTION_RESUME_RECORDING else ACTION_PAUSE_RECORDING
        val pause = recordingPendingIntent(82, pauseAction)
        val save = recordingPendingIntent(83, ACTION_STOP_RECORDING)
        val cancel = recordingPendingIntent(84, ACTION_CANCEL_RECORDING)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(if (recordingPaused) "录屏已暂停" else "正在录制问题页面")
            .setContentText("可暂停、保存或取消")
            .setOngoing(true)
            .addAction(if (recordingPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (recordingPaused) "继续" else "暂停", pause)
            .addAction(android.R.drawable.ic_menu_save, "保存", save)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancel)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun recordingPendingIntent(requestCode: Int, actionName: String) = PendingIntent.getService(
        this, requestCode, Intent(this, javaClass).apply { action = actionName },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun showRecordingControls() {
        if (recordingControls != null || !Settings.canDrawOverlays(this)) return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E61B2430"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#405A708A"))
            }
        }
        pauseControl = recordingControl("Ⅱ", "暂停录屏") {
            worker.post { if (recordingPaused) resumeRecording() else pauseRecording() }
        }
        pill.addView(pauseControl)
        pill.addView(recordingControl("✓", "保存录屏") { worker.post { finishRecording() } })
        pill.addView(recordingControl("×", "取消录屏", danger = true) { worker.post { cancelRecording() } })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(20)
            y = dp(62)
        }
        recordingControlParams = params
        pill.setOnTouchListener(recordingDragTouchListener(null))
        runCatching { windowManager.addView(pill, params) }
            .onSuccess {
                recordingControlWindowManager = windowManager
                recordingControls = pill
            }
    }

    private fun recordingControl(symbol: String, description: String, danger: Boolean = false, click: () -> Unit) =
        TextView(this).apply {
            text = symbol
            textSize = 18f
            gravity = Gravity.CENTER
            contentDescription = description
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (danger) "#D94B55" else "#2D78C8"))
                shape = GradientDrawable.OVAL
            }
            setOnTouchListener(recordingDragTouchListener(click))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(7) }
        }

    private fun hideRecordingControls() {
        recordingControls?.let { view -> runCatching { recordingControlWindowManager?.removeView(view) } }
        recordingControls = null
        recordingControlWindowManager = null
        pauseControl = null
        recordingControlParams = null
    }

    private fun recordingDragTouchListener(click: (() -> Unit)?): View.OnTouchListener = View.OnTouchListener { _, event ->
        val params = recordingControlParams ?: return@OnTouchListener false
        val manager = recordingControlWindowManager ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWindowX = params.x
                dragStartWindowY = params.y
                controlDragged = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) controlDragged = true
                params.x = (dragStartWindowX - dx.toInt()).coerceAtLeast(0)
                params.y = (dragStartWindowY + dy.toInt()).coerceAtLeast(0)
                recordingControls?.let { runCatching { manager.updateViewLayout(it, params) } }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!controlDragged) click?.invoke()
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> false
        }
    }

    private fun setFeedbackEntryVisible(visible: Boolean) {
        startService(Intent(this, UniversalFeedbackOverlayService::class.java).apply {
            action = if (visible) UniversalFeedbackOverlayService.ACTION_SHOW
            else UniversalFeedbackOverlayService.ACTION_HIDE
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun physicalDisplayMetrics(): DisplayMetrics = DisplayMetrics().also { metrics ->
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
    }

    private fun stopSession() {
        val wasEditorCapture = pendingForEditor || editorCallback != null
        capturePending = false
        pendingContextToken = null
        pendingForEditor = false
        if (recording || recorder != null) {
            cleanupRecording()
            deliverRecordingError("截图授权已停止，录屏已结束")
        }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null
        ScreenCaptureSession.update(ScreenCaptureSession.State.STOPPED)
        if (wasEditorCapture) deliverEditorCapture(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "通用反馈截图", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        stopSession()
        workerThread.quitSafely()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.feedback.universal.capture.START"
        const val ACTION_CAPTURE = "com.feedback.universal.capture.CAPTURE"
        const val ACTION_STOP = "com.feedback.universal.capture.STOP"
        const val ACTION_START_RECORDING = "com.feedback.universal.capture.RECORD_START"
        const val ACTION_STOP_RECORDING = "com.feedback.universal.capture.RECORD_STOP"
        const val ACTION_PAUSE_RECORDING = "com.feedback.universal.capture.RECORD_PAUSE"
        const val ACTION_RESUME_RECORDING = "com.feedback.universal.capture.RECORD_RESUME"
        const val ACTION_CANCEL_RECORDING = "com.feedback.universal.capture.RECORD_CANCEL"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_CONTEXT_TOKEN = "contextToken"
        private const val EXTRA_EDITOR_CAPTURE = "editorCapture"
        private const val EXTRA_RECORDING_DURATION = "recordingDuration"
        private const val EXTRA_RECORDING_BITRATE = "recordingBitrate"
        private const val CHANNEL_ID = "universal_feedback_capture"
        private const val NOTIFICATION_ID = 28462
        private const val FRAME_RESET_DELAY_MS = 120L
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        @Volatile private var editorCallback: ((ByteArray?) -> Unit)? = null
        @Volatile private var recordingCallbacks: RecordingCallbacks? = null

        private data class RecordingCallbacks(
            val started: () -> Unit,
            val completed: (ByteArray) -> Unit,
            val error: (String) -> Unit
        )

        fun captureForEditor(context: Context, callback: (ByteArray?) -> Unit) {
            editorCallback = callback
            context.startService(Intent(context, ScreenCaptureSessionService::class.java).apply {
                action = ACTION_CAPTURE
                putExtra(EXTRA_EDITOR_CAPTURE, true)
            })
        }

        fun record(
            context: Context,
            maxDurationSeconds: Int,
            bitrateKbps: Int,
            started: () -> Unit,
            completed: (ByteArray) -> Unit,
            error: (String) -> Unit
        ): Boolean {
            if (recordingCallbacks != null) return false
            recordingCallbacks = RecordingCallbacks(started, completed, error)
            context.startService(Intent(context, ScreenCaptureSessionService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_RECORDING_DURATION, maxDurationSeconds)
                putExtra(EXTRA_RECORDING_BITRATE, bitrateKbps)
            })
            return true
        }
    }
}
