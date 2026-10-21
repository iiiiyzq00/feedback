package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class MaskingEvidenceConfigPo(
    var id: Long = 0, var legacyId: Long? = null, var configVersion: String = "",
    var configSubtype: String? = null, var recordStatus: String = "", var approvalStatus: String? = null,
    var scopeKey: String? = null, var recordVersion: Long? = null, var sourceConfigId: Long? = null,
    var subjectUserId: Long? = null, var secondaryUserId: Long? = null, var effectiveFrom: LocalDateTime? = null,
    var effectiveTo: LocalDateTime? = null, var createdAt: LocalDateTime? = null,
    var storageReference: String? = null, var positiveCount: Int? = null, var negativeCount: Int? = null,
    var boundaryCount: Int? = null, var piiApprovalReference: String? = null, var reviewedBy: Long? = null,
    var nerF1: String? = null, var endToEndF1: String? = null, var highRiskRecall: String? = null,
    var falseMaskingRate: String? = null, var executionLogSha256: String? = null,
    var failureSummarySha256: String? = null, var approvedAt: String? = null
) { fun businessId(): Long = legacyId ?: id }

