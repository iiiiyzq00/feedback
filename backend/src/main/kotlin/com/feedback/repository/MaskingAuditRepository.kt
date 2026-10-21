package com.feedback.repository

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.masking.MaskingAudit
import org.springframework.stereotype.Repository
import com.feedback.mapper.masking.MaskingAuditMapper

@Repository
class MaskingAuditRepository(private val mapper: MaskingAuditMapper) {
    fun save(audit: MaskingAudit): MaskingAudit {
        if(mapper.insert(audit) != 1 || audit.id <= 0) throw ServiceException(ErrorCode.INTERNAL_ERROR,"MASKING_AUDIT_INSERT_FAILED")
        return audit
    }
}
