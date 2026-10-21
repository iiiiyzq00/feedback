package com.feedback.controller.sdk

import com.feedback.common.ApiResponse
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.dto.sdk.*
import com.feedback.service.sdk.SdkAttachmentService
import com.feedback.service.sdk.SdkConfigService
import com.feedback.service.sdk.SdkIngestService
import com.feedback.service.sdk.SdkTelemetryService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.security.MessageDigest

/**
 * SDK 反馈 HTTP 入口。
 *
 * 控制器只处理 HTTP 协议、原始请求体哈希和参数校验。反馈主体、附件与遥测分别依赖窄接口，
 * 防止 Web 层再次与某个承担全部职责的具体服务绑定。
 */
@RestController
@RequestMapping("/api/v1/sdk")
class SdkFeedbackController(
    private val ingestOperations: SdkIngestService,
    private val attachmentOperations: SdkAttachmentService,
    private val telemetryOperations: SdkTelemetryService,
    private val sdkConfigService: SdkConfigService,
    private val objectMapper: ObjectMapper,
    private val validator: Validator
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 接收反馈主体并同步返回 ACK。
     * 原始 UTF-8 字节参与签名计算，不能用 DTO 再序列化的结果替代。
     */
    @PostMapping("/feedback")
    fun submitFeedback(
        @RequestBody body: String,
        servletRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<SdkFeedbackAck>> {
        val appKey = servletRequest.getHeader("X-App-Key")
            ?: clientError(servletRequest, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_APP_KEY_MISSING")
        val signature = servletRequest.getHeader("X-Signature")
            ?: clientError(servletRequest, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_SIGNATURE_MISSING")
        val timestamp = servletRequest.getHeader("X-Timestamp")
            ?: clientError(servletRequest, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_TIMESTAMP_MISSING")
        val nonce = servletRequest.getHeader("X-Nonce")
            ?: clientError(servletRequest, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_NONCE_MISSING")

        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val request: SdkFeedbackRequest = try {
            objectMapper.readValue(body)
        } catch (error: com.fasterxml.jackson.core.JsonProcessingException) {
            clientError(servletRequest, ErrorCode.SDK_ENVELOPE_INVALID, "SDK_REQUEST_JSON_INVALID", cause = error)
        }
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            clientError(
                servletRequest,
                ErrorCode.BAD_REQUEST,
                "SDK_VALIDATION_FAILED",
                details = mapOf("fields" to violations.associate {
                    it.propertyPath.toString() to it.message
                })
            )
        }
        val clientIp = servletRequest.remoteAddr

        try {
            val ack = ingestOperations.ingestFeedback(
                request = request,
                appKey = appKey,
                signature = signature,
                timestamp = timestamp,
                nonce = nonce,
                method = servletRequest.method,
                path = servletRequest.requestURI,
                bodyBytes = bodyBytes,
                clientIp = clientIp
            )
            return ResponseEntity.ok(ApiResponse.success(ack))
        } catch (e: ClientException) {
            val sdkErrorCode = e.reasonCode ?: e.errorCode.name
            servletRequest.setAttribute("sdkErrorCode", sdkErrorCode)
            log.warn("SDK ingest failed: code={} reason={}", e.errorCode.name, sdkErrorCode)
            throw e
        }
    }

    @GetMapping("/feedback/{receipt_id}/status")
    /** 返回当前应用所属反馈的处理状态，不向 HTTP 响应暴露内部异常或其他应用数据。 */
    fun feedbackStatus(@PathVariable("receipt_id") receiptId:String,servletRequest:HttpServletRequest):ResponseEntity<ApiResponse<SdkFeedbackProgress>> {
        val headers=sdkHeaders(servletRequest)
        val emptyHash=MessageDigest.getInstance("SHA-256").digest(ByteArray(0)).joinToString(""){"%02x".format(it)}
        ingestOperations.authenticateSdkRequest(headers.appKey,headers.signature,headers.timestamp,headers.nonce,
            servletRequest.method,servletRequest.requestURI,emptyHash,servletRequest.remoteAddr)
        return ResponseEntity.ok(ApiResponse.success(ingestOperations.feedbackProgress(receiptId,headers.appKey)))
    }

    /**
     * 加密附件上传（异步，WorkManager 独立重试）
     */
    @PostMapping("/ingest/{receipt_id}/attachments")
    fun uploadAttachment(
        @PathVariable("receipt_id") receiptId: String,
        @RequestParam("metadata") metadata: String,
        @RequestParam("encrypted_file") file: org.springframework.web.multipart.MultipartFile,
        servletRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<SdkAttachmentAck>> {
        val headers = sdkHeaders(servletRequest)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(metadata.toByteArray(Charsets.UTF_8))
        file.inputStream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        ingestOperations.authenticateSdkRequest(
            headers.appKey, headers.signature, headers.timestamp, headers.nonce,
            servletRequest.method, servletRequest.requestURI,
            digest.digest().joinToString("") { "%02x".format(it) }, servletRequest.remoteAddr
        )
        val ack = attachmentOperations.ingestAttachment(receiptId, headers.appKey, metadata, file)
        return ResponseEntity.ok(ApiResponse.success(ack))
    }

    @PostMapping("/ingest/{receipt_id}/attachments/chunks")
    /** 接收 SDK 附件分片；校验签名、分片序号、大小与摘要后写入受控临时目录。 */
    fun uploadAttachmentChunk(
        @PathVariable("receipt_id") receiptId:String,
        @RequestParam("metadata") metadata:String,
        @RequestParam("chunk_index") chunkIndex:Int,
        @RequestParam("total_chunks") totalChunks:Int,
        @RequestParam("chunk_sha256") chunkSha256:String,
        @RequestParam("encrypted_chunk") chunk:org.springframework.web.multipart.MultipartFile,
        servletRequest:HttpServletRequest
    ):ResponseEntity<ApiResponse<SdkAttachmentAck>> {
        val headers=sdkHeaders(servletRequest)
        val digest=MessageDigest.getInstance("SHA-256").apply { update(metadata.toByteArray());update(chunkIndex.toString().toByteArray());update(totalChunks.toString().toByteArray());chunk.inputStream.use{input->val b=ByteArray(DEFAULT_BUFFER_SIZE);var n:Int;while(input.read(b).also{n=it}!=-1)update(b,0,n)} }.digest().joinToString(""){"%02x".format(it)}
        ingestOperations.authenticateSdkRequest(headers.appKey,headers.signature,headers.timestamp,headers.nonce,servletRequest.method,servletRequest.requestURI,digest,servletRequest.remoteAddr)
        return ResponseEntity.ok(ApiResponse.success(attachmentOperations.ingestAttachmentChunk(receiptId,headers.appKey,metadata,chunkIndex,totalChunks,chunkSha256,chunk)))
    }

    /**
     * 传输诊断事件批量上报
     */
    @PostMapping("/telemetry")
    fun reportTelemetry(
        @RequestBody body: String,
        servletRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<String>> {
        val headers = sdkHeaders(servletRequest)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
            .joinToString("") { "%02x".format(it) }
        ingestOperations.authenticateSdkRequest(
            headers.appKey, headers.signature, headers.timestamp, headers.nonce,
            servletRequest.method, servletRequest.requestURI, bodyHash, servletRequest.remoteAddr
        )
        val request: SdkTelemetryRequest = objectMapper.readValue(body)
        telemetryOperations.ingestTelemetry(request, headers.appKey, servletRequest.remoteAddr)
        return ResponseEntity.ok(ApiResponse.success("OK"))
    }

    /**
     * SDK 动态配置拉取
     */
    @GetMapping("/config")
    fun getConfig(
        @RequestParam("package_name") packageName: String
    ): ResponseEntity<ApiResponse<com.fasterxml.jackson.databind.JsonNode>> {
        return ResponseEntity.ok(ApiResponse.success(sdkConfigService.getConfig(packageName)))
    }

    // ============================================================
    // 辅助
    // ============================================================

    /** 处理 `sdkHeaders` HTTP 边界；只做协议转换、校验和主体提取，业务处理委托给 Service 接口。 */
    private fun sdkHeaders(request: HttpServletRequest): SdkHeaders = SdkHeaders(
        appKey = request.getHeader("X-App-Key")
            ?: clientError(request, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_APP_KEY_MISSING"),
        signature = request.getHeader("X-Signature")
            ?: clientError(request, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_SIGNATURE_MISSING"),
        timestamp = request.getHeader("X-Timestamp")
            ?: clientError(request, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_TIMESTAMP_MISSING"),
        nonce = request.getHeader("X-Nonce")
            ?: clientError(request, ErrorCode.SDK_SIGNATURE_INVALID, "SDK_NONCE_MISSING")
    )

    /** 处理 `clientError` HTTP 边界；只做协议转换、校验和主体提取，业务处理委托给 Service 接口。 */
    private fun clientError(
        request: HttpServletRequest,
        errorCode: ErrorCode,
        reasonCode: String,
        details: Map<String, Any?> = emptyMap(),
        cause: Throwable? = null
    ): Nothing {
        request.setAttribute("sdkErrorCode", reasonCode)
        throw ClientException(errorCode, reasonCode, details, cause)
    }

/** 封装已通过格式校验的 SDK 安全请求头，避免在业务方法间散落裸字符串参数。 */
    private data class SdkHeaders(
        val appKey: String, val signature: String, val timestamp: String, val nonce: String
    )
}
