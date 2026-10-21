package com.feedback.mapper.imports

import com.feedback.model.imports.ImportBatch
import com.feedback.model.po.imports.ImportBatchClaimPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ImportBatchMapper {
    fun insert(row: ImportBatch): Int
    fun update(row: ImportBatch): Int
    fun findById(id: Long): ImportBatch?
    fun findByBatchId(batchId: String): ImportBatch?
    fun findByContentHash(@Param("sourceChannel") sourceChannel: String, @Param("hash") hash: ByteArray): ImportBatch?
    fun findByNormalizedHash(@Param("sourceChannel") sourceChannel: String, @Param("hash") hash: ByteArray): ImportBatch?
    fun findLatestFilename(@Param("sourceChannel") sourceChannel: String, @Param("filename") filename: String): ImportBatch?
    fun findRecent(limit: Int): List<ImportBatch>
    fun findPage(@Param("sourceChannel") sourceChannel: String?, @Param("batchStatus") batchStatus: String?,
                 @Param("governanceStatus") governanceStatus: String?, @Param("offset") offset: Long, @Param("limit") limit: Int): List<ImportBatch>
    fun countPage(@Param("sourceChannel") sourceChannel: String?, @Param("batchStatus") batchStatus: String?,
                  @Param("governanceStatus") governanceStatus: String?): Long
    fun findActiveIdsByChannel(sourceChannel: String): List<Long>
    fun findClaimable(@Param("excludedChannels") excludedChannels: Set<String>): ImportBatchClaimPo?
    fun claimById(@Param("id") id: Long, @Param("workerId") workerId: String,
                  @Param("leaseExpiresAt") leaseExpiresAt: LocalDateTime, @Param("updatedAt") updatedAt: LocalDateTime): Int
    fun findRetryCountForUpdate(batchId: String): Int?
    fun markFailure(@Param("batchId") batchId: String, @Param("status") status: String,
                    @Param("retryCount") retryCount: Int, @Param("nextRetryAt") nextRetryAt: LocalDateTime?,
                    @Param("errorCode") errorCode: String, @Param("errorDetail") errorDetail: String,
                    @Param("completedAt") completedAt: LocalDateTime?, @Param("updatedAt") updatedAt: LocalDateTime): Int
}
