package com.feedback.repository.feedback.access

import com.feedback.mapper.feedback.access.FeedbackAccessMapper
import org.springframework.stereotype.Repository

data class FeedbackAccessFacts(
    val sourceId: Long,
    val sourceChannel: String,
    val dataEnvironment: String,
    val functionModule: String?,
    val provinceCode: String?,
    val cityCode: String?,
    val maskingStatus: String,
    val deleted: Boolean,
    /**
     * 数据归属租户；由 `FeedbackAccessMapper.xml` 里两跳主键查找现算
     * （来源 → 主问题事实 → 应用身份），未解析时退到 `'DEFAULT'`。
     *
     * 为什么不在 `t_feedback_source` 上存一列：租户是派生事实，见 V245 第 4 节。
     */
    val tenant: String
)

@Repository
class FeedbackAccessRepository(
    private val mapper: FeedbackAccessMapper
) {
    /** 在线访问校验使用一个受控读侧 JOIN，避免每个入口串行读取来源和正文。 */
    fun findBySourceId(sourceId: Long): FeedbackAccessFacts? {
        val row = mapper.findBySourceId(sourceId) ?: return null
        return FeedbackAccessFacts(
            sourceId = row.sourceId,
            sourceChannel = row.sourceChannel,
            dataEnvironment = row.dataEnvironment,
            functionModule = row.functionModule,
            provinceCode = row.provinceCode,
            cityCode = row.cityCode,
            maskingStatus = row.maskingStatus,
            deleted = row.deleted,
            tenant = row.tenantKey
        )
    }
}
