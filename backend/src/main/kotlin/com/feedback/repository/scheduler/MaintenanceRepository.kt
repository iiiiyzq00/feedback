package com.feedback.repository.scheduler

import com.feedback.mapper.scheduler.MaintenanceAiTaskMapper
import com.feedback.model.po.scheduler.MaintenanceAttachmentCandidatePo
import com.feedback.mapper.scheduler.MaintenanceImportBatchMapper
import com.feedback.model.po.scheduler.MaintenanceSdkCipherCandidatePo
import com.feedback.model.po.scheduler.MaintenanceStationCipherCandidatePo
import org.springframework.stereotype.Repository

@Repository
class MaintenanceLeaseRepository(
    private val imports: MaintenanceImportBatchMapper,
    private val aiTasks: MaintenanceAiTaskMapper,
) {
    fun recoverImports() = imports.recoverExpiredLeases()
    fun recoverAiTasks() = aiTasks.recoverExpiredLeases()
}

@Repository
class SensitiveRetentionRepository(
    private val sdk: SdkSensitiveRetentionRepository,
    private val station: StationSensitiveRetentionRepository,
    private val imports: ImportSensitiveRetentionRepository,
    private val attachments: AttachmentSensitiveRetentionRepository
) {
    fun sdkCandidates(afterId: Long?, limit: Int): List<MaintenanceSdkCipherCandidatePo> = sdk.candidates(afterId, limit)
    fun activeAlertSourceIds(sourceIds: Collection<Long>): Set<Long> = sdk.activeAlertSourceIds(sourceIds)
    fun clearSdk(ids: Collection<Long>): Int = sdk.clear(ids)
    fun clearImportRows() = imports.clearImportRows()
    fun stationCandidates(afterId: Long?, limit: Int): List<MaintenanceStationCipherCandidatePo> =
        station.candidates(afterId, limit)
    fun maskingStatuses(sourceIds: Collection<Long>) = station.maskingStatuses(sourceIds)
    fun clearStation(ids: Collection<Long>): Int = station.clear(ids)
    fun clearAiSnapshots() = imports.clearAiSnapshots()
    fun clearExpiredContacts() = imports.clearExpiredContacts()
    fun clearFinishedOrExpiredUrls() = imports.clearFinishedOrExpiredUrls()
    fun expireUploadSessions(): Pair<Int, Int> = imports.expireUploadSessions()
    fun attachmentCandidates(limit: Int): List<MaintenanceAttachmentCandidatePo> = attachments.candidates(limit)
    fun markAttachmentsDeleted(ids: Collection<Long>): Int = attachments.markDeleted(ids)
}
