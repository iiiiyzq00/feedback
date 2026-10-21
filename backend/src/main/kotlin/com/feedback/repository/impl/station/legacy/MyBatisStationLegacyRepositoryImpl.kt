package com.feedback.repository.impl.station.legacy

import com.feedback.mapper.station.legacy.*
import com.feedback.model.po.station.legacy.*
import com.feedback.repository.station.legacy.StationLegacyRepository
import org.springframework.stereotype.Repository

@Repository
/** Station Legacy 聚合仓储的 MyBatis 实现；统一有界查询、固定锁序和影响行数校验。 */
class MyBatisStationLegacyRepositoryImpl(
    private val sources: StationLegacySourceMapper,
    private val contents: StationLegacyContentMapper,
    private val channels: StationLegacyChannelMapper,
    private val rawPayloads: StationLegacyRawPayloadMapper,
    private val identities: StationLegacyIdentityMapper,
    private val attachments: StationLegacyAttachmentMapper
) : StationLegacyRepository {

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun sourcesByRecordIdsForUpdate(recordIds: List<String>): List<StationLegacySourcePo> =
        sources.findByRecordIdsForUpdate(boundedStrings(recordIds, 100))

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun sourcesByIds(sourceIds: List<Long>): Map<Long, StationLegacySourcePo> =
        sources.findByIds(boundedIds(sourceIds)).associateBy(StationLegacySourcePo::id)

    /** 插入已校验的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertSource(row: StationLegacySourcePo) {
        check(sources.insert(row) == 1 && row.id > 0) { "STATION_LEGACY_SOURCE_INSERT_FAILED" }
    }

    /** 通过条件 UPDATE 补齐空应用版本；仅接受零或一行受影响，异常行数立即失败关闭。 */
    override fun fillMissingAppVersion(sourceId: Long, appVersion: String): Boolean =
        sources.fillMissingAppVersion(positiveId(sourceId), bounded(appVersion, 50)) == 1

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun contents(sourceIds: List<Long>): Map<Long, StationLegacyContentPo> =
        contents.findBySourceIds(boundedIds(sourceIds)).associateBy(StationLegacyContentPo::sourceId)

    /** 插入已校验的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertContent(row: StationLegacyContentPo) {
        check(contents.insert(row) == 1) { "STATION_LEGACY_CONTENT_INSERT_FAILED" }
    }

    /** 校验并排序来源 ID 后调用 Mapper 加锁查询，确保批量导入使用一致锁顺序。 */
    override fun channelsForUpdate(sourceIds: List<Long>): Map<Long, StationLegacyChannelPo> =
        channels.findBySourceIdsForUpdate(boundedIds(sourceIds)).associateBy(StationLegacyChannelPo::sourceId)

    /** 校验游标和页大小后读取可恢复渠道锁定页，拒绝零值、负值和超过批量上限的请求。 */
    override fun recoveryPageForUpdate(afterSourceId: Long): List<StationLegacyChannelPo> {
        require(afterSourceId >= 0) { "STATION_LEGACY_RECOVERY_CURSOR_INVALID" }
        return channels.findRecoveryPageForUpdate(afterSourceId, StationLegacyRepository.MAX_BATCH_SIZE)
    }

    /** 插入已校验的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertChannel(row: StationLegacyChannelPo) {
        check(channels.insert(row) == 1) { "STATION_LEGACY_CHANNEL_INSERT_FAILED" }
    }

    /** 委托 Mapper 条件补齐渠道空字段，并把零行解释为无变化、一行解释为已补齐。 */
    override fun enrichChannel(row: StationLegacyChannelEnrichmentPo): Boolean = channels.enrichMissing(row) == 1

    /** 原子写入历史渠道恢复状态；要求恰好更新一行，避免静默覆盖不存在或已变化的记录。 */
    override fun applyChannelState(row: StationLegacyChannelStatePo) {
        check(channels.applyState(row) == 1) { "STATION_LEGACY_CHANNEL_STATE_CHANGED" }
    }

    /** 对有界来源 ID 查询原始载荷存在性，只返回 sourceId，避免把密文载荷传播到调用方。 */
    override fun rawSourceIds(sourceIds: List<Long>): Set<Long> =
        rawPayloads.findExistingSourceIds(boundedIds(sourceIds)).toSet()

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun identities(sourceIds: List<Long>): Map<Long, Set<String>> = identities.findBySourceIds(boundedIds(sourceIds))
        .groupBy(StationLegacyIdentityPo::sourceId)
        .mapValues { (_, rows) -> rows.map(StationLegacyIdentityPo::identityType).toSet() }

    /** 插入已校验的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertIdentity(row: StationLegacyIdentityPo): Boolean = identities.insertIgnore(row).also {
        check(it == 0 || it == 1) { "STATION_LEGACY_IDENTITY_INSERT_FAILED" }
    } == 1

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun sourcesWithEvidence(sourceIds: List<Long>): Set<Long> =
        attachments.findSourceIdsWithEvidence(boundedIds(sourceIds)).toSet()

    /** 校验集合边界：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    private fun boundedIds(values: List<Long>): List<Long> = values.distinct().sorted().also {
        require(it.isNotEmpty() && it.size <= StationLegacyRepository.MAX_BATCH_SIZE && it.all { id -> id > 0 }) {
            "STATION_LEGACY_SOURCE_IDS_INVALID"
        }
    }

    /** 校验集合边界：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    private fun boundedStrings(values: List<String>, maxLength: Int): List<String> = values.distinct().also {
        require(it.isNotEmpty() && it.size <= StationLegacyRepository.MAX_BATCH_SIZE && it.all { value -> value.isNotBlank() && value.length <= maxLength }) {
            "STATION_LEGACY_RECORD_IDS_INVALID"
        }
    }

    /** 校验正数数据库标识：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    private fun positiveId(value: Long): Long = value.also { require(it > 0) { "STATION_LEGACY_SOURCE_ID_INVALID" } }
    /** 校验集合边界：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    private fun bounded(value: String, maxLength: Int): String = value.also {
        require(it.isNotBlank() && it.length <= maxLength) { "STATION_LEGACY_VALUE_INVALID" }
    }
}
