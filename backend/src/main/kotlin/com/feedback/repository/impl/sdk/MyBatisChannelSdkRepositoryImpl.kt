package com.feedback.repository.impl.sdk

import com.feedback.model.sdk.ChannelSdk
import com.feedback.mapper.core.CoreChannelSdkMapper
import com.feedback.repository.sdk.ChannelSdkRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
/** SDK 核心渠道仓储的 MyBatis 实现；提供有界批量读取并校验单行保存结果。 */
class MyBatisChannelSdkRepositoryImpl(private val mapper: CoreChannelSdkMapper) : ChannelSdkRepository {
    /** 按受控条件查询渠道状态；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findById(id: Long): Optional<ChannelSdk> = Optional.ofNullable(mapper.findById(id))
    /** 按受控条件查询渠道状态；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findAllById(ids: Iterable<Long>): List<ChannelSdk> {
        val values = ids.toList().distinct()
        return if (values.isEmpty()) emptyList() else mapper.findByIds(values)
    }
    /** 保存并校验持久化结果：渠道状态；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun save(row: ChannelSdk): ChannelSdk {
        check(mapper.upsert(row) in 0..2) { "CHANNEL_SDK_UPSERT_AFFECTED_ROWS_INVALID" }
        return row
    }
}
