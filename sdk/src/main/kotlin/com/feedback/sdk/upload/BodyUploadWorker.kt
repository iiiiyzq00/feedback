package com.feedback.sdk.upload

import android.content.Context
import androidx.work.*
import com.feedback.sdk.db.FeedbackDatabase
import com.feedback.sdk.db.FeedbackStateMachine
import com.feedback.sdk.db.FeedbackStatus
import com.feedback.sdk.db.dao.AttachmentDao
import com.feedback.sdk.db.dao.FeedbackDao
import com.feedback.sdk.db.entity.PendingFeedback
import com.feedback.sdk.network.ApiClient
import com.feedback.sdk.network.FeedbackApi
import com.feedback.sdk.network.models.*
import com.feedback.sdk.telemetry.TelemetryReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 对首次网络调用之前存进 Room 的反馈正文做持久化重试。 */
class BodyUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = FeedbackDatabase.getInstance(applicationContext)
        val retryNeeded = BodyUploadProcessor(
            feedbackDao = db.feedbackDao(),
            attachmentDao = db.attachmentDao(),
            api = ApiClient.get(),
            onAccepted = { AttachmentWorker.enqueue(applicationContext, it) },
            onRetryError = { feedback, error, retryCount ->
                TelemetryReporter.report(
                    feedback.clientFeedbackId, "BODY_RETRY", error,
                    feedback.networkType, retryCount, appVersion = feedback.appVersion
                )
            }
        ).processPending()
        if (retryNeeded) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BodyUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "feedback_sdk_body_upload", ExistingWorkPolicy.KEEP, request
            )
        }
    }
}

internal class BodyUploadProcessor(
    private val feedbackDao: FeedbackDao,
    private val attachmentDao: AttachmentDao,
    private val api: FeedbackApi,
    private val onAccepted: (String) -> Unit = {},
    private val onRetryError: suspend (PendingFeedback, String, Int) -> Unit = { _, _, _ -> }
) {
    suspend fun processPending(): Boolean {
        val pending = feedbackDao.getPendingBodies()
        if (pending.isEmpty()) return false

        var retryNeeded = false
        for (feedback in pending) {
            val request = runCatching { feedback.toRequest(attachmentDao) }.getOrNull()
            if (request == null) {
                feedbackDao.update(feedback.copy(
                    status = "PERMANENT_FAILED", lastErrorCode = "LOCAL_ENVELOPE_INVALID",
                    updatedAt = System.currentTimeMillis()
                ))
                continue
            }
            try {
                val response = api.submitFeedback(request)
                val ack = BodyUploadAckValidator.accepted(response,feedback.clientFeedbackId)
                when {
                    ack != null -> {
                        feedbackDao.update(feedback.copy(
                            status = FeedbackStateMachine.requireTransition(
                                feedback.status, FeedbackStatus.PENDING_ATTACHMENTS
                            ),
                            receiptId = ack.receiptId,
                            lastErrorCode = null,
                            updatedAt = System.currentTimeMillis()
                        ))
                        onAccepted(feedback.clientFeedbackId)
                    }
                    response.code() == 429 || response.code() >= 500 -> {
                        val exhausted = feedback.retryCount + 1 >= MAX_RETRIES
                        retryNeeded = retryNeeded || !exhausted
                        feedbackDao.update(feedback.copy(
                            status = if (exhausted) "RETRY_EXHAUSTED" else feedback.status,
                            retryCount = feedback.retryCount + 1,
                            lastErrorCode = "HTTP_${response.code()}",
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                    response.isSuccessful -> feedbackDao.update(feedback.copy(
                        status = "PERMANENT_FAILED",
                        lastErrorCode = "ACK_STATUS_INVALID",
                        updatedAt = System.currentTimeMillis()
                    ))
                    else -> feedbackDao.update(feedback.copy(
                        status = "PERMANENT_FAILED",
                        lastErrorCode = response.safeSdkErrorCode(),
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            } catch (error: Exception) {
                val exhausted = feedback.retryCount + 1 >= MAX_RETRIES
                retryNeeded = retryNeeded || !exhausted
                feedbackDao.update(feedback.copy(
                    status = if (exhausted) "RETRY_EXHAUSTED" else feedback.status,
                    retryCount = feedback.retryCount + 1,
                    lastErrorCode = error.javaClass.simpleName,
                    updatedAt = System.currentTimeMillis()
                ))
                onRetryError(feedback, error.javaClass.simpleName, feedback.retryCount + 1)
            }
        }
        return retryNeeded
    }

    private suspend fun PendingFeedback.toRequest(attachmentDao: AttachmentDao): FeedbackRequest {
        val attachments = attachmentDao.getByFeedbackId(clientFeedbackId)
        return FeedbackRequest(
            clientFeedbackId = clientFeedbackId,
            packageName = packageName,
            appVersion = appVersion,
            functionModule = functionModule,
            issueType = issueType,
            sourceEventTime = ISO_UTC.format(Date(sourceEventTime)),
            osVersion = osVersion,
            networkType = networkType,
            triggerType = normalizeTriggerType(triggerType),
            encryptedPayload = EncryptedPayload(
                keyId = transportKeyId,
                encryptedDataKey = requireNotNull(encryptedDataKey).base64(),
                nonce = requireNotNull(payloadNonce).base64(),
                ciphertext = requireNotNull(payloadCiphertext).base64(),
                tag = requireNotNull(payloadTag).base64()
            ),
            encryptedContactPayload = if (contactAllowed) EncryptedContactPayload(
                nonce = requireNotNull(contactNonce).base64(),
                ciphertext = requireNotNull(contactCiphertext).base64(),
                tag = requireNotNull(contactTag).base64(),
                contactAllowed = true
            ) else null,
            attachmentManifest = attachments.map {
                AttachmentManifestEntry(
                    it.clientAttachmentId, it.type, it.sizeBytes,
                    it.sha256?.joinToString("") { byte -> "%02x".format(byte) }
                )
            }.ifEmpty { null }
        )
    }

    private fun ByteArray.base64(): String = toByteString().base64()

    /** 保留后端可机读的错误码，但不持久化它的消息或载荷。 */
    private fun retrofit2.Response<*>.safeSdkErrorCode(): String {
        val raw = runCatching { errorBody()?.string().orEmpty() }.getOrDefault("")
        val numericCode = Regex("\\\"code\\\"\\s*:\\s*(\\d{3,6})")
            .find(raw)?.groupValues?.getOrNull(1)
        val backendCode = when (numericCode) {
            "2001" -> "SDK_SIGNATURE_INVALID"
            "2002" -> "SDK_NONCE_REPLAY"
            "2003" -> "SDK_RATE_LIMITED"
            "2005" -> "SDK_ENVELOPE_INVALID"
            else -> numericCode?.let { "BACKEND_$it" }
        }
        return backendCode ?: "HTTP_${code()}"
    }

    companion object {
        private const val MAX_RETRIES = 8
        private val ISO_UTC = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    }
}
