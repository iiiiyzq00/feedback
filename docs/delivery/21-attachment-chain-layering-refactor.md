# 附件链路分层重构交付

## 1. 范围与调用链

本次覆盖以下完整链路：

```text
HTTP / XXL-Job
→ 附件 Service / UseCase 接口
→ ServiceImpl / 有界 Worker
→ Repository / Application Port
→ MyBatis RepositoryImpl
→ Mapper XML / MySQL

内容侧：ServiceImpl / Worker
→ 路径策略 / 加密 / ClamAV / FFmpeg / S3 端口
→ 单条 version/CAS/条件 UPDATE 确认数据库快照
```

关键入口包括附件列表、普通查看授权、批量媒体预览授权、原始日志下载、一次性令牌消费、安全扫描、日志预览回填、媒体预览、对象迁移和本地副本清理。

## 2. 分层结果

- `FeedbackAttachmentApplicationService`、`AttachmentSecureStreamService`、`AttachmentLogPreviewService` 为接口；Spring 业务实现位于 `service/impl` 并以 `ServiceImpl` 结尾。
- `SecureAttachmentRepository` 已按读取、预览、对象生命周期拆成三个窄接口，原 25 方法大仓储不再存在。
- `AttachmentGrantRepository`、`AttachmentSecurityScanRepository`、`FeedbackAttachmentMutationRepository` 均为接口；MyBatis 实现位于 `repository/impl/attachment`。
- Controller 和 Job 只依赖 UseCase、Service 或 Worker 契约，不依赖 Mapper、RepositoryImpl 或 ServiceImpl；ServiceImpl 不直连 Mapper。
- 原先单文件两个公开媒体预览 PO 已拆为独立文件。

## 3. 稳定编码与强类型字典

数据库和 HTTP 既有 code 未改变。内部事实优先传播强类型，只在 MyBatis、HTTP、Spring 或审计 JSON 边界转换为稳定字符串。

| 集合 | 领域类型 | 稳定 code |
|---|---|---|
| 附件类型 | `AttachmentType` | `SCREENSHOT`、`ANNOTATED_SCREENSHOT`、`SCREEN_RECORDING`、`LOG`、`IMAGE`、`VIDEO` |
| 上传状态 | `AttachmentUploadStatus` | `PENDING`、`UPLOADED`、`VERIFIED`、`FAILED` |
| 综合安全状态 | `AttachmentSecurityStatus` | `QUARANTINED`、`VERIFIED`、`REJECTED` |
| 完整性状态 | `AttachmentIntegrityStatus` | `PENDING`、`VERIFIED`、`FAILED` |
| 解密状态 | `AttachmentDecryptionStatus` | `NOT_REQUIRED`、`PENDING`、`DECRYPTED`、`FAILED` |
| 恶意软件状态 | `AttachmentMalwareScanStatus` | `PENDING`、`CLEAN`、`INFECTED`、`ERROR` |
| 内容审核状态 | `AttachmentContentReviewStatus` | `PENDING`、`PASS`、`REVIEW_NEEDED`、`REJECTED` |
| 日志预览状态 | `AttachmentLogPreviewStatus` | `PENDING`、`AVAILABLE`、`FAILED` |
| 媒体预览/授权响应 | `AttachmentPreviewStatus` | `PENDING`、`PROCESSING`、`AVAILABLE`、`FAILED`、`UNAVAILABLE` |
| 附件来源 | `AttachmentSourceKind` | `SDK`、`IMPORT`、`STATION_FEEDBACK`、`JIRA` |
| grant 用途 | `AttachmentGrantPurpose` | `INLINE_VIEW`、`DERIVED_PREVIEW`、`ORIGINAL_LOG_DOWNLOAD` |
| 授权动作 | `AttachmentAuthorizationAction` | `DIRECT_VIEW`、`DERIVED_PREVIEW`、`ORIGINAL_LOG_DOWNLOAD` |
| 审计操作 | `AttachmentAuditOperation` | `ATTACHMENT_DECRYPT`、`ATTACHMENT_PREVIEW`、`ORIGINAL_LOG_DOWNLOAD` |
| 交付模式 | `AttachmentDeliveryMode` | `ONE_TIME_SECURE_STREAM`、`ONE_TIME_DERIVED_PREVIEW`、`ONE_TIME_ORIGINAL_DOWNLOAD` |
| 存储后端 | `AttachmentStorageBackend` | `S3_COMPATIBLE` |
| 对象生命周期 | `AttachmentObjectLifecycleStatus` | `PENDING`、`QUARANTINED`、`AVAILABLE`、`SOURCE_UNAVAILABLE`、`FAILED` |
| 本地清理审计 | `AttachmentCleanupAuditOutcome` | 保持原 JSONL 的七个结果 code |

未知数据库状态解析为 `null` 或直接抛出服务不可用，不会被静默提升为可查看状态。

## 4. 事务、锁、CAS 与外部 I/O

| 阶段 | 事务边界 | 并发/失败策略 |
|---|---|---|
| 单附件授权 | 短写事务：实时授权事实、grant 指纹插入、审计插入 | 任一写失败整体回滚；不含文件、Redis、HTTP 或对象存储 I/O |
| 批量预览授权 | 固定最多 20 项；一次批量事实查询后在短写事务内逐项 grant+审计 | 消除逐附件读取 N+1；响应项彼此用途隔离 |
| grant 消费 | 短事务：数据范围复核、附件事实、grant `FOR UPDATE`、消费条件更新 | 过期、撤销、重复和并发消费全部失败关闭；提交后才打开内容 |
| 安全扫描 | 无跨 I/O 事务 | ClamAV、审核、预览、加密、文件删除在事务外；最后单条条件更新 |
| 日志预览 | 无跨 I/O 事务 | 事务外读取/解压/加密；单条 CAS 写预览，失败码条件收敛 |
| 媒体预览 | 无跨 I/O 事务 | owner+租期领取/续租/完成 CAS；FFmpeg/S3 在事务外；失败退避有上限 |
| 对象发布 | 无跨 I/O 事务 | 解密、上传、完整回读哈希在事务外；最后以原路径快照 CAS；CAS 失败补偿删对象 |
| 本地清理 | 无跨 I/O 事务 | 完整回读对象、重新读取 DB 快照、精确单文件删除、条件清路径；无递归/通配符删除 |

所有 RepositoryImpl 单条原子 SQL/CAS 均不声明额外事务。迁移重试次数、批量大小、租期、文件/像素/输出大小和外部进程超时全部有明确上限。

## 5. SQL 与查询

- `FeedbackAttachmentMapper.xml` 的 INSERT 列和值、UPDATE SET/WHERE、SELECT 字段和动态集合均采用多行排版。
- 列表与批量授权使用集合式查询，不逐附件回表。
- 扫描、日志预览、媒体预览、迁移和清理候选均有 `LIMIT` 或由 Repository 强制的最大集合边界。
- 未使用 `SELECT *`，未新增 Controller/Service 直接 SQL。

## 6. 注释与反回归门禁

附件范围内的接口和实现函数均补充紧邻中文 KDoc，重点说明职责、事务、外部 I/O、锁/CAS、幂等、缓存、重试、补偿、失败策略和不明显不变量。`AttachmentChainArchitectureTest` 自动覆盖：

- 入口依赖、ServiceImpl/Mapper 分层；
- 复杂 Repository 接口与实现拆分；
- 业务状态与授权动作强类型；
- 授权/消费事务边界和事务外内容 I/O；
- 批量授权禁止 N+1；
- SQL 多行、字段显式和候选有界；
- 附件范围所有函数紧邻中文 KDoc；
- 禁止注解/函数及条件/执行体压缩在同一行。

## 7. 验证记录

- `:backend:compileTestKotlin`：通过。
- 附件域定向测试（`--tests '*Attachment*'`）：109 项，107 通过，2 项按环境假设跳过，0 失败。
- 附件专属架构门禁：通过。
- 完整 `:backend:architectureTest`：51 项全部通过；原附件域两个既有失败已消除。
- 完整 `:backend:bootJar`：通过。
- 完整 `:backend:test`：共 1378 项，1332 通过、45 跳过、1 失败。唯一失败为附件域外的
  `ApplicationIdentityPersistenceContractTest`（期望 6、实际 7），本次未修改应用身份域来掩盖失败；附件定向测试仍为 0 失败。
