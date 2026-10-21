package com.feedback.repository.sdk

import com.feedback.model.sdk.ChannelSdk
import java.util.Optional

/** SDK 标准化渠道事实仓储契约。 */
interface ChannelSdkRepository {
/** 按主键参数化查询记录；未命中语义由上层 Repository 统一决定。 */
    fun findById(id: Long): Optional<ChannelSdk>
/** 一次批量加载 SDK 渠道记录；输入必须去重且受显式数量上限约束。 */
    fun findAllById(ids: Iterable<Long>): List<ChannelSdk>
/** 保存已校验领域记录；实现必须校验影响行数并返回权威持久化结果。 */
    fun save(row: ChannelSdk): ChannelSdk
}
