package com.feedback.mapper.feedback.listprojection

import com.feedback.model.po.feedback.listprojection.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper
interface FeedbackListProjectionGenerationMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insertBuilding(generation: FeedbackListProjectionGenerationPo): Int
    fun findForUpdate(@Param("generationId") generationId: Long): FeedbackListProjectionGenerationPo?
    fun findWritableForUpdate(): List<FeedbackListProjectionGenerationPo>
    fun findManagedForUpdate(): List<FeedbackListProjectionGenerationPo>
    fun findByKeyForUpdate(@Param("generationKey") generationKey: String): FeedbackListProjectionGenerationPo?
    fun extendBuildFence(
        @Param("generationId") generationId: Long,
        @Param("expectedFenceSourceId") expectedFenceSourceId: Long,
        @Param("expectedSourceCount") expectedSourceCount: Long,
        @Param("nextFenceSourceId") nextFenceSourceId: Long,
        @Param("nextSourceCount") nextSourceCount: Long
    ): Int
    fun advanceBuild(
        @Param("generationId") generationId: Long,
        @Param("cursorSourceId") cursorSourceId: Long,
        @Param("projectedDelta") projectedDelta: Long,
        @Param("rejectedDelta") rejectedDelta: Long
    ): Int
    fun markVerified(
        @Param("generationId") generationId: Long,
        @Param("expectedEpoch") expectedEpoch: Long,
        @Param("projectedCount") projectedCount: Long,
        @Param("rejectedCount") rejectedCount: Long,
        @Param("contentChecksum") contentChecksum: String,
        @Param("memberChecksum") memberChecksum: String,
        @Param("analysisProjectionVersion") analysisProjectionVersion: Int,
        @Param("analysisChecksum") analysisChecksum: String,
        @Param("analysisIdentityChecksum") analysisIdentityChecksum: String,
        @Param("analysisIdentityCount") analysisIdentityCount: Long
    ): Int
    fun prepareActiveForRollback(
        @Param("generationId") generationId: Long,
        @Param("fenceSourceId") fenceSourceId: Long,
        @Param("baselineCount") baselineCount: Long,
        @Param("expectedEpoch") expectedEpoch: Long,
        @Param("projectedCount") projectedCount: Long,
        @Param("rejectedCount") rejectedCount: Long,
        @Param("contentChecksum") contentChecksum: String,
        @Param("memberChecksum") memberChecksum: String,
        @Param("analysisProjectionVersion") analysisProjectionVersion: Int,
        @Param("analysisChecksum") analysisChecksum: String,
        @Param("analysisIdentityChecksum") analysisIdentityChecksum: String,
        @Param("analysisIdentityCount") analysisIdentityCount: Long
    ): Int
    fun transitionStatus(
        @Param("generationId") generationId: Long,
        @Param("expectedStatus") expectedStatus: String,
        @Param("nextStatus") nextStatus: String,
        @Param("actorId") actorId: Long?,
        @Param("cutoverEpoch") cutoverEpoch: Long?,
        @Param("failureCode") failureCode: String?,
        @Param("failureSummary") failureSummary: String?
    ): Int
}

