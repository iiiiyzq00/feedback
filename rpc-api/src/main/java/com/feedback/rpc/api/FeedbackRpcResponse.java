package com.feedback.rpc.api;

import java.io.Serializable;

/** Station RPC 稳定响应契约；字段名、默认值和 serialVersionUID 均保持向后兼容。 */
public class FeedbackRpcResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 业务状态："0" 已受理，"1" 失败。 */ private String status = "1";
    /** 固定兼容消息：成功为 ok，失败为 failed；不返回内部异常。 */ private String message = "failed";
    /** 成功时的 URL-safe Base64 回执；失败时为空。 */ private String receiptId = "";

    public FeedbackRpcResponse() {}

    public FeedbackRpcResponse(String status, String message, String receiptId) {
        this.status = status;
        this.message = message;
        this.receiptId = receiptId;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
}
