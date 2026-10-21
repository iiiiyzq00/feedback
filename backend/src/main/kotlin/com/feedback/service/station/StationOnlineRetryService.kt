package com.feedback.service.station

import com.feedback.model.station.StationRetryRequest
import com.feedback.model.station.StationRetryResponse

/** Station 在线失败任务重试 Service；只接受已授权运维入口调用。 */
interface StationOnlineRetryService {
    /** 在一个短写事务内重新入队当前仍可重试的去重回执。 */
    fun retry(request: StationRetryRequest): StationRetryResponse
}
