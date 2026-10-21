package com.feedback.repository.impl.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentityStationMapper
import com.feedback.model.applicationidentity.ApplicationAliasInput
import com.feedback.model.applicationidentity.ApplicationAliasType
import com.feedback.repository.applicationidentity.ApplicationIdentityReviewCandidateRepository
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import com.feedback.model.applicationidentity.ReviewedStationCandidate
import org.springframework.stereotype.Repository

/** Station 审核候选的 MyBatis 实现；展示名只定位候选组，不成为持久化 alias。 */
@Repository
class MyBatisApplicationIdentityReviewCandidateRepositoryImpl(
    private val stationMapper: ApplicationIdentityStationMapper,
    private val identities: ApplicationIdentityRepository
) : ApplicationIdentityReviewCandidateRepository {
    /** 候选查询多取一条探测越界，并只选择展示名下唯一尚未治理的 source_app_id。 */
    override fun resolve(displayName: String, expectedCount: Long): ReviewedStationCandidate {
        if (displayName.isBlank() ||
            displayName.length > ApplicationAliasInput.MAX_ALIAS_LENGTH ||
            expectedCount <= 0
        ) {
            reviewInvalid("APPLICATION_REVIEW_CANDIDATE_INVALID")
        }
        val rows = stationMapper.findCandidatesByDisplayName(
            displayName,
            ApplicationIdentityReviewCandidateRepository.CANDIDATE_LIMIT + 1
        )
        if (rows.isEmpty()) {
            reviewInvalid("APPLICATION_REVIEW_CANDIDATE_NOT_FOUND")
        }
        if (rows.size > ApplicationIdentityReviewCandidateRepository.CANDIDATE_LIMIT) {
            reviewIntegrity("APPLICATION_REVIEW_CANDIDATE_SCOPE_EXCEEDED")
        }
        val normalizedRows = rows.mapNotNull { row ->
            val key = ApplicationAliasInput(
                ApplicationSourceChannel.STATION_FEEDBACK,
                ApplicationAliasType.STATION_SOURCE_APP_ID,
                row.sourceAppId
            ).normalized() ?: return@mapNotNull null
            key to row.itemCount
        }
        val keys = normalizedRows.map { it.first }.distinct()
        val active = identities.activeAliases(keys)
        val unbound = keys.filterNot(active::containsKey)
        if (unbound.size != 1) {
            reviewInvalid("APPLICATION_REVIEW_CANDIDATE_AMBIGUOUS")
        }
        val candidateKey = unbound.single()
        val actualCount = normalizedRows.filter { it.first == candidateKey }.sumOf { it.second }
        if (actualCount != expectedCount) {
            throw ServiceException(
                ErrorCode.APPLICATION_IDENTITY_CONFLICT,
                "APPLICATION_REVIEW_CANDIDATE_COUNT_CONFLICT"
            )
        }
        return ReviewedStationCandidate(candidateKey, actualCount)
    }
}

/** 审核候选本身不可用时返回稳定客户端错误。 */
private fun reviewInvalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

/** 候选集合越界代表来源完整性不满足，禁止截断后继续绑定。 */
private fun reviewIntegrity(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reasonCode)
