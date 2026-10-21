package com.feedback.model.po.sync

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * 索引 outbox 任务快照。lease_holder + lease_generation 是本次领取的 fencing token，
 * 调用方必须把它们原样带到 complete/fail，否则租约过期后被别人接管时旧结果会覆盖新状态。
 */
data class IndexAiTaskPo(
    var id: Long = 0,
    var inputSnapshot: String = "",
    var retryCount: Int = 0,
    var leaseHolder: String? = null,
    var leaseGeneration: Long = 0
)

