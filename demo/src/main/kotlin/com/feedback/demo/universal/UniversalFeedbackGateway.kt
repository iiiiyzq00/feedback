package com.feedback.demo.universal

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.FeedbackDraft
import com.feedback.sdk.RecordingRecoveryHandler
import com.feedback.sdk.PrefillData
import com.feedback.sdk.ScreenshotRecaptureHandler
import com.feedback.sdk.ScreenRecordingHandler
import com.feedback.sdk.config.SDKConfig

data class UniversalFeedbackContext(
    val sourcePackage: String,
    val sourceAppName: String?,
    val sourceVersionName: String?,
    val sourceVersionCode: Long?,
    val sourceActivity: String?,
    val triggerType: String,
    val detectedAt: Long,
    val detectionMethod: String,
    val trustedSource: Boolean,
    val businessContext: Map<String, String> = emptyMap()
)

interface UniversalFeedbackGateway {
    fun openFeedback(
        activity: Activity,
        context: UniversalFeedbackContext,
        screenshot: ByteArray? = null,
        initialDraft: FeedbackDraft? = null,
        recoveryError: String? = null
    )
}

class SdkUniversalFeedbackGateway(
    private val serverUrl: String,
    private val appSecret: String,
    private val serverPublicKeyPem: String?,
    private val allowInsecurePrivateNetworkForTesting: Boolean
) : UniversalFeedbackGateway {
    override fun openFeedback(
        activity: Activity,
        context: UniversalFeedbackContext,
        screenshot: ByteArray?,
        initialDraft: FeedbackDraft?,
        recoveryError: String?
    ) {
        FeedbackSDK.init(
            context = activity.application,
            config = SDKConfig(
                appId = UNIVERSAL_APP_KEY,
                appKey = UNIVERSAL_APP_KEY,
                appSecret = appSecret,
                serverUrl = serverUrl,
                serverPublicKeyPem = serverPublicKeyPem,
                allowInsecurePrivateNetworkForTesting = allowInsecurePrivateNetworkForTesting
            )
        )
        FeedbackSDK.launch(
            activity = activity,
            prefillData = PrefillData(
                packageName = context.sourcePackage,
                appVersion = context.sourceVersionName,
                functionModule = "UNIVERSAL_APP_FEEDBACK",
                triggerType = context.triggerType,
                businessContext = context.businessContext + mapOf(
                    "source_app_name" to context.sourceAppName.orEmpty(),
                    "source_activity" to context.sourceActivity.orEmpty(),
                    "source_version_code" to (context.sourceVersionCode?.toString() ?: ""),
                    "source_detection_method" to context.detectionMethod,
                    "source_trusted" to context.trustedSource.toString(),
                    "source_detected_at" to context.detectedAt.toString()
                )
            ),
            captureCurrentActivity = false,
            preCapturedScreenshot = screenshot,
            screenshotRecaptureHandler = ScreenshotRecaptureHandler { callback ->
                ScreenCaptureSessionService.captureForEditor(activity, callback)
            },
            screenRecordingHandler = object : ScreenRecordingHandler {
                override fun request(
                    maxDurationSeconds: Int,
                    bitrateKbps: Int,
                    started: () -> Unit,
                    completed: (ByteArray) -> Unit,
                    error: (String) -> Unit
                ) = ScreenCaptureSessionService.record(
                    activity, maxDurationSeconds, bitrateKbps, started, completed, error
                )
            },
            initialDraft = initialDraft,
            recordingRecoveryHandler = RecordingRecoveryHandler { draft, errorMessage ->
                val token = UniversalFeedbackRecoveryRegistry.issue(context, draft, errorMessage)
                activity.applicationContext.startActivity(
                    Intent(activity.applicationContext, UniversalFeedbackActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        putExtra(UniversalFeedbackActivity.EXTRA_RECOVERY_TOKEN, token)
                    }
                )
            },
            onClosed = { activity.finish() }
        )
        recoveryError?.let {
            Toast.makeText(activity.applicationContext, it, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        // 当前后端已经预置了这个键。原始来源包在加密载荷里
        // 仍然独立可见。
        const val UNIVERSAL_APP_KEY = "universal-feedback-host"
    }
}
