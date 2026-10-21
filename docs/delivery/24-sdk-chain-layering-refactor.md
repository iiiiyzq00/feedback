# SDK 链路分层重构交付

## 1. 结论与调用链

SDK 主体、附件、配置、遥测、安全处理及物化持久化已按与 Station 一致的安全模型收口，同时保持两套外部协议独立：

```text
SdkFeedbackController / SDK Job
  → SdkIngestService / SdkAttachmentService / SdkTelemetryService / SdkConfigService
  → service/impl/sdk（验签后的业务编排、文件与 JSON 高成本工作）
  → SdkIngestPersistence / SdkTelemetryCommitWorker 短事务
  → SDK Repository 接口
  → repository/impl/sdk/MyBatisXxxRepositoryImpl
  → Mapper + 单表 XML
```

## 2. 分层与文件清单

- HTTP：`controller/sdk/SdkFeedbackController.kt`，只依赖四个窄 Service 契约。
- 契约：`service/sdk/SdkIngestService.kt`、`SdkAttachmentService.kt`、`SdkTelemetryService.kt`、`SdkConfigService.kt`；安全与持久化协作契约分别位于 `security/sdk`、`strategy/sdk` 和 `manager/sdk`。
- 实现：`service/impl/sdk/SdkIngestServiceImpl.kt`、`SdkAttachmentServiceImpl.kt`、`SdkTelemetryServiceImpl.kt`、`SdkConfigServiceImpl.kt`；短事务提交器位于 `manager/sdk`。
- 安全/职责组件：`SignatureVerifier.kt`、`NonceValidator.kt`、`RateLimiter.kt`、`DefaultSdkRequestAuthenticator`、`TransportKeyService.kt`、`SdkAttachmentOrphanCleaner.kt`、`SdkRequestTelemetryRecorder.kt`。
- 核心 Repository：`repository/sdk/SdkIngestRepository.kt`、`ChannelSdkRepository.kt`；实现为 `repository/impl/sdk/MyBatisSdkIngestRepositoryImpl.kt`、`MyBatisChannelSdkRepositoryImpl.kt`。
- 物化 Repository：`repository/sdk/SdkMaterializationRepositories.kt`；实现位于 `repository/impl/sdk/MyBatisSdkMaterializationRepositoryImpl.kt`，仅实现层注入 Mapper。
- SQL：`mybatis/mapper/sdk` 下 9 个职责 XML；核心信封/渠道 XML 继续位于 `mybatis/mapper/core`。
- 门禁和测试：`SdkChainArchitectureTest.kt`、`SdkIngestIdempotencyTest.kt`、`SdkMaterializationMyBatisContractTest.kt`、Android `sdk` 模块单测及全部既有 `*Sdk*Test`。

## 3. 安全、幂等、短事务和批量

- schema、密文长度、算法、keyId 和时间字段校验先于写入；HMAC、nonce Redis 防重放、IP/app 限流均在数据库事务外。
- `clientFeedbackId + appKey` 是业务幂等键，`requestSha256` 是命令指纹。首次查询和唯一键竞争后的权威重读都会复核指纹；同命令稳定返回原 receipt，异命令返回 `SDK_IDEMPOTENT_CONFLICT`。
- `TransactionalSdkIngestPersistence.saveReceived` 只包围单表信封写入，不包含验签、Redis、Base64 解码或 DTO 组装。
- 遥测最多 100 条；JSON 和安全字段裁剪在事务外完成，`SdkTelemetryCommitWorker` 只执行一次批量 SQL。
- 附件文件读取、哈希、解密和原子移动不进入数据库事务；同附件使用条带锁串行化，数据库失败时删除本次新生成文件。路径必须位于配置根目录，错误日志不输出密文或原始正文。
- 物化来源批量最多 200、身份最多 400、遥测最多 100；空批、重复 source/identity、越界和异常影响行数失败关闭。

## 4. 兼容性与类型字典

SDK HTTP 路由、请求/响应 DTO、`sdk-form-v1` schema、`AES-256-GCM+RSA-OAEP-256`、receipt 编码、Android WorkManager 协议和数据库字段均保持兼容。稳定状态 code 包括 `RECEIVED`、`PROCESSING`、`COMPLETED`、`RETRY_PENDING`、`REVIEW_NEEDED`、`FAILED`，正文/联系处理状态 `PENDING`、`NOT_PROVIDED`、`COMPLETED`，进度 stage `RECEIVED`、`MASKING`、`STANDARDIZATION`、`AVAILABLE`、`PRIVACY_GATE`。开放的 appKey、packageName、keyId 继续由集中校验规则约束，不强行封闭枚举。

## 5. 门禁与验证记录（2026-09-09）

专属 `SdkChainArchitectureTest` 覆盖 Controller 依赖、Service/Impl、Repository/Impl、单向依赖、验签/Redis/高成本工作不入事务、幂等指纹冲突、遥测短事务、批量上限以及 9 个 SDK XML 的参数化和可读性。Station/SDK 联合声明级复扫覆盖 127 个直接相关生产文件、806 个命名类型与函数声明。注释门禁使用完整文件清单，覆盖认证配置、HTTP 入口、共享 Job、DTO/PO、核心 SDK 实体与 Mapper/Repository、Service/Impl、安全组件、附件处理、受控诊断回填和安全物化 Worker；所有命名类型、接口方法、实现方法和关键私有方法必须有紧邻中文 KDoc。KDoc 逐项说明真实动作与验签、防重放、文件边界、短事务、幂等、批量或失败语义；门禁拒绝“执行本类型声明”“对应的领域操作”“所声明的操作”等占位式注释。控制流规则会扫描行内 lambda 中的 `if ... throw/return/break/continue`，避免只检查行首造成漏网。

| 命令 | 结果 |
|---|---|
| SDK 定向 `:backend:test --tests '*Sdk*' --tests '*SignatureVerifierTest' --tests '*RateLimiterTest'` | 通过 |
| `:backend:architectureTest --tests '*SdkChainArchitectureTest'` | 通过 |
| `ANDROID_HOME=/Users/yang/Library/Android/sdk ... :sdk:testDebugUnitTest` | 通过 |
| Station/SDK 联合定向行为测试与 `:sdk:testDebugUnitTest --rerun-tasks` | 通过，26 tasks executed |
| Station/SDK 联合专属 architectureTest | 通过，6 tasks executed |
| `:backend:test :backend:bootJar :sdk:testDebugUnitTest --rerun-tasks` | 通过，28 tasks executed，耗时 1m42s |
| 完整 `:backend:architectureTest --rerun-tasks` | 74 项中 73 项通过；唯一失败位于无关 cluster 域 |

完整 `:backend:architectureTest` 的 Station/SDK 新门禁均通过，但全任务仍被 cluster 域
`ApplicationSymptomDryRunService.summarize` 的 `checkNotNull`/`IllegalStateException` 阻断。
按“不得改无关域制造全绿”约束，本次未修改 cluster；因此不把完整 architectureTest 表述为通过。

`P0MyBatisMysqlContractTest` 中已有 SDK 遥测批量事务的真实 MySQL 合同，但当前环境未提供独立测试库凭据，因此该环境型用例被跳过；SDK 上报幂等并发仍没有专属真实 MySQL 合同。Mock、XML 解析和单元测试没有冒充真实数据库并发结果，这两项作为环境与测试覆盖风险保留。
