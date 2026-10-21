package com.feedback.rpc.api;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Station 在线反馈 RPC 输入契约。
 * resolvedUserId 与 productCode 必须来自受信适配器认证上下文；userId 仅是来源声明，不用于授权。
 * 联系方式只进入公共加密身份组件，图片地址最多 9 个且由受限下载器校验。
 */
public class FeedbackRpcRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** V1 或 V2。 */ private String apiVersion = "";
    /** 8-100 字符业务幂等键；同键不同请求将失败。 */ private String idempotencyKey = "";
    /** 经适配器认证的产品编码，最长 100 字符。 */ private String productCode = "";
    /** 经适配器认证的用户标识，最长 100 字符。 */ private String resolvedUserId = "";
    /** 来源产品标识，最长 100 字符；不替代 productCode 权限上下文。 */ private String productId = "";
    /** 客户端应用版本，最长 50 字符。 */ private String appVersion = "";
    /** 反馈标题，最长 500 字符；新平台入库前脱敏。 */ private String title = "";
    /** 反馈正文，最长 100000 字符；新平台入库前脱敏。 */ private String content = "";
    /** V2 兼容联系方式或适配器解析值，最长 1024 字符；禁止返回普通出口。 */ private String phone = "";
    /** 来源平台，最长 50 字符。 */ private String platform = "";
    /** 来源反馈类型，最长 50 字符。 */ private String type = "";
    /** 来源子渠道，最长 50 字符。 */ private String channel = "";
    /** HTTPS 图片地址，最多 9 个，每个最长 2048 字符。 */ private List<String> photoUrlList = new ArrayList<String>();
    /** 历史动态属性 JSON；当前只作兼容输入，不进入普通字段出口。 */ private String feedbackAttributeRecordsJson = "[]";
    /** 来源声明用户标识，最长 100 字符；不作为认证身份。 */ private String userId = "";
    /** 来源补充描述，最长 1000 字符；新平台入库前脱敏。 */ private String description = "";
    /** 来源应用标识，最长 100 字符。 */ private String appId = "";
    /** 来源应用名称，最长 200 字符。 */ private String appName = "";

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getResolvedUserId() { return resolvedUserId; }
    public void setResolvedUserId(String resolvedUserId) { this.resolvedUserId = resolvedUserId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public List<String> getPhotoUrlList() { return photoUrlList; }
    public void setPhotoUrlList(List<String> photoUrlList) { this.photoUrlList = photoUrlList; }
    public String getFeedbackAttributeRecordsJson() { return feedbackAttributeRecordsJson; }
    public void setFeedbackAttributeRecordsJson(String feedbackAttributeRecordsJson) { this.feedbackAttributeRecordsJson = feedbackAttributeRecordsJson; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
}
