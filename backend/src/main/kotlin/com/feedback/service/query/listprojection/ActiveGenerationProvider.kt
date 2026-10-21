package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionReadRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import org.springframework.stereotype.Component

enum class ProjectionCapability { LIST, PROBLEM_ANALYSIS }

/**
 * 为反馈公开读取固定唯一且验证完整的 ACTIVE generation。
 *
 * 调用方必须先开启 `REPEATABLE_READ` 一致性事务，使后续列表、成员、身份和安全正文查询都使用同一个 generation
 * 快照。纯查询入口使用只读事务；导出入口允许在同一事务写出站审计，但不得修改 generation 或投影事实。
 * 本组件只读取 generation 单表，不启用开关、不切换状态，也不把不完整 generation 回退到旧查询路径。
 *
 * `LIST` 要求构建数量、列表/成员 checksum、验证 epoch 和激活事实完整；`PROBLEM_ANALYSIS` 在此基础上还要求
 * 当前 analysis 版本、成员分析 checksum、身份 checksum 和身份行数完整。无 ACTIVE、重复 ACTIVE 或能力事实
 * 不完整时统一返回能力专属的 503 错误，避免返回跨 generation 或部分组装结果。
 */
@Component
class ActiveGenerationProvider(private val repository: FeedbackListProjectionReadRepository) {
    fun require(capability: ProjectionCapability): FeedbackListProjectionGenerationPo {
        val active = repository.activeGenerations()
        if (active.size != 1) unavailable(capability, "ACTIVE_GENERATION_INVALID")
        return active.single().takeIf { complete(it, capability) }
            ?: unavailable(capability, "GENERATION_INCOMPLETE")
    }

    /** LIST 完整性是所有公开读取的共同基线；问题分析只能在该基线上追加检查，不能以分析事实替代列表事实。 */
    private fun complete(row: FeedbackListProjectionGenerationPo, capability: ProjectionCapability): Boolean {
        val fence = row.buildFenceSourceId ?: return false
        val verifiedEpoch = row.verifiedMutationEpoch ?: return false
        val listComplete = row.id > 0 && row.generationStatus == "ACTIVE" && (row.buildCursorSourceId ?: 0L) == fence &&
            row.expectedSourceCount >= 0 && row.projectedSourceCount >= 0 && row.rejectedSourceCount >= 0 &&
            row.projectedSourceCount + row.rejectedSourceCount == row.expectedSourceCount &&
            row.contentChecksum.isChecksum() && row.verifiedMemberChecksum.isChecksum() && verifiedEpoch >= 0 &&
            row.cutoverMutationEpoch == verifiedEpoch && row.verifiedAt != null && row.activatedBy != null && row.activatedAt != null
        return listComplete && (capability == ProjectionCapability.LIST ||
            row.analysisProjectionVersion == FeedbackListProjectionRepository.ANALYSIS_PROJECTION_VERSION &&
            row.verifiedAnalysisChecksum.isChecksum() && row.verifiedAnalysisIdentityChecksum.isChecksum() &&
            row.projectedAnalysisIdentityCount >= 0)
    }

    private fun unavailable(capability: ProjectionCapability, suffix: String): Nothing {
        val prefix = if (capability == ProjectionCapability.LIST) "FEEDBACK_FEEDBACK_LIST" else "FEEDBACK_PROBLEM_ANALYSIS"
        throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "${prefix}_$suffix")
    }

    private fun String?.isChecksum(): Boolean = this != null && length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
}
