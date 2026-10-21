package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListProjectionGenerationPo(
    var id: Long = 0,
    var generationKey: String = "",
    var generationStatus: String = "BUILDING",
    var buildCursorSourceId: Long? = null,
    var buildFenceSourceId: Long? = null,
    var expectedSourceCount: Long = 0,
    var projectedSourceCount: Long = 0,
    var rejectedSourceCount: Long = 0,
    var contentChecksum: String? = null,
    var verifiedMutationEpoch: Long? = null,
    var verifiedMemberChecksum: String? = null,
    var analysisProjectionVersion: Int = 0,
    var verifiedAnalysisChecksum: String? = null,
    var verifiedAnalysisIdentityChecksum: String? = null,
    var projectedAnalysisIdentityCount: Long = 0,
    var verifiedAt: LocalDateTime? = null,
    var cutoverMutationEpoch: Long? = null,
    var failureCode: String? = null,
    var failureSummary: String? = null,
    var createdBy: Long = 0,
    var activatedBy: Long? = null,
    var createdAt: LocalDateTime? = null,
    var updatedAt: LocalDateTime? = null,
    var activatedAt: LocalDateTime? = null,
    var completedAt: LocalDateTime? = null
)

