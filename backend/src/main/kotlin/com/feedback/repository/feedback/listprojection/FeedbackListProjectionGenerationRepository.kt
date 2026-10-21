package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionGenerationMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import org.springframework.stereotype.Repository

/** 投影 generation 的创建、锁定、验证和状态 CAS。 */
@Repository
class FeedbackListProjectionGenerationRepository(
    private val mapper: FeedbackListProjectionGenerationMapper
) {
    fun createBuilding(generationKey: String, buildFenceSourceId: Long, expectedSourceCount: Long, createdBy: Long): FeedbackListProjectionGenerationPo {
        if (generationKey.isBlank() || generationKey.length > 80) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_GENERATION_KEY_INVALID")
        if (buildFenceSourceId < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_FENCE_INVALID")
        if (expectedSourceCount < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_EXPECTED_COUNT_INVALID")
        if (createdBy <= 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_ACTOR_INVALID")
        val generation = FeedbackListProjectionGenerationPo(
            generationKey = generationKey,
            buildFenceSourceId = buildFenceSourceId,
            expectedSourceCount = expectedSourceCount,
            createdBy = createdBy
        )
        if (mapper.insertBuilding(generation) != 1) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_CREATE_FAILED")
        if (generation.id <= 0) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_ID_MISSING")
        return generation
    }

    fun lockByKey(generationKey: String): FeedbackListProjectionGenerationPo =
        mapper.findByKeyForUpdate(generationKey)
            ?: projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_NOT_FOUND")

    fun lockManagedGenerations(): List<FeedbackListProjectionGenerationPo> = mapper.findManagedForUpdate().also { rows ->
        if (rows != rows.sortedBy(FeedbackListProjectionGenerationPo::id)) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_LOCK_ORDER_INVALID")
        if (rows.count { it.generationStatus == "BUILDING" } > 1 || rows.count { it.generationStatus == "ACTIVE" } > 1 ||
            rows.count { it.generationStatus == "ROLLBACK_READY" } > 1) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_SLOT_INVALID")
    }

    fun markVerified(generationId: Long, epoch: Long, projectedCount: Long, rejectedCount: Long, contentChecksum: String,
                     memberChecksum: String, analysisChecksum: String, analysisIdentityChecksum: String, analysisIdentityCount: Long) {
        if (epoch < 0 || projectedCount < 0 || rejectedCount < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_VERIFICATION_FACTS_INVALID")
        listOf(contentChecksum, memberChecksum, analysisChecksum, analysisIdentityChecksum).forEach(::requireChecksum)
        if (analysisIdentityCount < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_VERIFICATION_FACTS_INVALID")
        if (mapper.markVerified(generationId, epoch, projectedCount, rejectedCount, contentChecksum, memberChecksum,
            FeedbackListProjectionRepository.ANALYSIS_PROJECTION_VERSION, analysisChecksum, analysisIdentityChecksum,
            analysisIdentityCount) != 1) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_VERIFY_CAS_FAILED")
    }

    fun extendBuildFence(generationId: Long, expectedFenceSourceId: Long, expectedSourceCount: Long,
                         nextFenceSourceId: Long, nextSourceCount: Long) {
        if (generationId <= 0 || expectedFenceSourceId < 0 || expectedSourceCount < 0 ||
            nextFenceSourceId < expectedFenceSourceId || nextSourceCount < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_CATCH_UP_FACTS_INVALID")
        if (mapper.extendBuildFence(generationId, expectedFenceSourceId, expectedSourceCount, nextFenceSourceId, nextSourceCount) != 1) {
            projectionBuildFailed("FEEDBACK_LIST_PROJECTION_CATCH_UP_CAS_FAILED")
        }
    }

    fun prepareActiveForRollback(generationId: Long, fenceSourceId: Long, baselineCount: Long, facts: FeedbackListProjectionVerifiedFacts) {
        if (fenceSourceId < 0 || baselineCount < 0 || facts.mutationEpoch < 0 || facts.projectedCount < 0 ||
            facts.rejectedCount < 0 || facts.analysisIdentityCount < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_VERIFICATION_FACTS_INVALID")
        listOf(facts.contentChecksum, facts.memberChecksum, facts.analysisChecksum, facts.analysisIdentityChecksum).forEach(::requireChecksum)
        if (mapper.prepareActiveForRollback(generationId, fenceSourceId, baselineCount, facts.mutationEpoch,
            facts.projectedCount, facts.rejectedCount, facts.contentChecksum, facts.memberChecksum,
            FeedbackListProjectionRepository.ANALYSIS_PROJECTION_VERSION, facts.analysisChecksum,
            facts.analysisIdentityChecksum, facts.analysisIdentityCount) != 1) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_ACTIVE_VERIFY_CAS_FAILED")
    }

    fun transition(generationId: Long, expectedStatus: String, nextStatus: String, actorId: Long?, cutoverEpoch: Long?,
                   failureCode: String?, failureSummary: String?) {
        val allowed = setOf("BUILDING" to "ACTIVE", "BUILDING" to "FAILED", "ACTIVE" to "ROLLBACK_READY",
            "ACTIVE" to "FAILED", "ROLLBACK_READY" to "ACTIVE", "ROLLBACK_READY" to "RETIRED")
        if ((expectedStatus to nextStatus) !in allowed) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_STATUS_TRANSITION_INVALID")
        if (nextStatus == "ACTIVE" && (actorId == null || actorId <= 0 || cutoverEpoch == null || cutoverEpoch < 0)) {
            invalidProjectionInput("FEEDBACK_LIST_PROJECTION_ACTIVATION_FACTS_INVALID")
        }
        if (nextStatus == "ROLLBACK_READY" && (cutoverEpoch == null || cutoverEpoch < 0)) {
            invalidProjectionInput("FEEDBACK_LIST_PROJECTION_ROLLBACK_READY_FACTS_INVALID")
        }
        if (nextStatus == "FAILED" && (failureCode.isNullOrBlank() || failureSummary.isNullOrBlank())) {
            invalidProjectionInput("FEEDBACK_LIST_PROJECTION_FAILURE_FACTS_INVALID")
        }
        if (mapper.transitionStatus(generationId, expectedStatus, nextStatus, actorId, cutoverEpoch, failureCode, failureSummary) != 1) {
            projectionBuildFailed("FEEDBACK_LIST_PROJECTION_STATUS_CAS_FAILED")
        }
    }

    fun lockBuilding(generationId: Long): FeedbackListProjectionGenerationPo {
        val generation = mapper.findForUpdate(generationId)
            ?: projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_NOT_FOUND")
        if (generation.generationStatus != "BUILDING") projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_NOT_BUILDING")
        return generation
    }

    fun lockWritableGenerations(): List<FeedbackListProjectionGenerationPo> = mapper.findWritableForUpdate().also { generations ->
        if (generations != generations.sortedBy(FeedbackListProjectionGenerationPo::id)) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_GENERATION_LOCK_ORDER_INVALID")
        if (generations.size > 2) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_WRITABLE_GENERATION_COUNT_INVALID")
        if (generations.map(FeedbackListProjectionGenerationPo::generationStatus).distinct().size != generations.size) {
            projectionBuildFailed("FEEDBACK_LIST_PROJECTION_WRITABLE_GENERATION_STATUS_DUPLICATED")
        }
    }

    fun advanceBuild(generationId: Long, cursorSourceId: Long, projectedDelta: Long, rejectedDelta: Long) {
        if (mapper.advanceBuild(generationId, cursorSourceId, projectedDelta, rejectedDelta) != 1) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_BUILD_ADVANCE_FAILED")
    }

    private fun requireChecksum(value: String) {
        if (value.length != 64 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_CHECKSUM_INVALID")
    }
}
