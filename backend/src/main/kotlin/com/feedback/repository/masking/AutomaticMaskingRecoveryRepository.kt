package com.feedback.repository.masking

import com.feedback.mapper.masking.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.springframework.stereotype.Repository

data class MaskingClusterReconciliation(
    val sourceRecords: Long,
    val safeRecords: Long,
    val technicalFailures: Long,
    val clusteredSafeRecords: Long,
    val pendingClusterRecords: Long
)

@Repository
class AutomaticMaskingRecoveryRepository(
    private val sdk: MaskingRecoverySdkMapper,
    private val rows: MaskingRecoveryImportRowMapper,
    private val batches: MaskingRecoveryImportBatchMapper,
    private val contents: MaskingRecoveryContentMapper,
    private val links: MaskingRecoveryClusterLinkMapper
) {
    fun requeueLegacyReviews(): Int {
        val sdkCount = sdk.requeueLegacyReviews()
        val rowCount = rows.requeueLegacyReviews()
        if (rowCount > 0) {
            var after = 0L
            while (true) {
                val ids = rows.findRetryBatchIdsAfter(after, BATCH)
                if (ids.isEmpty()) break
                batches.requeue(ids)
                after = ids.last()
                if (ids.size < BATCH) break
            }
        }
        return sdkCount + rowCount
    }

    fun reconciliation(): MaskingClusterReconciliation {
        val counts = contents.counts()
        var clustered = 0L
        var after = 0L
        while (true) {
            val ids = contents.findSafeIdsAfter(after, BATCH)
            if (ids.isEmpty()) break
            clustered += links.findActiveMainSourceIds(ids).size
            after = ids.last()
            if (ids.size < BATCH) break
        }
        return MaskingClusterReconciliation(counts.sourceRecords, counts.safeRecords, counts.technicalFailures,
            clustered, (counts.safeRecords - clustered).coerceAtLeast(0))
    }

    companion object { private const val BATCH = 500 }
}
