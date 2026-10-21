package com.feedback.rpc.api;

/** Station 在线反馈 Dubbo 兼容入口；认证身份由受信适配器填入请求的已解析身份字段。 */
public interface FeedbackPlatformRpcApi {
    /**
     * 受理一条 Station 在线反馈。
     *
     * @param request 已校验大小边界的兼容请求；不得包含未授权明文凭据
     * @return status=0 表示受理成功并返回稳定回执，status=1 表示失败且不泄露内部错误
     */
    FeedbackRpcResponse submit(FeedbackRpcRequest request);
}
