package com.feedback.repository.impl.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentitySdkMapper
import com.feedback.mapper.applicationidentity.ApplicationIdentitySourceMapper
import com.feedback.mapper.applicationidentity.ApplicationIdentityStationMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentitySourcePo
import com.feedback.model.applicationidentity.ApplicationAliasInput
import com.feedback.model.applicationidentity.ApplicationAliasType
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.repository.applicationidentity.ApplicationIdentitySourceRepository
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import org.springframework.stereotype.Repository

/** MyBatis 来源事实实现；每张表均单独批量读取，不使用 JOIN 或逐行查询。 */
@Repository
class MyBatisApplicationIdentitySourceRepositoryImpl(
    private val sourceMapper: ApplicationIdentitySourceMapper,
    private val sdkMapper: ApplicationIdentitySdkMapper,
    private val stationMapper: ApplicationIdentityStationMapper
) : ApplicationIdentitySourceRepository {
    /** 读取 SDK/站端来源并在 Repository 边界校验游标、上限、顺序与成员完整性。 */
    override fun nextInputs(afterSourceId: Long, limit: Int): List<ApplicationIdentitySourceInput> {
        requireCursorAndLimit(afterSourceId, limit)
        return inputs(sourceMapper.findSupportedAfter(afterSourceId, limit), limit, false)
    }

    /** 四渠道读取不为缺少权威 alias 的渠道伪造证据，保持 UNRESOLVED 失败关闭。 */
    override fun nextFourChannelInputs(afterSourceId: Long, limit: Int): List<ApplicationIdentitySourceInput> {
        requireCursorAndLimit(afterSourceId, limit)
        return inputs(sourceMapper.findFourChannelAfter(afterSourceId, limit), limit, true)
    }

    /** 统一校验稳定游标和显式批量上限，防止无界 Mapper 调用。 */
    private fun requireCursorAndLimit(afterSourceId: Long, limit: Int) {
        if (afterSourceId < 0) {
            invalidInput("APPLICATION_SHADOW_CURSOR_INVALID")
        }
        if (limit !in 1..ApplicationIdentitySourceRepository.SHADOW_BATCH_SIZE) {
            invalidInput("APPLICATION_SHADOW_BATCH_INVALID")
        }
    }

    /** 批量装配来源事实；三个 Mapper 的缺失、重复、乱序及意外成员都会失败关闭。 */
    private fun inputs(
        sources: List<ApplicationIdentitySourcePo>,
        limit: Int,
        allowUnmappedChannels: Boolean
    ): List<ApplicationIdentitySourceInput> {
        if (sources.size > limit) {
            sourceIntegrity("APPLICATION_SOURCE_MAPPER_OVERFLOW")
        }
        if (!sources.map { it.id }.zipWithNext().all { (left, right) -> left < right }) {
            sourceIntegrity("APPLICATION_SOURCE_ORDER_INVALID")
        }
        if (sources.distinctBy { it.id }.size != sources.size) {
            sourceIntegrity("APPLICATION_SOURCE_DUPLICATED")
        }
        val channels = sources.map { source -> parseChannel(source.sourceChannel) }
        val permitted = if (allowUnmappedChannels) FOUR_CHANNELS else LEGACY_IDENTITY_CHANNELS
        if (channels.any { it !in permitted }) {
            sourceIntegrity("APPLICATION_SOURCE_CHANNEL_INVALID")
        }
        val sdkIds = sources.filter { it.sourceChannel == ApplicationSourceChannel.SDK.code }.map { it.id }
        val stationIds = sources.filter {
            it.sourceChannel == ApplicationSourceChannel.STATION_FEEDBACK.code
        }.map { it.id }
        val sdk = if (sdkIds.isEmpty()) {
            emptyMap()
        } else {
            checked(sdkMapper.findBySourceIds(sdkIds), sdkIds, { it.sourceId }, "APPLICATION_SDK")
        }
        val station = if (stationIds.isEmpty()) {
            emptyMap()
        } else {
            checked(stationMapper.findBySourceIds(stationIds), stationIds, { it.sourceId }, "APPLICATION_STATION")
        }
        return sources.map { source ->
            when (val channel = parseChannel(source.sourceChannel)) {
                ApplicationSourceChannel.SDK -> {
                    val row = sdk.getValue(source.id)
                    ApplicationIdentitySourceInput(
                        source.id,
                        channel,
                        listOf(
                            ApplicationAliasInput(channel, ApplicationAliasType.SDK_APP_ID, row.appId),
                            ApplicationAliasInput(channel, ApplicationAliasType.SDK_PACKAGE_NAME, row.packageName)
                        ),
                        row.packageName ?: row.appId
                    )
                }
                ApplicationSourceChannel.STATION_FEEDBACK -> {
                    val row = station.getValue(source.id)
                    ApplicationIdentitySourceInput(
                        source.id,
                        channel,
                        listOf(
                            ApplicationAliasInput(
                                channel,
                                ApplicationAliasType.STATION_SOURCE_APP_ID,
                                row.sourceAppId
                            )
                        ),
                        row.sourceAppName
                    )
                }
                ApplicationSourceChannel.JIRA,
                ApplicationSourceChannel.QUALITY,
                ApplicationSourceChannel.REDBLACK -> ApplicationIdentitySourceInput(source.id, channel, emptyList(), null)
            }
        }
    }

    /** 将数据库渠道编码严格转成领域类型，未知值归类为来源完整性故障。 */
    private fun parseChannel(code: String): ApplicationSourceChannel = runCatching {
        ApplicationSourceChannel.fromCode(code)
    }.getOrElse {
        sourceIntegrity("APPLICATION_SOURCE_CHANNEL_INVALID")
    }

    /** 验证批量从表结果精确覆盖请求 ID，禁止 N+1 回补缺失成员。 */
    private fun <T> checked(rows: List<T>, requested: List<Long>, key: (T) -> Long, code: String): Map<Long, T> {
        val indexed = rows.associateBy(key)
        if (indexed.size != rows.size) {
            sourceIntegrity("${code}_DUPLICATED")
        }
        if (indexed.keys.any { it !in requested }) {
            sourceIntegrity("${code}_UNEXPECTED_ID")
        }
        if (!indexed.keys.containsAll(requested)) {
            sourceIntegrity("${code}_MISSING")
        }
        return indexed
    }

    companion object {
        private val LEGACY_IDENTITY_CHANNELS = setOf(
            ApplicationSourceChannel.SDK,
            ApplicationSourceChannel.STATION_FEEDBACK
        )
        private val FOUR_CHANNELS = setOf(
            ApplicationSourceChannel.STATION_FEEDBACK,
            ApplicationSourceChannel.JIRA,
            ApplicationSourceChannel.QUALITY,
            ApplicationSourceChannel.REDBLACK
        )
    }
}

/** 将调用方输入错误转换为稳定错误码。 */
private fun invalidInput(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

/** 将数据库成员、顺序或编码异常转换为失败关闭的来源完整性错误。 */
private fun sourceIntegrity(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reasonCode)
