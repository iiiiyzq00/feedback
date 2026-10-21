package com.feedback.mapper.feedback.listprojection

import com.feedback.model.po.feedback.listprojection.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface FeedbackListProjectionDirtyMapper {
    fun upsertDirty(
        @Param("sourceIds") sourceIds: List<Long>,
        @Param("dirtyReason") dirtyReason: String
    ): Int

    fun findClaimableForUpdate(
        @Param("preferredSourceIds") preferredSourceIds: List<Long>?,
        @Param("limit") limit: Int
    ): List<FeedbackListProjectionDirtyPo>

    fun markProcessing(
        @Param("rows") rows: List<FeedbackListProjectionDirtyVersionPo>,
        @Param("leaseOwner") leaseOwner: String,
        @Param("leaseSeconds") leaseSeconds: Long
    ): Int

    fun deleteClaimed(
        @Param("rows") rows: List<FeedbackListProjectionDirtyVersionPo>,
        @Param("leaseOwner") leaseOwner: String
    ): Int

    fun releaseClaimed(
        @Param("rows") rows: List<FeedbackListProjectionDirtyVersionPo>,
        @Param("leaseOwner") leaseOwner: String,
        @Param("delaySeconds") delaySeconds: Long
    ): Int

    fun markRetry(
        @Param("rows") rows: List<FeedbackListProjectionDirtyVersionPo>,
        @Param("leaseOwner") leaseOwner: String,
        @Param("delaySeconds") delaySeconds: Long,
        @Param("errorCode") errorCode: String,
        @Param("errorSummary") errorSummary: String
    ): Int

    fun markFailed(
        @Param("rows") rows: List<FeedbackListProjectionDirtyVersionPo>,
        @Param("leaseOwner") leaseOwner: String,
        @Param("errorCode") errorCode: String,
        @Param("errorSummary") errorSummary: String
    ): Int

    fun findFirstForUpdate(): FeedbackListProjectionDirtyPo?
}

