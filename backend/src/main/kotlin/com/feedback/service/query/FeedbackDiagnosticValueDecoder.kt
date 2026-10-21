package com.feedback.service.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticIdentityFact
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticSdkFact
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticStationFact
import com.feedback.security.ContentVisibility
import com.feedback.security.WebPrincipal
import com.feedback.service.masking.ContactStorageCrypto
import org.springframework.stereotype.Component

/**
 * 将加密诊断事实转换为强类型响应的唯一边界。单条弹窗与列表批量投影共用本组件，确保密钥材料校验、
 * SDK JSON 约束、类型映射和技术/产品角色呈现规则不会出现两套实现。
 */
@Component
class FeedbackDiagnosticValueDecoder(
    private val crypto: ContactStorageCrypto,
    private val objectMapper: ObjectMapper
) {
    fun identities(rows: List<FeedbackDiagnosticIdentityFact>): List<FeedbackDiagnosticItemResponse> = rows.map { row ->
        validateCrypto(row.ciphertext, row.nonce, row.tag, row.keyId, row.algorithm)
        FeedbackDiagnosticItemResponse(
            row.identityType,
            decrypt(row.ciphertext!!, row.tag!!, row.nonce!!),
            "ENCRYPTED_VAULT"
        )
    }

    fun station(row: FeedbackDiagnosticStationFact?): List<FeedbackDiagnosticItemResponse> = buildList {
        row?.sourceUserId?.takeIf(String::isNotBlank)?.let {
            add(FeedbackDiagnosticItemResponse("USER_ID", it, "SOURCE_REFERENCE"))
        }
        row?.authenticatedUserId?.takeIf(String::isNotBlank)?.let {
            add(FeedbackDiagnosticItemResponse("AUTHENTICATED_USER_ID", it, "AUTHENTICATED_SNAPSHOT"))
        }
    }

    fun sdk(row: FeedbackDiagnosticSdkFact?): List<FeedbackDiagnosticItemResponse> {
        if (row == null) return emptyList()
        validateCrypto(row.ciphertext, row.nonce, row.tag, row.keyId, row.algorithm)
        val root = try {
            objectMapper.readTree(decrypt(row.ciphertext!!, row.tag!!, row.nonce!!))
        } catch (error: ServiceException) {
            throw error
        } catch (error: Exception) {
            throw ServiceException(
                ErrorCode.FEEDBACK_READ_UNAVAILABLE,
                "FEEDBACK_DIAGNOSTICS_JSON_INVALID",
                cause = error
            )
        }
        if (!root.isObject) {
            throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_DIAGNOSTICS_JSON_INVALID")
        }
        return buildList {
            root.properties().forEach { (key, node) ->
                add(FeedbackDiagnosticItemResponse(
                    diagnosticType(key), if (node.isTextual) node.asText() else node.toString(),
                    "ENCRYPTED_DIAGNOSTIC_VAULT"
                ))
            }
        }
    }

    fun present(items: List<FeedbackDiagnosticItemResponse>, principal: WebPrincipal) = items.map { item ->
        item.copy(value = ContentVisibility.present(item.type, item.value.orEmpty(), principal))
    }

    /**
     * 同一身份可能同时来自加密身份库和渠道快照。按类型与展示前原始值去重，既避免重复展示，
     * 又保留同类型的不同值以及 USER_ID / AUTHENTICATED_USER_ID 等不同业务语义。
     */
    fun distinctSemanticValues(items: List<FeedbackDiagnosticItemResponse>): List<FeedbackDiagnosticItemResponse> =
        items.distinctBy { item -> item.type.trim().uppercase() to item.value?.trim() }

    fun visibility(principal: WebPrincipal): String =
        if (ContentVisibility.canViewRaw(principal)) "RAW" else "PARTIALLY_HIDDEN"

    private fun validateCrypto(
        ciphertext: ByteArray?, nonce: ByteArray?, tag: ByteArray?, keyId: String?, algorithm: String?
    ) {
        if (ciphertext == null || nonce == null || tag == null) {
            throw ServiceException(
                ErrorCode.FEEDBACK_READ_UNAVAILABLE,
                "FEEDBACK_DIAGNOSTICS_CRYPTO_MATERIAL_INCOMPLETE"
            )
        }
        if (keyId != crypto.keyId) {
            throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_DIAGNOSTICS_KEY_UNAVAILABLE")
        }
        if (algorithm != "AES-256-GCM") {
            throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_DIAGNOSTICS_ALGORITHM_UNSUPPORTED")
        }
    }

    private fun diagnosticType(key: String) = when (key) {
        "app_internal_logs" -> "APPLICATION_LOG"
        "account_context" -> "ACCOUNT_CONTEXT"
        "private_file_manifest" -> "PRIVATE_FILE_MANIFEST"
        else -> key.uppercase()
    }

    private fun decrypt(ciphertext: ByteArray, tag: ByteArray, nonce: ByteArray): String = try {
        crypto.decrypt(ciphertext, tag, nonce)
    } catch (error: Exception) {
        throw ServiceException(
            ErrorCode.FEEDBACK_READ_UNAVAILABLE,
            "FEEDBACK_DIAGNOSTICS_DECRYPT_FAILED",
            cause = error
        )
    }
}
