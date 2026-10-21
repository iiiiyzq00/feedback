package com.feedback.repository

import com.feedback.model.imports.ImportBatch
import com.feedback.mapper.imports.ImportBatchMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.util.Optional
import java.time.LocalDateTime

/** 保持原服务方法语义的 `t_import_batch` 单表 MyBatis Repository。 */
@Repository
class ImportBatchRepository(private val mapper: ImportBatchMapper) {
    fun save(row: ImportBatch): ImportBatch {
        if (row.id == 0L) check(mapper.insert(row) == 1) else check(mapper.update(row) == 1)
        return row
    }
    fun findById(id: Long): Optional<ImportBatch> = Optional.ofNullable(mapper.findById(id))
    fun findByBatchId(batchId: String) = mapper.findByBatchId(batchId)
    fun findFirstBySourceChannelAndContentSha256(channel: String, hash: ByteArray) = mapper.findByContentHash(channel, hash)
    fun findFirstBySourceChannelAndNormalizedContentHash(channel: String, hash: ByteArray) = mapper.findByNormalizedHash(channel, hash)
    fun findFirstBySourceChannelAndOriginalFilenameOrderByCreatedAtDesc(channel: String, filename: String) = mapper.findLatestFilename(channel, filename)
    fun findTop10ByOrderByCreatedAtDesc() = mapper.findRecent(10)
    fun findAll(pageable: Pageable) = page(null, null, null, pageable)
    fun findAllBySourceChannel(channel: String, pageable: Pageable) = page(channel, null, null, pageable)
    fun findAllByBatchStatus(status: String, pageable: Pageable) = page(null, status, null, pageable)
    fun findAllBySourceChannelAndBatchStatus(channel: String, status: String, pageable: Pageable) = page(channel, status, null, pageable)
    fun findAllByGovernanceStatus(governance: String, pageable: Pageable) = page(null, null, governance, pageable)
    fun findAllBySourceChannelAndGovernanceStatus(channel: String, governance: String, pageable: Pageable) = page(channel, null, governance, pageable)
    fun findAllByBatchStatusAndGovernanceStatus(status: String, governance: String, pageable: Pageable) = page(null, status, governance, pageable)
    fun findAllBySourceChannelAndBatchStatusAndGovernanceStatus(channel: String, status: String, governance: String, pageable: Pageable) = page(channel, status, governance, pageable)
    fun activeIdsByChannel(channel: String) = mapper.findActiveIdsByChannel(channel)
    fun claimFair(workerId: String, excludedChannels: Set<String>, now: LocalDateTime): Pair<String, String>? {
        val row = mapper.findClaimable(excludedChannels) ?: return null
        check(mapper.claimById(row.id, workerId, now.plusMinutes(2), now) == 1)
        return row.batchId to row.sourceChannel
    }
    fun markFailure(batchId: String, errorCode: String, errorDetail: String, now: LocalDateTime) {
        val nextCount = (mapper.findRetryCountForUpdate(batchId) ?: 0) + 1
        val terminal = nextCount >= 3
        check(mapper.markFailure(
            batchId, if (terminal) "FAILED" else "PROCESSING", nextCount,
            if (terminal) null else now.plusSeconds((1 shl nextCount).toLong()),
            errorCode.take(50), errorDetail.take(500), if (terminal) now else null, now
        ) == 1)
    }

    private fun page(channel: String?, status: String?, governance: String?, pageable: Pageable): Page<ImportBatch> {
        val rows = mapper.findPage(channel, status, governance, pageable.offset, pageable.pageSize)
        return PageImpl(rows, pageable, mapper.countPage(channel, status, governance))
    }
}
