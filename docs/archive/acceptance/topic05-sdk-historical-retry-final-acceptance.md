# 专题 05：SDK 历史失败重试/补传最终验收

## 结论

**PASS（实习生项目最终汇报范围）**。

设备端现已形成“提交前落 Room—网络约束 WorkManager—指数退避—瞬态耗尽保留—显式历史补传—服务端 client feedback ID 幂等—严格 ACK—附件独立续传”的闭环。本结论基于一台真实 Android 10 设备和自动化服务端测试，不代表全机型、Doze 长稳或生产弱网覆盖。

## 本次修复

### 1. 区分永久失败与瞬态重试耗尽

原实现将网络异常、HTTP 429 和 5xx 在第 8 次失败后写成 `PERMANENT_FAILED`，与鉴权、协议和信封永久错误混在一起，后续没有补传入口。

现调整为：

- 网络异常、429、5xx：保留 `PENDING_BODY` 并由 WorkManager 指数退避；第 8 次失败进入 `RETRY_EXHAUSTED`。
- 401/403、400/422、非法 ACK、本地加密信封损坏：进入 `PERMANENT_FAILED`，禁止自动或历史重放。
- `RETRY_EXHAUSTED` 仍保留设备本地加密信封，可显式回到 `PENDING_BODY`；`PERMANENT_FAILED` 是终态。

### 2. 历史补传 API

新增宿主 API：

```kotlin
val requeued = FeedbackSDK.retryHistoricalFailures(limit = 100)
```

行为：

- 每次最多处理 100 条，参数越界失败关闭。
- 只查询 `RETRY_EXHAUSTED`，不会重放永久错误。
- 重置重试计数和瞬态错误码，重新进入 `PENDING_BODY`。
- 有数据时只创建一个唯一 `feedback_sdk_body_upload` WorkManager 任务；空队列不创建任务。
- `health-demo` 增加“补传历史”按钮，可在真实设备演示重新排队数量。

### 3. ACK 与服务端幂等

主体首次直传和后台补传现在共用严格 ACK 校验：

- HTTP 成功。
- API envelope code 为 0 或 200。
- ACK 的 `clientFeedbackId` 必须与本地记录一致。
- `receiptId` 非空。
- 状态必须为 `ENCRYPTED_ACCEPTED`。

服务端继续以 `(app_id, client_feedback_id)` 为幂等键；历史补传使用同一个 client feedback ID，重复请求返回原 receipt，不重复写入 `t_sdk_ingest`。后端专项测试验证幂等重放不调用第二次 `save`。

### 4. 设备持久链路

- `submit()` 在第一次网络调用前将公钥封装后的 data key、nonce、密文和 tag 写入 Room。
- 初始化时重新创建唯一主体上传任务，并恢复被 APK 替换或进程中断的附件任务。
- 主体 ACK 后保存 receipt，再以 receipt 和 client attachment ID 独立续传附件。
- WorkManager 要求网络已连接，主体采用至少 30 秒的指数退避，重复 enqueue 使用 `ExistingWorkPolicy.KEEP`。

## 验收证据

### 真实 Android 设备

设备：`DT15PA`，Android 10，通过 ADB 连接。

```text
connectedDebugAndroidTest: 4 tests, 0 failures
```

覆盖：

- Room 主体和附件队列生命周期。
- 主体删除时附件级联清理。
- 同一唯一 Work 重复 enqueue 后只有一个 ENQUEUED 任务。
- `RETRY_EXHAUSTED` 真实写入 Room 后，经历史补传恢复为 `PENDING_BODY`、计数归零、错误码清空并触发一次上传调度。

### 自动化回归

- SDK JVM 单测：30 tests，0 failures。
- SDK Lint：通过。
- 后端全量：378 tests，0 failures，0 errors，13 skipped。
- `demo`、`health-demo`、`child-art-demo` Debug APK：全部编译通过。
- `git diff --check`：通过。

## 边界说明

- 平台入口未收到正文时仍不能主动替设备重放；补传动作发生在持有加密信封的设备端。
- 本轮真实设备自动化证明 Room 与 WorkManager 的恢复链路，不声称已经覆盖断电、飞行模式、Doze、OEM 杀后台、跨版本数据库迁移和所有 Android 厂商。
- 生产前仍建议执行多机型断网 8 次耗尽、服务恢复、点击补传、收到同一 receipt、附件续传完成的长链录屏验收。
