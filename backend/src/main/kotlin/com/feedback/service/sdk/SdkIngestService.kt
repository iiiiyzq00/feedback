package com.feedback.service.sdk

import com.feedback.dto.sdk.SdkFeedbackAck
import com.feedback.dto.sdk.SdkFeedbackProgress
import com.feedback.dto.sdk.SdkFeedbackRequest

/** SDK 反馈主体接入服务契约；控制器只依赖此边界，不感知安全与持久化实现。 */
interface SdkIngestService {
    /** 受理 SDK 加密上报：先完成验签、防重放、限流与信封校验，再以业务键和命令指纹执行幂等持久化。 */
    fun ingestFeedback(
        request: SdkFeedbackRequest,
        appKey: String,
        signature: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
        clientIp: String
    ): SdkFeedbackAck

    /** 按回执查询 SDK 处理进度；校验调用方应用归属，禁止跨应用读取反馈状态。 */
    fun feedbackProgress(receiptId: String, appKey: String): SdkFeedbackProgress

    /** 复核业务键对应记录的命令指纹；同命令返回原回执，异命令返回幂等冲突。 */
    fun idempotentAck(appKey: String, clientFeedbackId: String): SdkFeedbackAck?

    /** 在数据库事务外完成 SDK 签名、防重放和限流校验，任一安全依赖异常均失败关闭。 */
    fun authenticateSdkRequest(
        appKey: String,
        signature: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        bodySha256: String,
        clientIp: String
    )
}
