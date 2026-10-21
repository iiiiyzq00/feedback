package com.feedback.controller.station

import com.feedback.dto.station.*
import com.feedback.service.station.StationFeedbackIngestService
import com.feedback.security.station.StationGatewayPrincipal
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Station V1/V2 HTTP 入口；负责协议校验、主体提取和稳定响应转换。 */
@RestController
@RequestMapping("/v1/feedback")
class StationFeedbackController(private val service: StationFeedbackIngestService) {
    @PostMapping
    /** 受理 Station V1 HTTP 请求；仅完成入口校验和主体解析，业务处理委托给 Station Service 接口。 */
    fun receiveV1(@Valid @RequestBody body: StationFeedbackRequest, request: HttpServletRequest) =
        accepted(service.accept(body, StationApiVersion.V1, request.getHeader("Idempotency-Key"), principal(request)))

    @PostMapping("/new")
    /** 受理 Station V2 HTTP 请求；保持既有响应编码，并把认证后的命令交给 Station Service 接口。 */
    fun receiveV2(@Valid @RequestBody body: StationFeedbackRequest, request: HttpServletRequest) =
        accepted(service.accept(body, StationApiVersion.V2, request.getHeader("Idempotency-Key"), principal(request)))

    /** 把领域受理结果转换为稳定 HTTP 响应；不得泄露内部主键、密文或异常文本。 */
    private fun accepted(result: StationAcceptResult): ResponseEntity<StationFeedbackAcceptedResponse> = ResponseEntity.ok()
        .header("X-Feedback-Receipt-Id", result.receiptId)
        .body(StationFeedbackAcceptedResponse(code = 0, msg = "ok"))

    /** 从安全上下文提取 Station 主体；主体缺失或类型不符时立即失败关闭。 */
    private fun principal(request: HttpServletRequest) =
        (request.userPrincipal as? Authentication)?.principal as? StationGatewayPrincipal
}
