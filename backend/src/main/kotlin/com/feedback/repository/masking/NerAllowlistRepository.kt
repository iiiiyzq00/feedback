package com.feedback.repository.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.masking.NerAllowlistInsertPo
import com.feedback.mapper.masking.NerAllowlistMapper
import org.springframework.stereotype.Repository

@Repository
class NerAllowlistRepository(private val mapper: NerAllowlistMapper) {
    fun activeTerms() = mapper.findActiveTerms()
    fun all() = mapper.findAll()
    fun nextVersion() = "allowlist-v${mapper.countVersions() + 1}"
    fun add(term: String, type: String, version: String, reason: String, actor: Long): Long {
        val row = NerAllowlistInsertPo(term = term, type = type, version = version, reason = reason, actor = actor)
        if(mapper.insert(row)!=1) throw ServiceException(ErrorCode.INTERNAL_ERROR,"NER_ALLOWLIST_INSERT_FAILED")
        return row.id
    }
    fun retire(id: Long, expectedVersion: Long, reason: String): Boolean = mapper.retire(id, expectedVersion, reason) == 1
}
