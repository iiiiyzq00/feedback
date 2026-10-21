package com.feedback.repository.impl.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentityPrimaryFactMapper
import com.feedback.model.po.applicationidentity.PrimaryProblemFactPo
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.PrimaryProblemClassificationStatus
import com.feedback.model.applicationidentity.PrimaryProblemFact
import com.feedback.repository.applicationidentity.PrimaryProblemFactRepository
import com.feedback.model.applicationidentity.ApplicationResolutionStatus
import org.springframework.stereotype.Repository

/** 主问题影子事实的 MyBatis 实现；所有集合读写均有 100 条硬上限。 */
@Repository
class MyBatisPrimaryProblemFactRepositoryImpl(
    private val mapper: ApplicationIdentityPrimaryFactMapper
) : PrimaryProblemFactRepository {
    /** 批量读取必须拒绝重复、意外来源和非法 ID，不以逐条查询补洞。 */
    override fun findBySourceIds(sourceIds: Collection<Long>): Map<Long, PrimaryProblemFact> {
        val ids = sourceIds.distinct().sorted()
        if (ids.size > ApplicationIdentityRepository.MAX_BATCH_SIZE || ids.any { it <= 0 }) {
            invalidInput("PRIMARY_FACT_LOOKUP_SCOPE_INVALID")
        }
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val rows = mapper.findBySourceIds(ids)
        val indexed = rows.associateBy(PrimaryProblemFactPo::feedbackSourceId)
        if (indexed.size != rows.size) {
            sourceIntegrity("PRIMARY_FACT_DUPLICATED")
        }
        if (indexed.keys.any { it !in ids }) {
            sourceIntegrity("PRIMARY_FACT_UNEXPECTED_SOURCE")
        }
        return indexed.mapValues { (_, row) -> row.domain() }
    }

    /** 一条批量 UPSERT 写入影子事实；调用方短写事务统一提交 identity、alias 与审计。 */
    override fun saveAll(rows: List<PrimaryProblemFact>) {
        if (rows.isEmpty()) {
            return
        }
        if (rows.size > ApplicationIdentityRepository.MAX_BATCH_SIZE) {
            invalidInput("PRIMARY_FACT_BATCH_TOO_LARGE")
        }
        if (rows.map { it.feedbackSourceId }.distinct().size != rows.size) {
            invalidInput("PRIMARY_FACT_SOURCE_DUPLICATED")
        }
        val affected = mapper.upsertAll(rows.map { it.po() })
        if (affected !in rows.size..(rows.size * 2)) {
            persistenceFailure("PRIMARY_FACT_UPSERT_COUNT_INVALID")
        }
    }

    /** 将数据库聚合编码严格转成解析状态，未知值按来源完整性错误关闭。 */
    override fun resolutionCounts(): Map<ApplicationResolutionStatus, Long> =
        mapper.countByResolutionStatus().associate { row ->
            val status = runCatching { ApplicationResolutionStatus.fromCode(row.resolutionStatus) }
                .getOrElse { sourceIntegrity("APPLICATION_RESOLUTION_STATUS_INVALID") }
            status to row.itemCount
        }

    /** 将事实 PO 的稳定字符串严格转换为领域类型。 */
    private fun PrimaryProblemFactPo.domain(): PrimaryProblemFact = PrimaryProblemFact(
        feedbackSourceId,
        applicationIdentityId,
        applicationPublicIdSnapshot,
        applicationDisplayNameSnapshot,
        applicationMappingVersion,
        runCatching { ApplicationResolutionStatus.fromCode(applicationResolutionStatus) }
            .getOrElse { sourceIntegrity("APPLICATION_RESOLUTION_STATUS_INVALID") },
        runCatching { PrimaryProblemClassificationStatus.valueOf(classificationStatus) }
            .getOrElse { sourceIntegrity("PRIMARY_PROBLEM_CLASSIFICATION_STATUS_INVALID") },
        resolutionRuleVersion,
        resolutionReasonCode,
        resolvedAliasCount
    )

    /** 将强类型事实转换为 MyBatis PO，保持既有数据库 code。 */
    private fun PrimaryProblemFact.po(): PrimaryProblemFactPo = PrimaryProblemFactPo(
        feedbackSourceId,
        applicationIdentityId,
        applicationPublicIdSnapshot,
        applicationDisplayNameSnapshot,
        applicationMappingVersion,
        applicationResolutionStatus.code,
        classificationStatus.code,
        resolutionRuleVersion,
        resolutionReasonCode,
        resolvedAliasCount
    )
}

/** 拒绝越界或非法批量参数。 */
private fun invalidInput(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

/** 数据库结果违反来源事实唯一性时失败关闭。 */
private fun sourceIntegrity(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reasonCode)

/** 批量 UPSERT 受影响数异常时停止状态推进。 */
private fun persistenceFailure(reasonCode: String): Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
