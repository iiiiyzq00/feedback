package com.feedback.repository.impl.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentitySafeTextMapper
import com.feedback.model.applicationidentity.ApplicationIdentitySafeText
import com.feedback.repository.applicationidentity.ApplicationIdentitySafeTextRepository
import org.springframework.stereotype.Repository

/** 安全文本的 MyBatis 实现；SQL 只读取 masking PASS 且未删除记录。 */
@Repository
class MyBatisApplicationIdentitySafeTextRepositoryImpl(
    private val mapper: ApplicationIdentitySafeTextMapper
) : ApplicationIdentitySafeTextRepository {
    /** 一次批量查询精确限制请求成员，不回传或审计敏感正文。 */
    override fun findPassingBySourceIds(sourceIds: Collection<Long>): Map<Long, ApplicationIdentitySafeText> {
        val ids = sourceIds.distinct().sorted()
        if (ids.isEmpty()) {
            return emptyMap()
        }
        if (ids.size > ApplicationIdentitySafeTextRepository.MAX_BATCH_SIZE || ids.any { it <= 0 }) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_TEXT_LOOKUP_SCOPE_INVALID")
        }
        val rows = mapper.findPassingBySourceIds(ids)
        if (rows.size != rows.distinctBy { it.sourceId }.size) {
            sourceIntegrity("APPLICATION_SAFE_TEXT_DUPLICATED")
        }
        if (rows.any { it.sourceId !in ids }) {
            sourceIntegrity("APPLICATION_SAFE_TEXT_UNEXPECTED_SOURCE")
        }
        return rows.associate { it.sourceId to ApplicationIdentitySafeText(it.title, it.description) }
    }
}

/** 文本结果越过请求集合时失败关闭，防止敏感内容串行。 */
private fun sourceIntegrity(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reasonCode)
