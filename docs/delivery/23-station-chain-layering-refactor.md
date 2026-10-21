# 旧反馈平台 Station 链路分层重构交付

## 1. 结论与调用链

本次覆盖 Station V1/V2 HTTP、RPC、在线受理、原始密文、身份、附件、Worker、双写、运维重试、脱敏恢复以及 Legacy 导出导入/恢复。调用链固定为：

```text
HTTP / RPC / 运维 Runner / XXL Job
  → Station Service 接口或职责 Worker
  → service/impl/station（事务外校验、脱敏、组装）
  → 短事务 Commit/Finalization Worker
  → Station Repository 接口
  → repository/impl/station/MyBatisXxxRepositoryImpl
  → Mapper + 单表 XML
  → 投影脏标记 / 审计
```

入口文件为 `controller/station/StationFeedbackController.kt`、`rpc/station/FeedbackPlatformRpcApiImpl.kt`、`IngestionOperationsController.kt`、`job/station/StationLegacyExportImportRunner.kt`、`StationLegacyRecoveryRunner.kt` 和 `StationJobHandlers.kt`。入口不依赖 ServiceImpl、Repository、Mapper 或 PO。

## 2. 分层与逐文件清单

- Service 契约：`StationFeedbackIngestService.kt`、`StationFeedbackOperationsService.kt`、`StationOnlineRetryService.kt`、`StationRawPayloadService.kt`、`StationOnlineIdentityService.kt`、`StationOnlineFinalizationService.kt`、`StationDualWriteTaskService.kt`、`StationLegacyExportImportService.kt`、`StationLegacyImportApplicationService.kt`、`StationOnlineMaskingRecoveryService.kt`。
- 实现：上述契约在 `service/impl/station/*ServiceImpl.kt` 一一对应；`StationFeedbackIngestCommitWorker.kt` 是在线受理短事务提交器。
- 职责组件：`StationFeedbackWorker.kt`、`StationDualWriteWorker.kt`、`StationAttachmentDownloader.kt`、`StationPhoneResolver.kt`、`StationLegacyRowAssembler.kt`、`StationLegacyExportFileReader.kt`、`OldFbpClient.kt`。这些类型按 Worker/Assembler/Adapter 的真实职责保留，不机械接口化。
- Repository 契约：`repository/station/online/StationOnlineRepositories.kt` 和 `repository/station/legacy/StationLegacyRepositories.kt`。
- MyBatis 实现：`repository/impl/station/online/MyBatisStationOnlineRepositoryImpl.kt`、`repository/impl/station/legacy/MyBatisStationLegacyRepositoryImpl.kt`；Mapper 只在这里注入。
- Online Mapper/XML：Source、Content、Channel、RawPayload、Identity、Attachment、DualWriteTask 共 7 个。
- Legacy Mapper/XML：Source、Content、Channel、RawPayload、Identity、Attachment 共 6 个。
- PO：`model/po/station/online` 与 `model/po/station/legacy`；数据库字段和状态字符串保持兼容。
- 门禁与合同：`StationChainArchitectureTest.kt`、`StationFeedbackIngestCommitWorkerTest.kt`、`StationOnlineMyBatisContractTest.kt`、`StationLegacyMyBatisContractTest.kt` 及全部既有 `*Station*Test`。

## 3. 类型、兼容与边界

HTTP 路由、RPC DTO、BINARY(16) receipt、幂等键、Online/Legacy 表结构、状态 code 和任务载荷均未改名。新增 `PreparedStationIngest`、`PreparedStationRawPayload`、`PreparedStationIdentity` 作为事务外准备模型，`StationIdentityType` 以 `PHONE`、`USER_ID` 稳定 code 落库；Legacy 行结果继续使用封闭的 `StationLegacyRowDecision`。

批量上限保持显式：Online 来源/恢复最多 200，人工重试最多 100，单条反馈附件最多 9，Legacy 每批最多 200，在线 Worker 每轮最多 20，双写 Worker 每轮最多 50。Repository 对空集合、重复身份、非法 ID、乱序风险和 Mapper 影响行数失败关闭。

## 4. 事务、租约、幂等和失败策略

- 在线受理在事务外完成认证、请求校验、SHA-256 命令指纹、脱敏、故障时间解析、照片 JSON、原始载荷加密以及适配器手机号规范化/指纹/加密。
- `StationFeedbackIngestCommitWorker.commit` 的短事务只执行 source insert-or-locate、定位后指纹复核、content/channel/raw/identity 写入和投影脏标记。同键同指纹稳定重放；同键异指纹返回 `STATION_IDEMPOTENCY_CONFLICT`。
- Online Worker 通过 `leaseOwner` claim，外部 UC/HTTP/文件处理期间按 owner 续租，最终状态推进仍带 owner 条件；退避上限 1800 秒且重试次数受任务上限约束。
- 双写任务的 JSON 和加密在事务外；任务创建、claim、旧平台成功、新平台成功及失败推进分别使用短事务。外部旧平台 HTTP 永不位于数据库事务中。
- 脱敏恢复先在事务外有界读取、解密和重新脱敏，再按 source 独立短事务提交，坏记录不回滚同批成功记录。
- Legacy 文件 I/O、解析和 SHA-256 在事务外；每个不超过 200 行的批次使用 `REQUIRES_NEW`，成功批次不会被后续坏批次回滚。成功/失败审计只记录稳定 ID、哈希、计数和安全错误码。

## 5. SQL、注释与门禁

13 个 Station XML 被专属门禁固定为精确数量，禁止 `${...}`、`SELECT *`、JOIN、EXISTS 和单行动态 `foreach`。专属门禁还覆盖入口依赖、Service/Impl、Repository/Impl、单向依赖、事务外高成本操作、定位后指纹复核、owner 租约、有限重试和批量上限。Station/SDK 联合声明级复扫覆盖 127 个直接相关生产文件、806 个命名类型与函数声明。注释门禁不再只扫 Service 包：配置、HTTP/RPC/运维入口、共享 Job、安全过滤器、DTO/PO、Service/Worker、Repository/Impl 和 Mapper 均列入完整文件清单，所有命名类型、接口方法、实现方法和关键私有方法必须有紧邻中文 KDoc。KDoc 逐项说明真实动作与事务、租约、幂等、批量、I/O、补偿或失败语义；门禁明确拒绝“执行本类型声明”“对应的领域操作”“所声明的操作”等占位式注释。控制流规则会扫描行内 lambda 中的 `if ... throw/return/break/continue`，防止只检查行首造成漏网。

## 6. 验证记录（2026-09-09）

| 命令 | 结果 |
|---|---|
| Station 定向 `:backend:test --tests '*Station*' --tests '*FeedbackPlatformRpcApiImplTest'` | 通过 |
| `:backend:architectureTest --tests '*StationChainArchitectureTest'` | 通过 |
| Station/SDK 联合定向行为测试与 `:sdk:testDebugUnitTest --rerun-tasks` | 通过，26 tasks executed |
| Station/SDK 联合专属 architectureTest | 通过，6 tasks executed |
| `:backend:test :backend:bootJar :sdk:testDebugUnitTest --rerun-tasks` | 通过，28 tasks executed，耗时 1m42s |
| 完整 `:backend:architectureTest --rerun-tasks` | 74 项中 73 项通过；唯一失败位于无关 cluster 域，详见下文 |
| `:rpc-api:test` | `NO-SOURCE`，RPC 消费契约由 backend 的 `FeedbackPlatformRpcApiImplTest` 覆盖 |

完整架构测试唯一失败位于 cluster 域：`ExceptionBoundaryArchitectureTest` 报告
`ApplicationSymptomDryRunService.summarize -> java.lang.IllegalStateException`。Station/SDK 专属门禁均已通过。
按“不得修改无关域制造全绿”约束，本次保留该跨域遗留项，不把完整 architectureTest 表述为通过。

当前环境未提供独立 MySQL 测试库连接，也没有可执行的 Station 专属真实 MySQL 并发合同；本轮只执行了单元测试、Repository 行为测试和 XML/MyBatis 合同，不能把它们表述为真实数据库并发验证。该项作为环境与测试覆盖风险保留。
