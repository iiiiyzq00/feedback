package com.feedback.repository.remote.station

import com.feedback.model.station.StationDualWriteEnvelope

/** 旧反馈平台写入端口；可靠双写 Worker 不感知令牌获取和一次性认证重试。 */
interface OldFbpGateway {
    /** 提交 Station RPC/旧平台请求；保持外部协议兼容，并把远端失败归一为安全错误码。 */
    fun submit(envelope: StationDualWriteEnvelope)
}
