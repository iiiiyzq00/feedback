package com.feedback.demo.universal

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.Toast

/** 通知/深链跳板：在反馈 Activity 出现之前先完成截屏。 */
class UniversalTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val triggerType = intent.getStringExtra(EXTRA_TRIGGER_TYPE)
            ?: if (intent.data?.scheme == "feedback") "DEEPLINK" else "NOTIFICATION"
        val accepted = UniversalFeedbackLauncher.trigger(
            this,
            triggerType,
            beforeCapture = {
                startService(Intent(this, UniversalFeedbackOverlayService::class.java).apply {
                    action = UniversalFeedbackOverlayService.ACTION_HIDE
                })
            },
            onLaunchFailure = {
                startService(Intent(this, UniversalFeedbackOverlayService::class.java).apply {
                    action = UniversalFeedbackOverlayService.ACTION_SHOW
                })
            }
        )
        if (!accepted) Toast.makeText(this, "暂时无法识别当前应用，请稍后重试", Toast.LENGTH_SHORT).show()
        finish()
    }
    companion object { const val EXTRA_TRIGGER_TYPE = "triggerType" }
}
