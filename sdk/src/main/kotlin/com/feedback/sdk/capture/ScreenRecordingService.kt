package com.feedback.sdk.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.VirtualDisplay
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File

/** 前台静默录制 H.264/MP4，硬性上限 30 秒。 */
class ScreenRecordingService : Service() {
    companion object {
        const val ACTION_START = "com.feedback.sdk.recording.START"
        const val ACTION_STOP = "com.feedback.sdk.recording.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_MAX_DURATION_SECONDS = "max_duration_seconds"
        const val EXTRA_BITRATE_KBPS = "bitrate_kbps"
        private const val CHANNEL_ID = "feedback_sdk_screen_recording"
        private const val NOTIFICATION_ID = 18462
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var projection: MediaProjection? = null
    private var outputFile: File? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recording = false
    private var stopping = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (recording && !stopping) finishRecording()
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRecording()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || recording) return START_NOT_STICKY
        val startIntent = intent ?: return START_NOT_STICKY
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        // 先让透明的授权 Activity 结束，再产出第一帧编码数据。
        handler.postDelayed({ startRecording(startIntent) }, 700L)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startRecording(intent: Intent) {
        try {
            val resultData = (if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else intent.getParcelableExtra(EXTRA_RESULT_DATA))
                ?: error("missing MediaProjection consent")
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
            val density = intent.getIntExtra(EXTRA_DENSITY, resources.displayMetrics.densityDpi)
            val maxDurationMs = intent.getIntExtra(EXTRA_MAX_DURATION_SECONDS, 30)
                .coerceIn(1, 120) * 1_000L
            val bitrate = intent.getIntExtra(EXTRA_BITRATE_KBPS, 1_000)
                .coerceIn(100, 8_000) * 1_000
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(projectionCallback, handler)
            outputFile = File(cacheDir, "feedback-recording-${System.currentTimeMillis()}.mp4")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            mediaRecorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(24)
                setVideoEncodingBitRate(bitrate)
                setOutputFile(outputFile!!.absolutePath)
                setMaxDuration(maxDurationMs.toInt())
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording()
                }
                prepare()
            }
            recorder = mediaRecorder
            virtualDisplay = projection?.createVirtualDisplay(
                "FeedbackSDKRecording", width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface, null, null
            )
            mediaRecorder.start()
            recording = true
            ScreenRecordingCoordinator.started()
            SdkLogBuffer.record("INFO", "screen_recording_started", "${width}x$height")
            handler.postDelayed({ finishRecording() }, maxDurationMs)
        } catch (error: Exception) {
            fail(error.message ?: "录屏启动失败")
        }
    }

    private fun finishRecording() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        if (!recording) {
            fail("录屏尚未开始")
            return
        }
        try {
            recorder?.stop()
            recording = false
            recorder?.reset()
            recorder?.release()
            recorder = null
            virtualDisplay?.release()
            virtualDisplay = null
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
            projection = null
            val file = outputFile ?: error("录屏文件不存在")
            val bytes = file.readBytes()
            file.delete()
            require(bytes.isNotEmpty()) { "录屏文件为空" }
            SdkLogBuffer.record("INFO", "screen_recording_completed", "size=${bytes.size}")
            ScreenRecordingCoordinator.completed(bytes)
        } catch (error: Exception) {
            fail(error.message ?: "停止录屏失败")
            return
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        recording = false
        stopping = true
        runCatching { recorder?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        outputFile?.delete()
        ScreenRecordingCoordinator.failed(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "反馈录屏", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "正在录制问题复现过程" })
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("正在录制问题页面")
            .setContentText("最长30秒，点击“停止”后返回反馈表单")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (recording) fail("录屏服务被系统终止")
        super.onDestroy()
    }
}
