package com.feedback.demo.universal

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import com.feedback.demo.BuildConfig
import com.feedback.demo.TransportPublicKey

/** SDK 表单的透明宿主；它绝不截取自己的窗口。 */
class UniversalFeedbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, UniversalFeedbackOverlayService::class.java).apply {
            action = UniversalFeedbackOverlayService.ACTION_HIDE
        })
        val recovery = UniversalFeedbackRecoveryRegistry.consume(intent.getStringExtra(EXTRA_RECOVERY_TOKEN))
        val source = recovery?.context
            ?: UniversalLaunchRegistry.consume(intent.getStringExtra(EXTRA_CONTEXT_TOKEN))
        val screenshot = if (recovery == null) {
            CapturedScreenshotRegistry.consume(intent.getStringExtra(EXTRA_SCREENSHOT_TOKEN))
        } else null
        if (source == null) {
            screenshot?.fill(0)
            Toast.makeText(this, "反馈来源已失效，请重新触发", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        SdkUniversalFeedbackGateway(
            serverUrl = BuildConfig.SDK_SERVER_URL,
            appSecret = BuildConfig.UNIVERSAL_FEEDBACK_SDK_SECRET,
            serverPublicKeyPem = TransportPublicKey.pemOrNull(),
            allowInsecurePrivateNetworkForTesting = BuildConfig.DEBUG
        ).openFeedback(this, source, screenshot, recovery?.draft, recovery?.errorMessage)
    }

    override fun onDestroy() {
        startService(Intent(this, UniversalFeedbackOverlayService::class.java).apply {
            action = UniversalFeedbackOverlayService.ACTION_SHOW
        })
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONTEXT_TOKEN = "contextToken"
        const val EXTRA_SCREENSHOT_TOKEN = "screenshotToken"
        const val EXTRA_RECOVERY_TOKEN = "recoveryToken"
    }
}
