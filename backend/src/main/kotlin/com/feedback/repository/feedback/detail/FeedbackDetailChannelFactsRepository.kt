package com.feedback.repository.feedback.detail

import com.feedback.mapper.feedback.detail.FeedbackDetailConfigMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailIdentityMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailJiraMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailQualityMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailRedblackMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailSdkMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailStationMapper
import org.springframework.stereotype.Repository

/** 五渠道详情、诊断类型和外链配置事实。 */
@Repository
class FeedbackDetailChannelFactsRepository(
    private val support: FeedbackDetailChannelSupportRepository,
    private val channels: FeedbackDetailChannelRowsRepository
) {
    fun identityTypes(sourceId: Long): List<String> = support.identityTypes(sourceId)
    fun sdk(sourceId: Long) = channels.sdk(sourceId)
    fun jira(sourceId: Long) = channels.jira(sourceId)
    fun quality(sourceId: Long) = channels.quality(sourceId)
    fun redblack(sourceId: Long) = channels.redblack(sourceId)
    fun station(sourceId: Long) = channels.station(sourceId)
    fun activeConfigString(key: String) = support.activeConfigString(key)
}

/** 诊断类型与动态外链配置读取；两者不承载五渠道详情映射。 */
@Repository
class FeedbackDetailChannelSupportRepository(
    private val configMapper: FeedbackDetailConfigMapper,
    private val identityMapper: FeedbackDetailIdentityMapper
) {
    fun identityTypes(sourceId: Long): List<String> = identityMapper.findTypes(sourceId)
    fun activeConfigString(key: String) = configMapper.findActiveJsonString(key)
}

/** 五个物理渠道各自的单表行读取，保持显式渠道分支且不引入万能渠道服务。 */
@Repository
class FeedbackDetailChannelRowsRepository(
    private val sdkMapper: FeedbackDetailSdkMapper,
    private val jiraMapper: FeedbackDetailJiraMapper,
    private val qualityMapper: FeedbackDetailQualityMapper,
    private val redblackMapper: FeedbackDetailRedblackMapper,
    private val stationMapper: FeedbackDetailStationMapper
) {
    fun sdk(sourceId: Long) = sdkMapper.findBySourceId(sourceId)
    fun jira(sourceId: Long) = jiraMapper.findBySourceId(sourceId)
    fun quality(sourceId: Long) = qualityMapper.findBySourceId(sourceId)
    fun redblack(sourceId: Long) = redblackMapper.findBySourceId(sourceId)
    fun station(sourceId: Long) = stationMapper.findBySourceId(sourceId)
}
