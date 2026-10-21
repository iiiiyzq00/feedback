package com.feedback.repository.applicationidentity

import com.feedback.model.applicationidentity.*

/** 人工审核候选的来源事实读取边界。 */
interface ApplicationIdentityReviewCandidateRepository {
    /** 将展示名候选组解析为唯一 Station 来源 alias，并以预期计数拒绝数据漂移。 */
    fun resolve(displayName: String, expectedCount: Long): ReviewedStationCandidate

    companion object {
        const val CANDIDATE_LIMIT = 100
    }
}
