package com.feedback.demo.universal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

object UniversalFeedbackLauncher {
    fun trigger(
        context: Context,
        triggerType: String,
        beforeCapture: (() -> Unit)? = null,
        onLaunchFailure: (() -> Unit)? = null
    ): Boolean {
        val source = ForegroundAppDetector(context).detect(triggerType = triggerType) ?: return false
        open(context, source, beforeCapture, onLaunchFailure = onLaunchFailure)
        return true
    }

    fun open(
        context: Context,
        source: UniversalFeedbackContext,
        beforeCapture: (() -> Unit)? = null,
        captureScreen: Boolean = true,
        onLaunchFailure: (() -> Unit)? = null
    ) {
        beforeCapture?.invoke()
        val contextToken = UniversalLaunchRegistry.issue(source)
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                if (captureScreen && ScreenCaptureSession.state == ScreenCaptureSession.State.READY) {
                    context.startService(Intent(context, ScreenCaptureSessionService::class.java).apply {
                        action = ScreenCaptureSessionService.ACTION_CAPTURE
                        putExtra(ScreenCaptureSessionService.EXTRA_CONTEXT_TOKEN, contextToken)
                    })
                } else {
                    context.startActivity(Intent(context, UniversalFeedbackActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        putExtra(UniversalFeedbackActivity.EXTRA_CONTEXT_TOKEN, contextToken)
                    })
                }
            }.onFailure { onLaunchFailure?.invoke() }
        }, 250L)
    }
}
