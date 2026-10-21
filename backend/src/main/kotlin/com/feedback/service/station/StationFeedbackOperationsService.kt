package com.feedback.service.station

/** Station 运维查询 Service；仅返回无正文、无联系方式的任务元数据。 */
interface StationFeedbackOperationsService {
    /** 按可选状态查询显式分页，并返回兼容的运维响应结构。 */
    fun tasks(status: String?, page: Int, size: Int): Map<String, Any>
}
