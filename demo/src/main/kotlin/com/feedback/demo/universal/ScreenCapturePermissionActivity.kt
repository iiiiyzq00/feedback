package com.feedback.demo.universal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/** 用于建立可选的跨应用截屏会话的系统授权入口。 */
class ScreenCapturePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenCaptureSession.state == ScreenCaptureSession.State.READY) {
            Toast.makeText(this, "截图已授权", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ScreenCaptureSession.update(ScreenCaptureSession.State.STARTING)
        if (savedInstanceState == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
    }

    @Deprecated("MediaProjection consent is delivered by the platform Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureSessionService::class.java).apply {
                    action = ScreenCaptureSessionService.ACTION_START
                    putExtra(ScreenCaptureSessionService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureSessionService.EXTRA_RESULT_DATA, data)
                })
            }.onFailure {
                ScreenCaptureSession.update(ScreenCaptureSession.State.STOPPED)
                Toast.makeText(this, "截图授权启动失败，请重试", Toast.LENGTH_SHORT).show()
            }
        } else {
            ScreenCaptureSession.update(ScreenCaptureSession.State.STOPPED)
        }
        finish()
    }

    private companion object { const val REQUEST_CAPTURE = 28461 }
}
