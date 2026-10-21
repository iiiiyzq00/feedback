package com.feedback.service.station

import com.feedback.dto.station.StationAcceptResult
import com.feedback.dto.station.StationApiVersion
import com.feedback.dto.station.StationFeedbackRequest
import com.feedback.model.station.PreparedStationIngest
import com.feedback.security.station.StationGatewayPrincipal

/** 旧反馈平台在线受理 Service；HTTP 与 RPC 入口共享相同幂等和安全处理语义。 */
interface StationFeedbackIngestService {
    /** 受理一个 V1/V2 请求；同幂等键同指纹稳定重放，异指纹返回冲突。 */
    fun accept(
        request: StationFeedbackRequest,
        version: StationApiVersion,
        idempotencyKey: String?,
        principal: StationGatewayPrincipal?
    ): StationAcceptResult
}
