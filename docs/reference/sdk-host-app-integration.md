# 通用反馈 SDK 宿主 App 接入

## 1. 引入 SDK

同一 Gradle 工程开发时：

```kotlin
dependencies {
    implementation(project(":sdk"))
}
```

外部 App 使用交付的 AAR 时：

```kotlin
dependencies {
    implementation(files("libs/feedback-sdk.aar"))
}
```

SDK 最低支持 Android 7.0（API 24）。Manifest 中的网络、录屏 Activity 和前台服务会通过
AAR manifest merger 自动合并，宿主不应复制 SDK 内部组件。

## 2. Application 初始化

每个宿主 App 必须由平台分配独立的 `appKey` 和 `appSecret`，不得跨 App 共用 secret。

```kotlin
class HostApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FeedbackSDK.init(
            this,
            SDKConfig(
                appId = packageName,
                appKey = BuildConfig.FEEDBACK_APP_KEY,
                appSecret = BuildConfig.FEEDBACK_APP_SECRET,
                serverUrl = BuildConfig.FEEDBACK_SERVER_URL,
                serverPublicKeyPem = loadFeedbackPublicKey(),
                allowInsecurePrivateNetworkForTesting = BuildConfig.DEBUG
            )
        )
        FeedbackSDK.setUser(currentUserIdOrNull)
        FeedbackSDK.setDeviceSerialNumber(managedDeviceSerialOrNull)
        FeedbackSDK.setContact(
            phone = authenticatedProfile.phone,
            email = authenticatedProfile.email
        )
    }
}
```

必填配置：

| 参数 | 来源 | 说明 |
|---|---|---|
| `appId` | 宿主 | 通常使用宿主 packageName |
| `appKey` | 平台分配 | 请求公开身份，服务端必须有对应映射 |
| `appSecret` | 平台安全分发 | 至少 16 字符，仅用于 HMAC，不随请求传输 |
| `serverUrl` | 平台分配 | 生产必须为 HTTPS |
| `serverPublicKeyPem` | 平台分配 | 与服务端 RSA 私钥配对，用于封装 AES dataKey |

`setUser` 和 `setDeviceSerialNumber` 的值会进入加密正文，服务端分别作为 `USER_ID` 和
`DEVICE_SN` 受控落库。设备 SN 只能来自宿主有权读取的受管设备资料；SDK 不会用 Android ID、
设备型号、IMEI 或本地资产编号冒充 SN。`setContact` 只在进程内保存宿主已认证账号的联系方式；
用户在反馈窗口主动勾选“允许工作人员就此问题联系我”后，手机号/邮箱才会进入独立加密联系信封。
登出或切换账号时应传入 `null` 清除对应值。

## 3. 页面触发反馈

```kotlin
FeedbackSDK.launch(
    activity = this,
    prefillData = PrefillData(
        packageName = packageName,
        appVersion = BuildConfig.VERSION_NAME,
        functionModule = "DRAWING_CANVAS",
        triggerType = "IN_APP_BUTTON",
        networkType = "WIFI",
        businessContext = mapOf(
            "screen_code" to "DRAWING_HOME",
            "record_id" to drawingId
        )
    )
)
```

`businessContext` 只放定位问题所需的白名单字段，不放聊天正文、答案、令牌、真实健康数据或儿童隐私。
`triggerType` 会去除首尾空白并转为大写，最长 64 字符；SDK 接入表保留该宿主触发标识，正式渠道事实仍将未知值归一为 `API_CALL`。
截图、录屏只有用户主动选择后才加密上传。日志在反馈窗口打开时冻结，只截取窗口打开前 3 分钟的
SDK/宿主诊断事件；用户填写表单期间产生的日志不会进入本次附件。

## 4. 可选回执与宿主日志

```kotlin
FeedbackSDK.setListener(object : FeedbackListener {
    override fun onQueued(clientFeedbackId: String) = Unit
    override fun onAccepted(clientFeedbackId: String, receiptId: String) = Unit
    override fun onPermanentFailure(clientFeedbackId: String, reasonCode: String) = Unit
})

FeedbackSDK.recordHostLog("INFO", "page_opened", "screen=DRAWING_HOME")
```

取得 `receiptId` 后，宿主可以查询安全处理进度：

```kotlin
when (val result = FeedbackSDK.queryProgress(receiptId)) {
    is FeedbackProgressResult.Success -> {
        val progress = result.progress
        // status: PROCESSING / COMPLETED / REVIEW_NEEDED / FAILED
        // stage: RECEIVED / MASKING / STANDARDIZATION / PRIVACY_GATE / AVAILABLE
    }
    is FeedbackProgressResult.ServerError -> Unit
    is FeedbackProgressResult.NetworkError -> Unit
}
```

查询请求使用与上报相同的 HMAC 鉴权，服务端还会验证 receipt 所属应用。接口只返回状态、阶段、进度和处理时间，不返回失败原文或内部记录 ID。

`recordHostLog` 写入进程内环形缓冲区并自动脱敏，不读取系统 logcat。不得主动写入密钥、联系方式、
儿童身份、健康指标值等敏感信息。

## 5. 当前统一能力

- 代码按钮触发、截图、截图编辑、录屏和最近诊断日志。
- AES-256-GCM 正文与附件加密，RSA-OAEP-256 封装 dataKey，HMAC-SHA256 请求鉴权。
- Room 离线队列、WorkManager 独立重试、client feedback ID 幂等。
- 网络异常、HTTP 429 和 5xx 连续重试 8 次后进入 `RETRY_EXHAUSTED`，不会被误标为协议永久失败。宿主可在用户主动点击“补传历史反馈”或网络/服务恢复确认后调用 `FeedbackSDK.retryHistoricalFailures()`；该方法只重排瞬态耗尽记录，重置重试预算并创建唯一 WorkManager 任务，不会重放 401/403、400/422、无效 ACK 或本地信封损坏记录。
- `onQueued`、`onAccepted`、`onPermanentFailure` 三阶段宿主回调。
- 通用反馈独立 App/悬浮入口可与嵌入式入口同时保留。

## 6. Demo 对照

- 健康管理：`appKey=health-demo`，模块 `HEALTH_OVERVIEW`。
- 儿童绘画：`appKey=child-art-demo`，模块 `DRAWING_CANVAS`。
- 两者调用同一组 `FeedbackSDK.init / launch / setListener / recordHostLog` API，仅身份与业务上下文不同。
