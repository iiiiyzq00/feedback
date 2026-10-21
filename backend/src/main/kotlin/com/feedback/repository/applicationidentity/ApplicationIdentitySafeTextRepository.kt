package com.feedback.repository.applicationidentity

import com.feedback.model.applicationidentity.*

/** masking 通过文本的有界批量读取边界，仅供只读历史应用解析。 */
interface ApplicationIdentitySafeTextRepository {
    /** 批量读取安全文本；重复或意外 source ID 失败关闭。 */
    fun findPassingBySourceIds(sourceIds: Collection<Long>): Map<Long, ApplicationIdentitySafeText>

    companion object {
        const val MAX_BATCH_SIZE = 100
    }
}
