package com.feedback.repository.normalization

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.normalization.FaultOccurrenceTimeContentMapper
import com.feedback.model.po.normalization.FaultOccurrenceTimeContentPo
import com.feedback.mapper.normalization.FaultOccurrenceTimeJiraMapper
import com.feedback.model.po.normalization.FaultOccurrenceTimeJiraPo
import com.feedback.mapper.normalization.FaultOccurrenceTimeQualityMapper
import com.feedback.model.po.normalization.FaultOccurrenceTimeQualityPo
import com.feedback.mapper.normalization.FaultOccurrenceTimeSourceMapper
import com.feedback.model.po.normalization.FaultOccurrenceTimeSourcePo
import com.feedback.mapper.normalization.FaultOccurrenceTimeStationMapper
import com.feedback.model.po.normalization.FaultOccurrenceTimeStationPo
import com.feedback.model.po.normalization.FaultOccurrenceTimeUpdatePo
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class FaultOccurrenceTimeUpdate(
    val sourceId: Long,
    val occurredAt: LocalDateTime?,
    val raw: String?,
    val precision: String?,
    val source: String,
    val resolution: String
)

/**
 * 故障时间回填的单表持久化边界。
 *
 * 所有集合在进入 Mapper 前统一去重并限制为 200 条；空集合不执行 SQL。跨表事实只在应用层组装。
 */
@Repository
class FaultOccurrenceTimeRepository(
    private val sourceMapper: FaultOccurrenceTimeSourceMapper,
    private val contentMapper: FaultOccurrenceTimeContentMapper,
    private val jiraMapper: FaultOccurrenceTimeJiraMapper,
    private val stationMapper: FaultOccurrenceTimeStationMapper,
    private val qualityMapper: FaultOccurrenceTimeQualityMapper
) {
    fun findPendingAfter(afterSourceId: Long, limit: Int): List<FaultOccurrenceTimeSourcePo> {
        if(afterSourceId<0) invalid("FAULT_TIME_CURSOR_INVALID")
        if(limit !in 1..MAX_BATCH_SIZE) invalid("FAULT_TIME_BATCH_LIMIT_INVALID")
        return sourceMapper.findPendingAfter(afterSourceId, limit).also { rows ->
            if(rows.size>limit) failed("FAULT_TIME_SOURCE_BATCH_TOO_LARGE")
            if(rows.any { it.id<=afterSourceId }) failed("FAULT_TIME_SOURCE_CURSOR_ORDER_INVALID")
            if(rows.zipWithNext().any { (left,right) -> left.id>=right.id }) failed("FAULT_TIME_SOURCE_ORDER_INVALID")
        }
    }

    fun contents(sourceIds: List<Long>): Map<Long, FaultOccurrenceTimeContentPo> =
        load(sourceIds, contentMapper::findBySourceIds, FaultOccurrenceTimeContentPo::sourceId)

    fun jira(sourceIds: List<Long>): Map<Long, FaultOccurrenceTimeJiraPo> =
        load(sourceIds, jiraMapper::findBySourceIds, FaultOccurrenceTimeJiraPo::sourceId)

    fun station(sourceIds: List<Long>): Map<Long, FaultOccurrenceTimeStationPo> =
        load(sourceIds, stationMapper::findBySourceIds, FaultOccurrenceTimeStationPo::sourceId)

    fun quality(sourceIds: List<Long>): Map<Long, FaultOccurrenceTimeQualityPo> =
        load(sourceIds, qualityMapper::findBySourceIds, FaultOccurrenceTimeQualityPo::sourceId)

    fun updateAll(commands: List<FaultOccurrenceTimeUpdate>) {
        if (commands.isEmpty()) return
        val ids = boundedIds(commands.map(FaultOccurrenceTimeUpdate::sourceId))
        if(ids.size != commands.size) invalid("FAULT_TIME_UPDATE_SOURCE_DUPLICATED")
        val updates = commands.map {
            FaultOccurrenceTimeUpdatePo(it.sourceId, it.occurredAt, it.raw, it.precision, it.source, it.resolution)
        }
        if(sourceMapper.updateAll(updates) != updates.size) failed("FAULT_TIME_SOURCE_UPDATE_FAILED")
    }

    private fun <T> load(sourceIds: List<Long>, query: (List<Long>) -> List<T>, key: (T) -> Long): Map<Long, T> {
        val ids = boundedIds(sourceIds)
        if (ids.isEmpty()) return emptyMap()
        val rows = query(ids)
        val result = rows.associateBy(key)
        if(result.size != rows.size) failed("FAULT_TIME_AUXILIARY_SOURCE_DUPLICATED")
        if(result.keys.any { it !in ids }) failed("FAULT_TIME_AUXILIARY_SOURCE_UNEXPECTED")
        return result
    }

    private fun boundedIds(sourceIds: List<Long>): List<Long> {
        val ids = sourceIds.distinct()
        if(ids.size>MAX_BATCH_SIZE) invalid("FAULT_TIME_BATCH_TOO_LARGE")
        if(ids.any { it<=0 }) invalid("FAULT_TIME_SOURCE_ID_INVALID")
        return ids
    }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun failed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)

    companion object {
        const val MAX_BATCH_SIZE = 200
    }
}
