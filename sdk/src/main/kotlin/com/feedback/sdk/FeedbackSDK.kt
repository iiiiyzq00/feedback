package com.feedback.sdk

import android.app.Activity
import android.app.Application
import android.os.Build
import android.util.Base64
import com.feedback.sdk.config.SDKConfig
import com.feedback.sdk.capture.AttachmentInput
import com.feedback.sdk.capture.SdkLogBuffer
import com.feedback.sdk.capture.ScreenCapture
import com.feedback.sdk.crypto.EncryptionEnvelope
import com.feedback.sdk.crypto.RsaKeyWrapper
import com.feedback.sdk.db.FeedbackDatabase
import com.feedback.sdk.db.FeedbackStateMachine
import com.feedback.sdk.db.FeedbackStatus
import com.feedback.sdk.db.entity.PendingFeedback
import com.feedback.sdk.db.entity.AttachmentRecord
import com.feedback.sdk.network.ApiClient
import com.feedback.sdk.network.models.*
import com.feedback.sdk.ui.FeedbackDialog
import com.feedback.sdk.telemetry.TelemetryReporter
import com.feedback.sdk.telemetry.EnvironmentCollector
import com.feedback.sdk.privacy.ClientPrivacyFilter
import com.feedback.sdk.upload.AttachmentWorker
import com.feedback.sdk.upload.BodyUploadWorker
import com.feedback.sdk.upload.BodyUploadAckValidator
import com.feedback.sdk.upload.HistoricalBodyRetry
import com.feedback.sdk.identity.SdkIdentityContext
import com.feedback.sdk.identity.SdkIdentitySnapshot
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.io.File
import java.security.MessageDigest
import com.google.gson.Gson

/**
 * 学习机反馈 SDK — 公开 API 入口
 *
 * 初始化（Application.onCreate）：
 *   FeedbackSDK.init(this, SDKConfig(appId = "...", appKey = "...", serverUrl = "..."))
 *
 * 触发反馈（Activity 内）：
 *   FeedbackSDK.launch(activity, prefillData? = null)
 */
object FeedbackSDK {

    private const val SDK_VERSION = "1.0.0"
    private var config: SDKConfig? = null
    private var database: FeedbackDatabase? = null
    private var listener: FeedbackListener? = null
    private var appContext: android.content.Context? = null
    @Volatile private var remoteConfig = SDKRemoteConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ============================================================
    // 初始化
    // ============================================================

    fun init(context: Application, config: SDKConfig) {
        SdkIdentityContext.clear()
        this.config = config
        this.database = FeedbackDatabase.getInstance(context)
        this.appContext = context.applicationContext
        ApiClient.init(config)
        // 加载 RSA 公钥 — 用于封装 AES dataKey
        RsaKeyWrapper.loadPublicKey(
            config.serverPublicKeyPem ?: RsaKeyWrapper.DEFAULT_PUBLIC_KEY_PEM,
            config.serverTransportKeyId
        )
        BodyUploadWorker.enqueue(context)
        scope.launch {
            // WorkManager 任务可能因 APK 替换或 OEM 清理任务而
            // 被取消。排队中与已中断的附件上传都从 Room 恢复；
            // 服务端接口按附件 id 幂等。
            runCatching {
                val db = requireNotNull(database)
                db.attachmentDao().resetInterruptedUploads()
                db.attachmentDao().getFeedbackIdsReadyForUpload().forEach { feedbackId ->
                    AttachmentWorker.enqueue(context, feedbackId)
                }
            }.onFailure {
                SdkLogBuffer.record("WARN", "attachment_recovery_failed", it.javaClass.simpleName)
            }
            runCatching { ApiClient.get().getRemoteConfig(config.appId) }
                .onSuccess { response ->
                    response.body()?.data?.let {
                        remoteConfig = it
                        SdkLogBuffer.configure(it.logBufferMinutes, it.logMaxSizeKb)
                        if (!it.transportKeyId.isNullOrBlank() && !it.transportPublicKeyPem.isNullOrBlank()) {
                            RsaKeyWrapper.loadPublicKey(it.transportPublicKeyPem, it.transportKeyId)
                        }
                    }
                }
                .onFailure {
                    SdkLogBuffer.record("WARN", "remote_config_fetch_failed", it.javaClass.simpleName)
                }
        }
    }

    /**
     * 绑定用户（可选，登录后调用）
     */
    fun setUser(userId: String?) {
        SdkIdentityContext.setUser(userId)
    }

    /** 绑定可信宿主提供的真实受管设备序列号。 */
    fun setDeviceSerialNumber(deviceSerialNumber: String?) {
        SdkIdentityContext.setDeviceSerialNumber(deviceSerialNumber)
    }

    /**
     * 绑定来自宿主已认证资料的联系方式。只有用户勾选联系方式授权
     * 之后才会提交。
     */
    fun setContact(phone: String? = null, email: String? = null) {
        SdkIdentityContext.setContact(phone, email)
    }

    fun setListener(l: FeedbackListener) {
        this.listener = l
    }

    /**
     * 将宿主的非敏感诊断事件写入 SDK 环形缓冲区。
     * SDK 会自动脱敏，且只保留服务端配置的最近 1–60 分钟；不读取 logcat。
     */
    fun recordHostLog(level: String, event: String, detail: String? = null) {
        SdkLogBuffer.record(level, "host_$event", detail)
    }

    /**
     * 重新入队那些已经用尽瞬时网络/429/5xx 重试的本地历史正文。
     * 永久性的认证、协议与信封失败被刻意排除在外。
     */
    suspend fun retryHistoricalFailures(limit:Int=100):Int {
        val db=database?:return 0
        val context=appContext?:return 0
        return HistoricalBodyRetry(db.feedbackDao()) { BodyUploadWorker.enqueue(context) }.requeue(limit)
    }

    internal fun currentIssueTypes(): List<String> = remoteConfig.quickIssueTypes
    fun currentFormSchemaVersion():String = remoteConfig.formSchemaVersion
    fun currentFormFields():List<RemoteFormField> = remoteConfig.formFields
    internal fun currentRecordingDurationSeconds(): Int = remoteConfig.maxVideoDuration.coerceIn(1, 120)
    internal fun currentRecordingBitrateKbps(): Int = remoteConfig.maxVideoBitrateKbps.coerceIn(100, 8_000)

    // ============================================================
    // 代码触发
    // ============================================================

    /**
     * 显示反馈弹窗
     */
    fun launch(
        activity: Activity,
        prefillData: PrefillData? = null,
        captureCurrentActivity: Boolean = true,
        preCapturedScreenshot: ByteArray? = null,
        screenshotRecaptureHandler: ScreenshotRecaptureHandler? = null,
        screenRecordingHandler: ScreenRecordingHandler? = null,
        initialDraft: FeedbackDraft? = null,
        recordingRecoveryHandler: RecordingRecoveryHandler? = null,
        onClosed: (() -> Unit)? = null
    ) {
        val detectedPrefill = (prefillData ?: PrefillData()).copy(
            packageName = prefillData?.packageName ?: activity.packageName,
            functionModule = prefillData?.functionModule ?: activity.localClassName
        )
        val frozenDiagnosticLog = SdkLogBuffer.snapshotWindow(3, System.currentTimeMillis())
        if (preCapturedScreenshot != null) {
            FeedbackDialog(activity, detectedPrefill, preCapturedScreenshot, screenshotRecaptureHandler, screenRecordingHandler, initialDraft, recordingRecoveryHandler, frozenDiagnosticLog, onClosed).show()
            return
        }
        // 在 SDK 对话框挂载之前先截屏，这样反馈界面就不会
        // 意外出现在用户的截图里。
        // 系统级宿主在反馈其他包时必须关掉这一项，
        // 否则宿主自己的界面会被误标成本次反馈的来源证据。
        if (!captureCurrentActivity) {
            FeedbackDialog(activity, detectedPrefill, null, screenshotRecaptureHandler, screenRecordingHandler, initialDraft, recordingRecoveryHandler, frozenDiagnosticLog, onClosed).show()
            return
        }
        ScreenCapture.captureActivity(activity) { screenshot ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                FeedbackDialog(activity, detectedPrefill, screenshot, screenshotRecaptureHandler, screenRecordingHandler, initialDraft, recordingRecoveryHandler, frozenDiagnosticLog, onClosed).show()
            }
        }
    }

    // ============================================================
    // 内部：反馈提交流程
    // ============================================================

    /**
     * 提交反馈 — 加密 → 写 RoomDB → POST → ACK
     */
    suspend fun submit(
        description: String,
        prefill: PrefillData? = null,
        contactAllowed: Boolean = false,
        phone: String? = null,
        email: String? = null,
        attachments: List<AttachmentInput> = emptyList()
    ): SubmitResult {
        val cfg = config ?: return SubmitResult.Failure("SDK not initialized")
        val db = database ?: return SubmitResult.Failure("SDK not initialized")

        val clientFeedbackId = generateClientFeedbackId()
        val packageName = prefill?.packageName ?: requireNotNull(appContext).packageName
        val appVersion = prefill?.appVersion ?: runCatching {
            requireNotNull(appContext).packageManager.getPackageInfo(packageName,0).versionName
        }.getOrNull()
        val startedAt = System.currentTimeMillis()
        val identity = SdkIdentityContext.snapshot()

        try {
            // 诊断信息是所有提交路径的 SDK 默认能力，直接调用 submit()
            // 的集成也不例外。对话框或宿主已经提供过 LOG 时，
            // 不要再重复加一份。
            val resolvedAttachments = if (attachments.any { it.type == "LOG" }) {
                attachments
            } else {
                val logBytes = SdkLogBuffer.snapshot()
                if (logBytes.isEmpty()) attachments
                else attachments + AttachmentInput("LOG", "text/plain", logBytes)
            }
            // 1. 生成加密信封
            val dataKey = EncryptionEnvelope.generateDataKey()
            val nonce = EncryptionEnvelope.generateNonce()

            // 构建内部 payload（description + SN + 省市区 + input_methods）
            val environment = EnvironmentCollector.collect(requireNotNull(appContext))
            val innerPayload = buildInnerPayload(description, prefill, environment, identity)
            val encryptResult = EncryptionEnvelope.encrypt(
                innerPayload.toByteArray(Charsets.UTF_8),
                dataKey,
                nonce
            )
            val wrappedDataKey = RsaKeyWrapper.wrapDataKeyWithId(dataKey)

            val contactPayload = if (contactAllowed) {
                val contactNonce = EncryptionEnvelope.generateNonce()
                val contactText = buildContactPayload(
                    phone?.trim()?.takeIf(String::isNotEmpty) ?: identity.phone,
                    email?.trim()?.takeIf(String::isNotEmpty) ?: identity.email
                )
                val contactKey = EncryptionEnvelope.derivePurposeKey(dataKey, "contact")
                val contactResult = EncryptionEnvelope.encrypt(
                    contactText.toByteArray(Charsets.UTF_8), contactKey, contactNonce
                )
                EncryptedContactPayload(
                    nonce = Base64.encodeToString(contactNonce, Base64.NO_WRAP),
                    ciphertext = Base64.encodeToString(contactResult.ciphertext, Base64.NO_WRAP),
                    tag = Base64.encodeToString(contactResult.tag, Base64.NO_WRAP),
                    contactAllowed = true
                )
            } else null

            // 2. 写入 RoomDB
            val pendingFeedback = PendingFeedback(
                clientFeedbackId = clientFeedbackId,
                packageName = packageName,
                appVersion = appVersion,
                functionModule = prefill?.functionModule,
                issueType = prefill?.issueType,
                sourceEventTime = System.currentTimeMillis(),
                osVersion = Build.VERSION.RELEASE,
                networkType = prefill?.networkType ?: environment.networkType,
                triggerType = normalizeTriggerType(prefill?.triggerType),
                // Room 中只保存服务端公钥封装后的密钥，避免明文 data key 落盘。
                transportKeyId = wrappedDataKey.keyId,
                encryptedDataKey = wrappedDataKey.bytes,
                payloadNonce = nonce,
                payloadCiphertext = encryptResult.ciphertext,
                payloadTag = encryptResult.tag,
                contactAllowed = contactAllowed,
                contactNonce = contactPayload?.nonce?.let { Base64.decode(it, Base64.NO_WRAP) },
                contactCiphertext = contactPayload?.ciphertext?.let { Base64.decode(it, Base64.NO_WRAP) },
                contactTag = contactPayload?.tag?.let { Base64.decode(it, Base64.NO_WRAP) },
                status = "PENDING_BODY"
            )
            db.feedbackDao().insert(pendingFeedback)

            // 明文附件字节绝不落盘。每个附件都有自己的用途密钥
            // 与 nonce，随后持久化到应用私有存储。
            val attachmentRecords = resolvedAttachments.map { input ->
                persistEncryptedAttachment(
                    clientFeedbackId = clientFeedbackId,
                    input = input,
                    dataKey = dataKey,
                    db = db
                )
            }

            listener?.onQueued(clientFeedbackId)

            // 3. 发送 HTTP 请求
            val encryptedPayload = EncryptedPayload(
                keyId = wrappedDataKey.keyId,
                // RSA-OAEP-256 封装 dataKey（只有服务端私钥能解开）
                encryptedDataKey = Base64.encodeToString(wrappedDataKey.bytes, Base64.NO_WRAP),
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(encryptResult.ciphertext, Base64.NO_WRAP),
                tag = Base64.encodeToString(encryptResult.tag, Base64.NO_WRAP)
            )

            val request = FeedbackRequest(
                clientFeedbackId = clientFeedbackId,
                packageName = packageName,
                appVersion = appVersion,
                functionModule = prefill?.functionModule,
                issueType = prefill?.issueType,
                sourceEventTime = isoUtcNow(),
                osVersion = Build.VERSION.RELEASE,
                networkType = prefill?.networkType ?: environment.networkType,
                triggerType = normalizeTriggerType(prefill?.triggerType),
                encryptedPayload = encryptedPayload,
                encryptedContactPayload = contactPayload,
                attachmentManifest = attachmentRecords.map { record ->
                    AttachmentManifestEntry(
                        clientAttachmentId = record.clientAttachmentId,
                        type = record.type,
                        sizeBytes = record.sizeBytes,
                        sha256 = record.sha256?.toHex()
                    )
                }.ifEmpty { null }
            )

            val response = ApiClient.get().submitFeedback(request)
            if (response.isSuccessful) {
                val wrapper = response.body()
                val ack = BodyUploadAckValidator.accepted(response,clientFeedbackId)
                if (ack != null) {
                    db.feedbackDao().update(
                        pendingFeedback.copy(
                            status = FeedbackStateMachine.requireTransition(
                                pendingFeedback.status,
                                FeedbackStatus.PENDING_ATTACHMENTS
                            ),
                            receiptId = ack.receiptId
                        )
                    )
                    listener?.onAccepted(clientFeedbackId, ack.receiptId)
                    SdkLogBuffer.record("INFO", "feedback_accepted", "client_feedback_id=$clientFeedbackId")

                    // 触发附件上传
                    appContext?.let { ctx ->
                        AttachmentWorker.enqueue(ctx, clientFeedbackId)
                    }

                    return SubmitResult.Success(clientFeedbackId, ack.receiptId)
                } else {
                    db.feedbackDao().update(pendingFeedback.copy(
                        status="PERMANENT_FAILED",lastErrorCode="ACK_INVALID",updatedAt=System.currentTimeMillis()
                    ))
                    listener?.onPermanentFailure(clientFeedbackId,"ACK_INVALID")
                    TelemetryReporter.report(
                        clientFeedbackId = clientFeedbackId,
                        failureStage = "BODY_ACK",
                        errorType = "EMPTY_ACK_BODY",
                        networkType = prefill?.networkType,
                        retryCount = 0,
                        appVersion = prefill?.appVersion,
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                    return SubmitResult.ServerError(wrapper?.code ?: 500, false)
                }
            } else {
                // 4xx 永久失败，5xx 可重试
                val isRetryable = response.code() in 500..599 || response.code() == 429
                TelemetryReporter.report(
                    clientFeedbackId = clientFeedbackId,
                    failureStage = "BODY_ACK",
                    errorType = "HTTP_${response.code()}",
                    networkType = prefill?.networkType,
                    retryCount = 0,
                    appVersion = prefill?.appVersion,
                    durationMs = System.currentTimeMillis() - startedAt
                )
                if (isRetryable) {
                    appContext?.let(BodyUploadWorker::enqueue)
                } else {
                    db.feedbackDao().update(pendingFeedback.copy(
                        status = "PERMANENT_FAILED",
                        lastErrorCode = "HTTP_${response.code()}",
                        updatedAt = System.currentTimeMillis()
                    ))
                    listener?.onPermanentFailure(clientFeedbackId, "HTTP_${response.code()}")
                }
                return SubmitResult.ServerError(response.code(), isRetryable)
            }
        } catch (e: Exception) {
            // 网络异常 → 标记 PENDING_BODY，等待自动重试
            db.feedbackDao().update(
                db.feedbackDao().getById(clientFeedbackId)?.copy(
                    lastErrorCode = "NETWORK_ERROR"
                ) ?: return SubmitResult.Failure(e.message ?: "Unknown error")
            )
            TelemetryReporter.report(
                clientFeedbackId = clientFeedbackId,
                failureStage = "BODY_NETWORK",
                errorType = e.javaClass.simpleName.ifBlank { "NETWORK_ERROR" },
                networkType = prefill?.networkType,
                retryCount = 0,
                appVersion = prefill?.appVersion,
                durationMs = System.currentTimeMillis() - startedAt
            )
            appContext?.let(BodyUploadWorker::enqueue)
            return SubmitResult.NetworkError(e.message ?: "Network error", true)
        }
    }

    suspend fun queryProgress(receiptId:String):FeedbackProgressResult = try {
        require(receiptId.isNotBlank()){"receiptId must not be blank"}
        val response=ApiClient.get().getFeedbackProgress(receiptId)
        val value=response.body()?.data
        if(response.isSuccessful&&value!=null)FeedbackProgressResult.Success(value)
        else FeedbackProgressResult.ServerError(response.code(),response.code() in 500..599||response.code()==429)
    } catch(error:Exception) {
        FeedbackProgressResult.NetworkError(error.message?:"Unknown error",true)
    }

    // ============================================================
    // 内部
    // ============================================================

    private fun generateClientFeedbackId(): String = UUID.randomUUID().toString()

    private fun isoUtcNow(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun buildInnerPayload(
        description: String,
        prefill: PrefillData?,
        environment: com.feedback.sdk.telemetry.DeviceEnvironment,
        identity: SdkIdentitySnapshot
    ): String {
        val controlledIdentity = buildMap {
            identity.userId?.let { put("user_id", it) }
            identity.deviceSerialNumber?.let { put("device_sn", it) }
        }
        return Gson().toJson(mapOf(
            "description" to ClientPrivacyFilter.filter(description),
            "tags" to (prefill?.tags ?: emptyList<String>()).distinct().take(20),
            "input_methods" to (prefill?.inputMethods ?: listOf("TEXT")),
            // 业务定位信息与反馈正文一起加密。它们由各宿主应用
            // 白名单限定，且绝不能包含答案/聊天文本。
            "business_context" to (ClientPrivacyFilter.filter(prefill?.businessContext ?: emptyMap()) + mapOf(
                "device_model" to environment.deviceModel,
                "network_type" to (environment.networkType ?: "UNKNOWN"),
                "battery_level" to (environment.batteryLevel?.toString() ?: ""),
                "storage_available_mb" to environment.storageAvailableMb.toString()
            ) + controlledIdentity)
        ))
    }

    private fun buildContactPayload(phone: String?, email: String?): String {
        return listOfNotNull(phone, email).joinToString(";")
    }

    private suspend fun persistEncryptedAttachment(
        clientFeedbackId: String,
        input: AttachmentInput,
        dataKey: ByteArray,
        db: FeedbackDatabase
    ): AttachmentRecord {
        require(input.bytes.isNotEmpty()) { "attachment must not be empty" }
        val safeBytes = if (input.mimeType.startsWith("text/") || input.mimeType.contains("json"))
            ClientPrivacyFilter.filter(input.bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        else input.bytes
        val clientAttachmentId = UUID.randomUUID().toString()
        val nonce = EncryptionEnvelope.generateNonce()
        val attachmentKey = EncryptionEnvelope.generateDataKey()
        val wrappedAttachmentKey = RsaKeyWrapper.wrapDataKey(attachmentKey)
        val encrypted = EncryptionEnvelope.encrypt(safeBytes, attachmentKey, nonce)
        // FBA2 使用独立封装的附件密钥，因此延迟上传
        // 不依赖反馈正文本密钥的生命周期。
        val envelope = "FBA2".toByteArray(Charsets.US_ASCII) + nonce +
            encrypted.ciphertext + encrypted.tag
        val directory = File(requireNotNull(appContext).filesDir, "feedback_sdk/attachments")
        check(directory.exists() || directory.mkdirs()) { "cannot create attachment directory" }
        val file = File(directory, "$clientAttachmentId.enc")
        file.outputStream().buffered().use { it.write(envelope) }
        val record = AttachmentRecord(
            clientAttachmentId = clientAttachmentId,
            clientFeedbackId = clientFeedbackId,
            type = input.type,
            mimeType = input.mimeType,
            sizeBytes = file.length(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(envelope),
            encryptedDataKey = wrappedAttachmentKey,
            localPath = file.absolutePath,
            parentAttachmentId = input.parentAttachmentId,
            annotationJson = input.annotationJson
        )
        db.attachmentDao().insert(record)
        input.bytes.fill(0)
        attachmentKey.fill(0)
        return record
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

// ============================================================
// 公共类型
// ============================================================

data class PrefillData(
    val packageName: String? = null,
    val appVersion: String? = null,
    val functionModule: String? = null,
    val issueType: String? = null,
    val triggerType: String? = null,
    val networkType: String? = null,
    val inputMethods: List<String> = listOf("TEXT"),
    val tags: List<String> = emptyList(),
    val businessContext: Map<String, String> = emptyMap()
)

sealed class SubmitResult {
    data class Success(val clientFeedbackId: String, val receiptId: String) : SubmitResult()
    data class ServerError(val httpCode: Int, val retryable: Boolean) : SubmitResult()
    data class NetworkError(val message: String, val retryable: Boolean) : SubmitResult()
    data class Failure(val message: String) : SubmitResult()
}

sealed class FeedbackProgressResult {
    data class Success(val progress:com.feedback.sdk.network.models.FeedbackProgress):FeedbackProgressResult()
    data class ServerError(val httpCode:Int,val retryable:Boolean):FeedbackProgressResult()
    data class NetworkError(val message:String,val retryable:Boolean):FeedbackProgressResult()
}

interface FeedbackListener {
    fun onQueued(clientFeedbackId: String)
    fun onAccepted(clientFeedbackId: String, receiptId: String)
    fun onPermanentFailure(clientFeedbackId: String, reasonCode: String)
}

fun interface ScreenshotRecaptureHandler {
    fun request(callback: (ByteArray?) -> Unit)
}

interface ScreenRecordingHandler {
    fun request(
        maxDurationSeconds: Int,
        bitrateKbps: Int,
        started: () -> Unit,
        completed: (ByteArray) -> Unit,
        error: (String) -> Unit
    ): Boolean
}

/** 录制期间 OEM 销毁透明宿主时，使用的可序列化内存表单快照。 */
data class FeedbackDraft(
    val description: String = "",
    val issueType: String? = null,
    val screenshot: ByteArray? = null,
    val attachScreenshot: Boolean = false,
    val recording: ByteArray? = null,
    val attachRecording: Boolean = false,
    val contactAllowed: Boolean = false
)

fun interface RecordingRecoveryHandler {
    fun recover(draft: FeedbackDraft, errorMessage: String?)
}
