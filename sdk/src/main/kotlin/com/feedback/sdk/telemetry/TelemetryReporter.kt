package com.feedback.sdk.telemetry

import android.util.Log
import com.feedback.sdk.network.ApiClient
import com.feedback.sdk.network.models.TelemetryEvent
import com.feedback.sdk.network.models.TelemetryRequest

internal object TelemetryReporter {
    private const val TAG = "TelemetryReporter"
    private const val SDK_VERSION = "1.0.0"

    suspend fun report(
        clientFeedbackId: String?,
        failureStage: String,
        errorType: String,
        networkType: String?,
        retryCount: Int,
        attachmentSizeBytes: Long? = null,
        appVersion: String? = null,
        durationMs: Long? = null
    ) {
        runCatching {
            ApiClient.get().reportTelemetry(
                TelemetryRequest(
                    events = listOf(
                        TelemetryEvent(
                            clientFeedbackId = clientFeedbackId,
                            failureStage = failureStage,
                            errorType = errorType.take(50),
                            networkType = networkType,
                            retryCount = retryCount,
                            attachmentSizeBytes = attachmentSizeBytes,
                            sdkVersion = SDK_VERSION,
                            appVersion = appVersion,
                            durationMs = durationMs
                        )
                    )
                )
            )
        }.onFailure {
            Log.d(TAG, "Telemetry report skipped: ${it.message}")
        }
    }
}
