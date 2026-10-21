# 六域技术层目录统一治理交付

## 1. 结论与唯一目录规则

登录、权限、附件、应用身份、Station、SDK 六条已重构链路统一采用“技术层优先、层内领域分包”。`service/<domain>` 只放同名 `*Service.kt` 接口，`service/impl/<domain>` 只放一一对应的 `*ServiceImpl.kt`。旧顶级 `applicationidentity`、`attachment`、`client`、`sdk`、`station`、`entity` 包已退出生产源码。

唯一目标路径如下：

| 职责 | 目标路径 |
|---|---|
| HTTP / RPC / 任务入口 | `controller/<domain>`、`rpc/<domain>`、`job/<domain>` |
| Service 契约 / 实现 | `service/<domain>`、`service/impl/<domain>` |
| 编排、策略、转换、安全 | `manager/<domain>`、`strategy/<domain>`、`convert/<domain>`、`security/<domain>` |
| 持久化契约 / 实现 / 外部端口 | `repository/<domain>`、`repository/impl/<domain>`、`repository/remote/<domain>` |
| MyBatis / HTTP DTO / 领域模型 / PO | `mapper/<domain>`、`dto/<domain>`、`model/<domain>`、`model/po/<domain>` |

## 2. 调用链与关键迁移

统一调用链为：入口 → Service 接口 → ServiceImpl 事务外校验/规范化/高成本组装 → Manager 短事务或 Worker → Repository 接口 → RepositoryImpl → Mapper/XML。Controller、RPC、Job、Scheduler 不依赖 ServiceImpl、Repository、Mapper 或 PO；ServiceImpl 不直连 Mapper；RepositoryImpl 不反调内部 Service。

本轮完成的关键路径收口包括：

- Station 网关主体与过滤器迁入 `security/station`，SDK 核心仓储契约迁入 `repository/sdk`。
- Attachment、SDK、Station 任务入口拆入各自 `job/<domain>`，不再保留混合 Job 文件。
- 附件 `service/query/attachment` 的平行 UseCase 契约被移除；列表/授权统一为 `FeedbackAttachmentApplicationService`，安全流统一为 `AttachmentSecureStreamService`。
- 附件命令与结果迁入 `model/attachment`，范围访问端口迁入 `security/attachment`，grant 协作端口迁入 `manager/attachment`。
- JSONL 迁移审计实现迁入 `repository/impl/attachment`，封闭审计结果迁入 `model/attachment`；Repository 文件只保留审计契约。
- 原 `entity` 类型按职责拆分：真正的持久化绑定对象迁入 `model/po/<domain>`，普通领域事实迁入 `model/<domain>`，MyBatis XML 全限定名同步更新。

本轮最终复核后的“原路径 → 唯一目标路径”如下；一行中列出多个目标表示原混合文件已按职责拆分：

| 原路径 | 唯一目标路径 |
|---|---|
| `job/AttachmentJobHandlers.kt` | `job/attachment/AttachmentJobHandlers.kt` |
| `job/SdkAndStationJobHandlers.kt` | `job/sdk/SdkJobHandlers.kt`、`job/station/StationJobHandlers.kt` |
| `repository/ChannelSdkRepository.kt` | `repository/sdk/ChannelSdkRepository.kt` |
| `repository/SdkIngestRepository.kt` | `repository/sdk/SdkIngestRepository.kt` |
| `security/StationGatewayAuthenticationFilter.kt` | `security/station/StationGatewayAuthenticationFilter.kt` |
| `service/query/FeedbackAttachmentApplicationService.kt` | `service/attachment/FeedbackAttachmentApplicationService.kt` |
| `service/impl/query/FeedbackAttachmentApplicationServiceImpl.kt` | `service/impl/attachment/FeedbackAttachmentApplicationServiceImpl.kt` |
| `service/query/attachment/FeedbackAttachmentContracts.kt` | 契约分别归入 `model/attachment`、`manager/attachment`、`security/attachment`；原文件删除 |
| `repository/attachment/AttachmentMigrationAudit.kt` 中的实现与结果类型 | `repository/impl/attachment/JsonLineAttachmentMigrationAudit.kt`、`model/attachment/AttachmentCleanupAuditOutcome.kt` |
| `repository/cluster/ClusterRepositories.kt` 中的权限守卫 | `manager/cluster/ClusterMutationAccessGuard.kt` |
| `model/po/ai/AiTask.kt`、`model/po/audit/SystemAuditLog.kt` | `model/ai/AiTask.kt`、`model/audit/SystemAuditLog.kt` |
| `model/po/cluster/Cluster.kt`、`ClusterLink.kt`、`ClusterNameIdentity.kt` | `model/cluster/Cluster.kt`、`ClusterLink.kt`、`ClusterNameIdentity.kt` |
| `model/po/config/SystemConfig.kt` | `model/config/SystemConfig.kt` |
| `model/po/feedback/FeedbackAi.kt`、`FeedbackContent.kt`、`FeedbackIdentity.kt`、`FeedbackSource.kt` | `model/feedback` 下同名文件 |
| `model/po/imports/ImportAttachmentTask.kt`、`ImportBatch.kt`、`ImportRow.kt`、`ImportTemplate.kt` | `model/imports` 下同名文件 |
| `model/po/jira/ChannelJira.kt`、`model/po/masking/MaskingAudit.kt` | `model/jira/ChannelJira.kt`、`model/masking/MaskingAudit.kt` |
| `model/po/sdk/ChannelSdk.kt`、`SdkIngest.kt` | `model/sdk/ChannelSdk.kt`、`SdkIngest.kt` |
| `model/po/channel/ChannelQuality.kt`、`ChannelRedblack.kt` | `model/channel/ChannelQuality.kt`、`ChannelRedblack.kt` |

`model/po` 现在只允许文件名、唯一公开类型均以 `Po` 结尾；普通领域事实进入 `model/<domain>`。门禁递归扫描整个 `model/po`，不再依赖容易漏项的固定领域清单。

## 3. 兼容性、事务与安全结论

目录迁移不改变数据库表/列、MyBatis statement ID、HTTP 路由、RPC DTO、XXL Job handler 名、SDK receipt、Station 任务载荷与既有状态 code。应用身份 7 个 XML 是语义职责清单：第七个 SafeText Mapper 仅承担 masking PASS 历史只读解析，不扩大 identity 写边界。

应用身份人工重绑继续固定 alias → source identity → target identity 锁序，并在同一短事务内完成锁后 requestId 复核、双版本 CAS、旧 alias 失效、新版本写入和必须成功的审计。Station/SDK 的认证、Redis nonce、HTTP、文件、加解密、脱敏和 JSON 组装均在事务外；提交阶段通过唯一键、命令指纹、租约 owner、版本/CAS 或条件更新重新确认快照。附件授权与审计同一短事务提交，消费事务提交后才打开文件或对象流。

批量边界保持显式：附件预览 20、SDK 遥测 100、SDK/Station 物化与读取按各链既有 100/200/400 上限执行；空集、重复、缺失、乱序、越界及异常影响行数失败关闭。日志和审计不记录密钥、token、正文、原始路径或对象键。

## 4. 注释与架构门禁

`MigratedServicePackagePurityArchitectureTest` 现在递归扫描六域的 Service、Impl、Manager、Strategy、Convert、RPC、Job、Security、DTO、Model、PO、Repository、RepositoryImpl、Remote、Mapper 及直接相关 Controller。所有具名类型、接口方法、实现公开方法和关键私有方法必须具有紧邻中文 KDoc；同时拒绝单行压缩 `if/else + throw/return/break/continue` 和注解/声明同一行。

各链专属门禁继续检查入口依赖、Service/Impl 一一对应、Mapper 单向依赖、短事务位置、外部工作不入事务、应用身份锁/CAS/幂等、Station/SDK 租约和有限重试、无 N+1 与批量上限，以及 XML 参数化、无 `SELECT *`、无字符串拼接 SQL和既有单表约束。

根启动类显式恢复 Spring Boot 默认 `TypeExcludeFilter` 与自动配置排除器，保证 `@WebMvcTest` 只加载 Web 切片；安全授权测试不再通过堆叠业务 Repository/Mapper mock 掩盖错误扫描边界。

## 5. 验证记录

2026-09-10 最终验证记录：

| 命令 | 结果 |
|---|---|
| 六域 `:backend:test` 定向矩阵（`*ApplicationIdentity*`、`*Attachment*`、`*Station*`、`*Sdk*`、`*Auth*`、`*Permission*`） | 通过，`BUILD SUCCESSFUL` |
| 六域专属 `:backend:architectureTest` 矩阵 | 48 项通过，`BUILD SUCCESSFUL` |
| `:backend:test --rerun-tasks` | 1403 项通过，45 项按环境条件跳过，`BUILD SUCCESSFUL` |
| `:backend:bootJar --rerun-tasks` | 通过，`BUILD SUCCESSFUL` |
| `:backend:integrationTest --rerun-tasks` | 9 项中 8 项执行通过、1 项环境跳过，`BUILD SUCCESSFUL` |
| `ANDROID_HOME=... :sdk:testDebugUnitTest --rerun-tasks` | 通过，`BUILD SUCCESSFUL` |
| `:rpc-api:test --rerun-tasks` | 任务成功；当前模块测试源码为 `NO-SOURCE`，RPC 实现合同由 backend 测试承担 |
| `:backend:architectureTest --rerun-tasks` | 79 项中 78 项通过；唯一失败为既有非六域 `ApplicationSymptomDryRunService.summarize -> IllegalStateException` |

当前环境未提供专用 MySQL/MinIO/Milvus/ONNX 条件，因此 45 项环境测试按既有假设跳过。其中与本交付直接相关的真实合同包括 `ApplicationIdentityRebindMysqlTest`、`ApplicationIdentityMigrationMysqlTest`、`AttachmentObjectStorageMigrationMysqlTest` 与 `AttachmentObjectStorageMinioIntegrationTest`；没有使用 mock 冒充真实并发或迁移证据。完整 backend 单元、MyBatis XML 解析与内存合同均已通过。

全量架构测试唯一遗留属于问题簇 dry-run 服务的既有异常边界，不在登录、权限、附件、应用身份、Station、SDK 六域范围内。本轮没有为制造全绿而修改该模块；六域专属架构门禁以及全量架构测试中的目录、PO、依赖、MyBatis 与 Job 子门禁均已通过。

> **后续更新（2026-09-29，与本节结论相反，故在此显式更正）**：上一段描述的两个数字都已变化。
> `:backend:architectureTest` 的**遗留失败已修复**，现为 **79 项全部通过、0 失败**；
> `:backend:test` 现为 **1431 项通过**（1431 与上表的 1403 之间隔了多次改动，
> 不是同一批用例的计数口径变化）。因此「唯一失败」与「没有为制造全绿而修改该模块」这两句话
> **不再是最新状态**——该模块后来被修改了，且是在先逐条确认它确有缺陷之后修改的，
> 不是为凑绿。本节的 2026-09-10 记录是那一轮的事实，**不改写**；
> 修复明细与后续未决项见 `docs/delivery/29-rca-agent-runtime.md` 第 13 节。

最终静态复扫确认：旧顶级六域包与 `entity` 目录不存在，源码无旧全限定名；`model/po` 无非 `*Po.kt` 文件；Repository 无反向 Service import；附件旧 query/impl 路径无残留。
