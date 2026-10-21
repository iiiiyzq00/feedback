package com.feedback.repository.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.masking.OutboundPrivacyAuditMapper
import com.feedback.mapper.masking.OutboundPrivacyOverrideMapper
import org.springframework.stereotype.Repository

@Repository
class OutboundPrivacyRepository(
    private val overrides: OutboundPrivacyOverrideMapper,
    private val audits: OutboundPrivacyAuditMapper
) {
    fun hasBoundOverride(sourceId: Long, version: String, hash: ByteArray): Boolean =
        runCatching { overrides.countBoundOverride(sourceId, version, hash) > 0 }.getOrDefault(false)
    fun reject(target: String, targetId: String, version: String, typesJson: String, fieldCount: Int) {
        if(audits.insertReject(target, targetId, version, typesJson, fieldCount) != 1) throw ServiceException(ErrorCode.OUTBOUND_PRIVACY_CHECK_FAILED,"OUTBOUND_PRIVACY_REJECT_AUDIT_FAILED")
    }
    fun override(sourceId: Long, version: String) { if(audits.insertOverride(sourceId, version) != 1) throw ServiceException(ErrorCode.OUTBOUND_PRIVACY_CHECK_FAILED,"OUTBOUND_PRIVACY_OVERRIDE_AUDIT_FAILED") }
}
