# 应用身份域完整分层重构交付

## 1. 结论与边界

本次交付覆盖管理员查询和写入、SDK/站端来源事实、四渠道历史读取、alias/identity 解析、影子事实、人工重绑、reviewed binding 预检与 APPLY、审计、MyBatis 持久化及相关测试。实现遵循后端分层方案第 12.1、13～16 节：入口只依赖 Service 接口，业务实现位于 `service/impl/applicationidentity`，复杂数据访问经 Repository 接口进入 `repository/impl/applicationidentity`，PO 和 Mapper 不越过 RepositoryImpl。

全仓未发现前端、SDK 或 RPC 对 `/api/v1/admin/application-identities` 的消费者，因此本次没有需要同步修改的消费者。当前域没有自动 Scheduler；受控入口是管理员 HTTP 入口以及只读 PREPARE、显式 APPLY 两个一次性 `ApplicationRunner`，不会隐式周期执行。

## 2. 完整调用链

```text
管理员 HTTP / PREPARE CLI / APPLY CLI / 历史影子调用方
  → ApplicationIdentityAdminService / ApplicationIdentityShadowService
    / ApplicationReviewedBindingPreflightService / ApplicationReviewedBindingBatchService
  → 对应 ServiceImpl（鉴权、输入边界、短事务入口、有限重试）
  → Resolver / Matcher / Worker（解析、连通分量、锁与 CAS 编排）
  → Repository 接口（强类型快照和写模型）
  → MyBatisXxxRepositoryImpl（字符串 code、PO、批量完整性校验）
  → Mapper + XML（单表、参数化、有界 SQL）
  → identity / alias / primary fact / 来源渠道表
  → BusinessAuditService（与必须成功的写入处于同一短事务）
```

细分链路如下：

1. 管理员查询：`ApplicationIdentityAdminController` → `ApplicationIdentityAdminService` → `ApplicationIdentityAdminServiceImpl` → identity/fact Repository。诊断的两次相关聚合和 alias 历史读取使用短只读事务；单页 identity 查询使用有界单次 SQL。
2. SDK/站端影子生成：Controller → `ApplicationIdentityShadowServiceImpl` → `ApplicationIdentityShadowWorker` → `ApplicationIdentityShadowSnapshotReader`。来源主表与 SDK/站端从表批量加载后，在事务外执行 NFKC、包名小写、解析和连通分量计算，再由 `ApplicationIdentityShadowCommitWorker` 写前重读 alias/identity 版本并提交。
3. 四渠道历史读取：`ClusterHistoricalShadowService` → `ApplicationIdentitySourceRepository.nextFourChannelInputs`、`PrimaryProblemFactRepository`、`ApplicationIdentitySafeTextRepository` → `CanonicalApplicationTextMatcher`。只有 masking `PASS` 且未删除的安全文本可作为正文证据；JIRA、QUALITY、REDBLACK 不伪造权威 alias。
4. 人工重绑：Controller → Admin Service → `ApplicationIdentityRebindWorker` → identity Repository →审计。锁顺序固定为 alias → source identity → target identity。
5. reviewed binding：HTTP 单条入口或 PREPARE/APPLY CLI → Preflight Service → `ApplicationIdentityReviewedBindingWorker`。APPLY 文件权限、SHA-256、命令指纹、固定范围和 actor 在事务外校验；最多 20 条命令由 Batch Service 在一个短写事务中整体提交或回滚。

## 3. HTTP、任务和数据库兼容契约

管理员路由继续使用 `/api/v1/admin/application-identities`：

| 方法 | 路由 | 契约 |
|---|---|---|
| GET | `/diagnostics` | 只返回解析计数、alias 类型计数和规则版本，不返回正文 |
| GET | `/` | UUID 游标分页，默认 50、最大 100，只列活动 identity |
| GET | `/{applicationId}/aliases` | 最多 200 条版本历史；规范化 alias 只在管理员入口可见 |
| POST | `/shadow-batches` | `afterSourceId >= 0`、`1..100`、固定规则版本 `application-identity-v1` |
| POST | `/{applicationId}/aliases/rebind` | requestId、alias/target 版本和原因保持原 JSON 字段 |
| POST | `/reviewed-bindings` | `NEW_APPLICATION` / `ALIAS_OF_EXISTING` 保持稳定字符串 code |

PREPARE/APPLY 继续使用现有配置前缀 `app.application-identity.reviewed-binding-preflight.*` 和 `app.application-identity.reviewed-binding-apply.*`。APPLY 要求显式正数 `actor-id`，禁止通过直接 SQL 推测管理员；标准输出仅包含聚合计数和命令文件哈希。

V237 的三张域表和编码未改变：`t_application_identity`、`t_application_identity_alias`、`t_feedback_primary_problem_fact`。BINARY(16) UUID、活动 alias SHA-256 键、requestId 唯一键、alias `(alias_public_id, alias_version)` 唯一键及所有 CHECK 约束继续有效。

## 4. 业务类型字典

| 类型 | 稳定 code |
|---|---|
| 来源渠道 | `SDK`、`STATION_FEEDBACK`、`JIRA`、`QUALITY`、`REDBLACK` |
| alias 类型 | `SDK_APP_ID`、`SDK_PACKAGE_NAME`、`STATION_SOURCE_APP_ID` |
| identity 生命周期 | `ACTIVE`、`RETIRED` |
| alias 版本状态 | `ACTIVE`、`SUPERSEDED` |
| alias 证据 | `AUTO_SAME_SOURCE`、`MANUAL_REBIND` |
| 应用解析状态 | `RESOLVED`、`UNRESOLVED`、`AMBIGUOUS` |
| 主问题分类状态 | `PENDING` |
| reviewed binding 决策 | `NEW_APPLICATION`、`ALIAS_OF_EXISTING` |
| 审计动作 | `APPLICATION_ALIAS_REBIND`、`APPLICATION_REVIEWED_BINDING`、`APPLICATION_IDENTITY_SHADOW_BATCH` |
| 审计分类/结果 | `BUSINESS` / `SUCCESS` |
| 解析失败类别 | `APPLICATION_ALIAS_MISSING`、`APPLICATION_ALIASES_MAP_TO_MULTIPLE_IDENTITIES` |

封闭集合在内部以 enum/sealed type 传播，只在 HTTP、MyBatis、任务和审计边界转换字符串。开放的 alias 文本由 `ApplicationAliasKey` 统一校验：NFKC、去首尾空白、最大 200 字符，SDK 包名额外按 `Locale.ROOT` 小写。未知数据库 code 失败关闭，不静默降级。

## 5. 事务、并发和性能不变量

- 影子读取分为来源事实短只读事务与 alias/identity 短只读事务；规范化、解析、连通分量和批量计划均在事务外。写事务重读 alias，并同时比较 aliasVersion、identity ID 和 mappingVersion，过期快照返回冲突。
- 影子提交中的 identity/alias/fact 批量写和成功审计共同提交。活动 alias 唯一键拒绝并发双绑定；`DuplicateKeyException` 只重试一次完整安全阶段。
- 人工重绑先做锁前 requestId 快速重放，再固定锁 alias、source identity、target identity；锁后用当前读重新确认 requestId。source/target 双 mappingVersion CAS、alias 旧版本失效、新版本插入和审计位于同一短写事务。
- 同 requestId 且同命令指纹稳定重放；同 requestId 异指纹返回 409。重放结果读取首次提交保存的目标版本快照，不使用当前可变版本。
- reviewed binding 预检是短只读事务，展示名目标使用一次批量查询消除 N+1。APPLY 的文件读取、0600 权限、SHA、JSON、指纹和范围校验均在事务外；固定 4 条/207 来源的当前治理包小于通用上限 20，并作为一个短事务全成全败。
- Repository 对页大小、alias 键、identity、事实、候选和历史集合强制上限；Mapper 返回缺失、重复、乱序、意外成员或越界均失败关闭。生产 XML 无 `${...}`、`SELECT *`、JOIN、EXISTS 或直接拼接 SQL。

## 6. 授权、审计和敏感文本

HTTP 管理入口沿用管理员权限链并通过 `CurrentWebPrincipalProvider` 获取操作者；Controller 不自行判断角色字符串。写审计仅记录 actor、requestId、稳定 ID、版本、计数、规则版本与人工原因，不记录规范化 alias、候选展示名或反馈正文。历史文本 Repository 只读取 masking `PASS` 且未删除的 title/description；任何未知状态、来源缺行、批量不完整或并发版本变化均停止整批处理。

## 7. 逐文件职责清单

### 7.1 HTTP 与工具入口

- `controller/admin/ApplicationIdentityAdminController.kt`：六个管理员 HTTP 入口，只依赖 Service 接口。
- `controller/admin/ApplicationIdentityAdminContracts.kt`：v1 DTO、Bean Validation、强类型边界转换。
- `tools/ApplicationReviewedBindingPreflightMain.kt`：只读最小 Spring CLI 上下文。
- `tools/ApplicationReviewedBindingApplyMain.kt`：显式写入最小 Spring CLI 上下文与防误启动配置。
- `service/applicationidentity/ApplicationReviewedBindingPreflightCommandRunner.kt`：输入校验、离线准备文件和 SHA 输出。
- `service/applicationidentity/ApplicationReviewedBindingApplyCommandRunner.kt`：0600/SHA/指纹/固定范围/actor 校验及聚合输出。

### 7.2 Service 契约、实现与职责组件

- `ApplicationIdentityAdminService.kt` / `impl/applicationidentity/ApplicationIdentityAdminServiceImpl.kt`：管理员查询、重绑与单条审核绑定。
- `ApplicationIdentityShadowService.kt` / `impl/applicationidentity/ApplicationIdentityShadowServiceImpl.kt`：影子批次公开边界与一次安全重试。
- `ApplicationReviewedBindingPreflightService.kt` / `impl/applicationidentity/ApplicationReviewedBindingPreflightServiceImpl.kt`：批量准备与短只读预检。
- `ApplicationReviewedBindingBatchService.kt` / `impl/applicationidentity/ApplicationReviewedBindingBatchServiceImpl.kt`：有界审核命令的短写全成全败边界。
- `ApplicationIdentityResolver.kt`：基于强类型 alias 快照解析单条来源。
- `CanonicalApplicationTextMatcher.kt`：历史安全文本的确定性精确提及匹配。
- `ApplicationIdentityShadowWorker.kt`：事务外连通分量与写入计划；内含短读 SnapshotReader。
- `ApplicationIdentityShadowCommitWorker.kt`：写前版本复核、批量落库和审计。
- `ApplicationIdentityRebindWorker.kt`：锁序、双 CAS、幂等重放与审计。
- `ApplicationIdentityReviewedBindingWorker.kt`：人工审核候选绑定、新 identity 或既有 identity 分支。
- `ApplicationIdentityCommandFingerprint.kt`：稳定 SHA-256 命令指纹。
- `ApplicationIdentityContracts.kt`：影子结果和解析结果等紧密相关契约。

### 7.3 Repository、Mapper、PO 与 XML

- `repository/applicationidentity/ApplicationIdentityRepositories.kt`：来源、identity/alias、主问题事实三个复杂仓储契约。
- `ApplicationIdentityReviewCandidateRepository.kt`：审核候选批量定位边界。
- `ApplicationIdentitySafeTextRepository.kt`：已脱敏安全文本批量读取边界。
- `ApplicationIdentityModels.kt`：类型字典、值对象、领域快照和写模型。
- `repository/impl/applicationidentity/MyBatisApplicationIdentityRepositoryImpl.kt`：identity/alias 聚合、批量完整性、锁和 CAS。
- `MyBatisApplicationIdentitySourceRepositoryImpl.kt`：来源主表及 SDK/站端从表批量装配。
- `MyBatisPrimaryProblemFactRepositoryImpl.kt`：主问题事实批量 UPSERT、读取和聚合。
- `MyBatisApplicationIdentityReviewCandidateRepositoryImpl.kt`：站端审核候选一次批量定位。
- `MyBatisApplicationIdentitySafeTextRepositoryImpl.kt`：masking PASS 文本批量读取。
- 七个单表 Mapper：`ApplicationIdentityMapper.kt`、`ApplicationIdentityAliasMapper.kt`、`ApplicationIdentityPrimaryFactMapper.kt`、`ApplicationIdentitySafeTextMapper.kt`、`ApplicationIdentitySdkMapper.kt`、`ApplicationIdentitySourceMapper.kt`、`ApplicationIdentityStationMapper.kt`。
- 七个生产 XML：`ApplicationIdentityMapper.xml`、`ApplicationIdentityAliasMapper.xml`、`ApplicationIdentityPrimaryFactMapper.xml`、`ApplicationIdentitySafeTextMapper.xml`、`ApplicationIdentitySdkMapper.xml`、`ApplicationIdentitySourceMapper.xml`、`ApplicationIdentityStationMapper.xml`。
- 十一个 PO：`ApplicationIdentityPo.kt`、`ApplicationIdentityAliasPo.kt`、`ApplicationAliasTypeCountPo.kt`、`ApplicationResolutionCountPo.kt`、`ApplicationIdentitySourcePo.kt`、`ApplicationIdentitySdkPo.kt`、`ApplicationIdentityStationPo.kt`、`ApplicationIdentityStationCandidatePo.kt`、`ApplicationIdentitySafeTextPo.kt`、`ApplicationIdentityVersionCasPo.kt`、`PrimaryProblemFactPo.kt`；它们仅在 Mapper/RepositoryImpl 边界使用。
- `db/migration/V237__create_application_identity_shadow_foundation.sql`：三表、索引、唯一键、外键和 CHECK 约束基线。

### 7.4 测试与门禁

- `architectureTest/.../ApplicationIdentityChainArchitectureTest.kt`：分层、事务位置、锁/CAS/幂等、类型、SQL、KDoc 与控制流门禁。
- `controller/admin/ApplicationIdentityAdminControllerTest.kt`、`security/ApplicationIdentityAuthorizationTest.kt`：HTTP 映射与管理员授权。
- `repository/applicationidentity/ApplicationIdentitySourceRepositoryTest.kt`、`ApplicationIdentityReviewCandidateRepositoryTest.kt`、`ApplicationIdentitySafeTextRepositoryTest.kt`：来源完整性、候选、安全文本与强类型转换。
- `service/applicationidentity/ApplicationIdentityResolverTest.kt`、`ApplicationIdentityShadowWorkerTest.kt`、`ApplicationIdentityRebindWorkerTest.kt`、`ApplicationIdentityReviewedBindingWorkerTest.kt`：解析、影子、重绑与审核写入。
- `service/applicationidentity/ApplicationReviewedBindingPreflightServiceTest.kt`、`ApplicationReviewedBindingPreflightCommandRunnerTest.kt`、`ApplicationReviewedBindingApplyCommandRunnerTest.kt`：批量预检、准备文件、篡改检测与 APPLY 范围。
- `service/applicationidentity/CanonicalApplicationTextMatcherTest.kt`：规范文本精确匹配与歧义处理。
- `service/cluster/HistoricalApplicationTextResolutionTest.kt`：四渠道历史安全文本解析。
- `persistence/applicationidentity/ApplicationIdentityPersistenceContractTest.kt`：迁移、七 XML 语义清单、单表和参数化契约。
- `integration/ApplicationIdentityMigrationMysqlTest.kt`、`ApplicationIdentityRebindMysqlTest.kt`、`ApplicationIdentityMysqlTestSupport.kt` 及四个测试 XML：真实 MySQL 迁移与并发重绑合同。

## 8. “6 个 XML / 实际 7 个”根因

第七个文件是 `ApplicationIdentitySafeTextMapper.xml`。它只从 `t_feedback_content` 读取 masking `PASS` 且未删除的安全文本，服务于四渠道历史应用解析，不写 identity/alias，也没有把 cluster 或 content 表并入身份写聚合。因此差异是旧测试的数量基线过期，不是边界意外扩张。

修复不是把 `6` 机械改为 `7`：契约现在冻结七个文件的精确语义集合，并逐文件解析 XML、拒绝 JOIN/EXISTS/`${...}`/`SELECT *`。任何职责的新增、删除或跨表扩张仍会失败。

## 9. 验证记录（2026-09-09）

以下命令均使用独立工程 `GRADLE_USER_HOME` 并以 `--rerun-tasks` 强制执行：

| 验证 | 结果 |
|---|---|
| `:backend:architectureTest` | 通过，6 个任务均实际执行 |
| 应用身份定向 `:backend:test`（`*ApplicationIdentity*`、`*ApplicationReviewedBinding*`、matcher、历史解析） | 通过，8 个任务均实际执行 |
| `:backend:test` | 通过，8 个任务均实际执行 |
| `:backend:bootJar` | 通过，6 个任务均实际执行 |
| 最终组合 `:backend:architectureTest :backend:test :backend:bootJar` | 通过，12 个任务均实际执行 |

真实 MySQL 合同代码已提供，并覆盖 V237、两个并发事务、同 requestId 一次写入一次重放、ACTIVE/SUPERSEDED 历史和 source/target 双版本推进。但当前环境 `APPLICATION_IDENTITY_MYSQL_ENABLED=false`，URL、username、password 均未提供，所以本次没有执行真实 MySQL 合同，不能把 Mock/单元测试表述为真实数据库通过。测试上下文只接受数据库名 `feedback_platform_test`，配置环境后可补跑上述两个集成测试。

## 10. 回滚与例外

本次没有新增临时架构白名单、兼容 Bean alias、HTTP/RPC 版本或数据库迁移。结构回滚应以应用身份域文件为单位恢复；V237 已存在的数据结构不应随代码结构回滚删除。唯一待目标环境补验项是真实 MySQL 合同，不影响本地架构、单元、契约和制品构建结论。
