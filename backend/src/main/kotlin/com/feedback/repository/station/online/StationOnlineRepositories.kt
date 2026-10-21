package com.feedback.repository.station.online

import com.feedback.model.po.station.online.*

/** Station 在线来源事实仓储契约。 */
interface StationOnlineSourceRepository {
    /** 插入并在唯一键竞争后定位权威Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertOrLocate(row: StationOnlineSourcePo): Int
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun get(sourceId: Long): StationOnlineSourcePo
    /** 按游标和显式上限分页查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findPageAfterId(afterSourceId: Long, limit: Int): List<StationOnlineSourcePo>
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findByIds(sourceIds: List<Long>): List<StationOnlineSourcePo>
}

/** Station 在线安全正文仓储契约。 */
interface StationOnlineContentRepository {
    /** 插入已校验的Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insert(row: StationOnlineContentPo)
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIds(sourceIds: List<Long>): List<StationOnlineContentPo>
    /** 按前置状态条件更新 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markMaskingPass(row: StationOnlineContentPo)
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findMaskingReviewSourceIdsAfterId(afterSourceId: Long, limit: Int): List<Long>
}

/** Station 在线渠道状态、租约和 CAS 推进仓储契约。 */
interface StationOnlineChannelRepository {
    /** 插入已校验的Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insert(row: StationOnlineChannelPo)
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun find(sourceId: Long): StationOnlineChannelPo?
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun get(sourceId: Long): StationOnlineChannelPo
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIds(sourceIds: List<Long>): List<StationOnlineChannelPo>
    /** 查询 Station 运维视图：Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun operations(status: String?, page: Int, size: Int): Pair<List<StationOperationsTaskPo>, Long>
    /** 查询 Station 运维视图：Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun operationsSummary(): Map<String, Long>
    /** 锁定符合条件的可重试Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findRetryableByReceiptsForUpdate(receiptIds: List<ByteArray>): List<StationOnlineChannelPo>
    /** 按稳定顺序锁定并领取一个可处理任务；租约 owner 是后续续租和状态推进的必要条件。 */
    fun claimOne(leaseOwner: String): StationOnlineChannelPo?
    /** 以当前版本和租约条件领取记录；竞争失败返回未领取，不覆盖其他 Worker 的租约。 */
    fun claim(sourceId: Long, leaseOwner: String): Boolean
    /** 仅允许当前 lease owner 延长未过期租约；影响行数异常按租约丢失处理。 */
    fun renewLease(sourceId: Long, leaseOwner: String)
    /** 按前置状态条件更新 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markContactUnavailable(sourceId: Long, leaseOwner: String)
    /** 按当前状态或租约条件完成Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun complete(sourceId: Long, leaseOwner: String, unavailableAttachments: Int, identityStored: Boolean)
    /** 按当前状态或租约条件记录失败的 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun fail(sourceId: Long, leaseOwner: String, status: String, retryCount: Int, delaySeconds: Long,
             review: Boolean, errorCode: String, errorSummary: String)
    /** 按条件重置可重试Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun resetRetryable(sourceIds: List<Long>)
    /** 按前置状态条件更新 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markMaskingRecovered(sourceId: Long)
    /** 按前置状态条件更新 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markMaskingFailure(sourceId: Long, errorSummary: String)
}

/** Station 原始密文仓储契约。 */
interface StationOnlineRawPayloadRepository {
    /** 按唯一业务键幂等写入 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun upsert(row: StationOnlineRawPayloadPo): Int
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIds(sourceIds: List<Long>): List<StationOnlineRawPayloadPo>
}

/** Station 在线身份事实仓储契约。 */
interface StationOnlineIdentityRepository {
    /** 以唯一约束执行先写者获胜的Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertFirstWins(row: StationOnlineIdentityPo): Boolean
}

/** Station 附件事实及来源身份采用仓储契约。 */
interface StationOnlineAttachmentRepository {
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIdentity(sourceAttachmentId: String): StationOnlineAttachmentPo?
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceAndHash(sourceId: Long, hash: ByteArray): StationOnlineAttachmentPo?
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIdentities(sourceAttachmentIds: List<String>): List<StationOnlineAttachmentPo>
    /** 按受控条件查询 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceAndHashes(sourceId: Long, hashes: List<ByteArray>): List<StationOnlineAttachmentPo>
    /** 插入并在唯一键竞争后定位权威Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertOrLocate(row: StationOnlineAttachmentPo): StationOnlineAttachmentPo
    /** 以条件更新认领Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun adoptSourceIdentity(id: Long, sourceAttachmentId: String): Boolean
    /** 批量插入或定位唯一键对应的Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertOrLocateBatch(rows: List<StationOnlineAttachmentPo>): List<StationOnlineAttachmentPo>
    /** 以条件更新认领Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun adoptSourceIdentities(rows: List<StationAttachmentIdentityAdoptionPo>): Int
}

/** Station 新旧双写任务的幂等、租约和状态推进仓储契约。 */
interface StationDualWriteTaskRepository {
    /** 插入并在唯一键竞争后定位权威Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertOrLocate(row: StationDualWriteTaskPo): StationDualWriteTaskPo
    /** 按稳定顺序锁定并领取一个可处理任务；租约 owner 是后续续租和状态推进的必要条件。 */
    fun claimOne(leaseOwner: String): StationDualWriteTaskPo?
    /** 按前置状态条件更新 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markOldSucceeded(id: Long, leaseOwner: String)
    /** 按当前状态或租约条件完成Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markCompleted(id: Long, leaseOwner: String, sourceId: Long)
    /** 按当前状态或租约条件记录失败的 Station 在线记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun markFailure(id: Long, leaseOwner: String, overallStatus: String, retryCount: Int, failedStatus: String,
                    finalFailure: Boolean, delaySeconds: Long, errorCode: String, errorSummary: String)
}
