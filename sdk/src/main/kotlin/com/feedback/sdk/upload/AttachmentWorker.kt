package com.feedback.sdk.upload

import android.content.Context
import android.util.Log
import androidx.work.*
import com.feedback.sdk.db.FeedbackDatabase
import com.feedback.sdk.db.FeedbackStateMachine
import com.feedback.sdk.db.FeedbackStatus
import com.feedback.sdk.network.ApiClient
import com.feedback.sdk.telemetry.TelemetryReporter
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WorkManager 附件后台上传
 *
 * 主体 ACK 后，附件使用 receipt_id 独立上传。
 * 附件失败不回滚主体信封。
 */
class AttachmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AttachmentWorker"
        private const val MAX_RETRIES = 8
        private const val INPUT_CLIENT_FEEDBACK_ID = "client_feedback_id"

        fun enqueue(context: Context, clientFeedbackId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val work = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setInputData(workDataOf(INPUT_CLIENT_FEEDBACK_ID to clientFeedbackId))
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30L, TimeUnit.SECONDS
                )
                .addTag("attachment_$clientFeedbackId")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "upload_$clientFeedbackId",
                    ExistingWorkPolicy.REPLACE,
                    work
                )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = FeedbackDatabase.getInstance(applicationContext)
        val clientFeedbackId = inputData.getString(INPUT_CLIENT_FEEDBACK_ID)
            ?: return@withContext Result.failure()
        val pendingAttachments = db.attachmentDao().getPendingByFeedbackId(clientFeedbackId)

        if (pendingAttachments.isEmpty()) {
            Log.d(TAG, "No pending attachments")
            markCompletedIfDone(db, clientFeedbackId)
            return@withContext Result.success()
        }

        val api = ApiClient.get()
        val gson = Gson()

        var allSuccess = true
        for (attachment in pendingAttachments) {
            try {
                val file = File(attachment.localPath)
                if (!file.exists()) {
                    db.attachmentDao().update(
                        attachment.copy(status = "FAILED")
                    )
                    TelemetryReporter.report(
                        clientFeedbackId = clientFeedbackId,
                        failureStage = "ATTACHMENT_FILE",
                        errorType = "LOCAL_FILE_MISSING",
                        networkType = feedbackNetworkType(db, clientFeedbackId),
                        retryCount = attachment.retryCount,
                        attachmentSizeBytes = attachment.sizeBytes
                    )
                    continue
                }

                // 标记上传中
                db.attachmentDao().update(
                    attachment.copy(status = "UPLOADING")
                )

                // 找到关联的 receipt_id
                val feedback = db.feedbackDao().getById(attachment.clientFeedbackId)
                val receiptId = feedback?.receiptId
                if (receiptId == null) {
                    allSuccess = false
                    db.attachmentDao().update(attachment.copy(status = "PENDING"))
                    TelemetryReporter.report(
                        clientFeedbackId = clientFeedbackId,
                        failureStage = "ATTACHMENT_ACK",
                        errorType = "MISSING_RECEIPT_ID",
                        networkType = feedback?.networkType,
                        retryCount = attachment.retryCount,
                        attachmentSizeBytes = attachment.sizeBytes,
                        appVersion = feedback?.appVersion
                    )
                    continue
                }

                // 构建 metadata
                val metadata = mapOf(
                    "client_attachment_id" to attachment.clientAttachmentId,
                    "type" to attachment.type,
                    "mime" to attachment.mimeType,
                    "size_bytes" to attachment.sizeBytes,
                    "sha256" to (attachment.sha256?.joinToString("") { "%02x".format(it) } ?: "")
                    ,"encrypted_data_key" to (attachment.encryptedDataKey?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: "")
                    ,"algorithm_version" to attachment.algorithmVersion
                )

                val metadataBody = gson.toJson(metadata)
                    .toRequestBody("application/json".toMediaType())

                val chunkSize=1024*1024
                val totalChunks=((file.length()+chunkSize-1)/chunkSize).toInt().coerceAtLeast(1)
                var response:retrofit2.Response<com.feedback.sdk.network.models.ApiResponseWrapper<com.feedback.sdk.network.models.AttachmentAck>>?=null
                file.inputStream().buffered().use { input ->
                    repeat(totalChunks){index->
                        val buffer=ByteArray(minOf(chunkSize,(file.length()-index.toLong()*chunkSize).coerceAtLeast(0).toInt()))
                        var offset=0
                        while(offset<buffer.size){val n=input.read(buffer,offset,buffer.size-offset);if(n<0)break;offset+=n}
                        val bytes=if(offset==buffer.size)buffer else buffer.copyOf(offset)
                        val chunkHash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                        val digest=MessageDigest.getInstance("SHA-256").apply { update(gson.toJson(metadata).toByteArray());update(index.toString().toByteArray());update(totalChunks.toString().toByteArray());update(bytes) }.digest().joinToString(""){"%02x".format(it)}
                        response=api.uploadAttachmentChunk(receiptId,digest,metadataBody,index.toString().toRequestBody("text/plain".toMediaType()),totalChunks.toString().toRequestBody("text/plain".toMediaType()),chunkHash.toRequestBody("text/plain".toMediaType()),MultipartBody.Part.createFormData("encrypted_chunk","${file.name}.$index",bytes.toRequestBody(attachment.mimeType.toMediaType())))
                        if(response?.isSuccessful!=true)return@use
                    }
                }
                val finalResponse=requireNotNull(response)
                if (AttachmentUploadAckValidator.isAccepted(finalResponse, attachment.clientAttachmentId)) {
                    db.attachmentDao().update(
                        attachment.copy(status = "UPLOADED")
                    )
                    Log.d(TAG, "Uploaded: ${attachment.clientAttachmentId}")
                } else {
                    allSuccess = false
                    val newRetryCount = attachment.retryCount + 1
                    TelemetryReporter.report(
                        clientFeedbackId = clientFeedbackId,
                        failureStage = "ATTACHMENT_UPLOAD",
                        errorType = "HTTP_${finalResponse.code()}",
                        networkType = feedback.networkType,
                        retryCount = newRetryCount,
                        attachmentSizeBytes = attachment.sizeBytes,
                        appVersion = feedback.appVersion
                    )
                    if (newRetryCount >= MAX_RETRIES) {
                        db.attachmentDao().update(
                            attachment.copy(status = "FAILED", retryCount = newRetryCount)
                        )
                    } else {
                        db.attachmentDao().update(
                            attachment.copy(status = "PENDING", retryCount = newRetryCount)
                        )
                    }
                    Log.w(TAG, "Upload rejected or invalid ACK ${attachment.clientAttachmentId}: HTTP ${finalResponse.code()}")
                }
            } catch (e: Exception) {
                allSuccess = false
                val newRetryCount = attachment.retryCount + 1
                val feedback = db.feedbackDao().getById(attachment.clientFeedbackId)
                TelemetryReporter.report(
                    clientFeedbackId = clientFeedbackId,
                    failureStage = "ATTACHMENT_UPLOAD",
                    errorType = e.javaClass.simpleName.ifBlank { "UPLOAD_EXCEPTION" },
                    networkType = feedback?.networkType,
                    retryCount = newRetryCount,
                    attachmentSizeBytes = attachment.sizeBytes,
                    appVersion = feedback?.appVersion
                )
                db.attachmentDao().update(
                    attachment.copy(
                        status = if (newRetryCount >= MAX_RETRIES) "FAILED" else "PENDING",
                        retryCount = newRetryCount
                    )
                )
                Log.e(TAG, "Upload error: ${attachment.clientAttachmentId}", e)
            }
        }

        if (allSuccess) {
            markCompletedIfDone(db, clientFeedbackId)
            Result.success()
        } else Result.retry()
    }

    private suspend fun markCompletedIfDone(db: FeedbackDatabase, clientFeedbackId: String) {
        if (db.attachmentDao().countIncomplete(clientFeedbackId) == 0) {
            db.feedbackDao().getById(clientFeedbackId)?.let {
                db.feedbackDao().update(it.copy(
                    status = FeedbackStateMachine.requireTransition(it.status, FeedbackStatus.COMPLETED),
                    updatedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    private suspend fun feedbackNetworkType(db: FeedbackDatabase, clientFeedbackId: String): String? =
        db.feedbackDao().getById(clientFeedbackId)?.networkType
}
