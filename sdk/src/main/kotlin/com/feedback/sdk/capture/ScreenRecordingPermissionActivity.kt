package com.feedback.sdk.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.feedback.sdk.FeedbackSDK

/** SDK 自有的透明 Activity，仅用于必须的 MediaProjection 授权。 */
class ScreenRecordingPermissionActivity : Activity() {
    private val requestCode = 18461

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), requestCode)
    }

    @Deprecated("MediaProjection consent still reports through the platform Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != this.requestCode || resultCode != RESULT_OK || data == null) {
            ScreenRecordingCoordinator.failed("用户未授权录屏")
            finish()
            return
        }
        val metrics = resources.displayMetrics
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else null
        val sourceWidth = bounds?.width() ?: metrics.widthPixels
        val sourceHeight = bounds?.height() ?: metrics.heightPixels
        val (width, height) = fitEven(sourceWidth, sourceHeight, 1280)
        val serviceIntent = Intent(this, ScreenRecordingService::class.java).apply {
            action = ScreenRecordingService.ACTION_START
            putExtra(ScreenRecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordingService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordingService.EXTRA_WIDTH, width)
            putExtra(ScreenRecordingService.EXTRA_HEIGHT, height)
            putExtra(ScreenRecordingService.EXTRA_DENSITY, metrics.densityDpi)
            putExtra(ScreenRecordingService.EXTRA_MAX_DURATION_SECONDS, FeedbackSDK.currentRecordingDurationSeconds())
            putExtra(ScreenRecordingService.EXTRA_BITRATE_KBPS, FeedbackSDK.currentRecordingBitrateKbps())
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        finish()
    }

    private fun fitEven(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(width, height))
        val outWidth = ((width * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val outHeight = ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return outWidth to outHeight
    }
}
