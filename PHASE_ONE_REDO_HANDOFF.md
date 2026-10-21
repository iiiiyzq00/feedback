当前目标：
从 `/Users/yang/projects/feedback-platform` 当前工作树无损恢复完整一期系统到 `/Users/yang/feedback-platform`；保留原功能、类名、API、状态机、安全链、飞书登录/权限和前端，仅将最终保留代码的 JDBC/JPA/业务内嵌 SQL 等价迁移到单表 MyBatis XML + Mapper + Repository。

不在范围：
不修改原仓库；不恢复一期契约明确删除的通知/告警页面、人工隐私复核和独立 SDK 页面；不把本地编译、Mock 或静态验证表述成生产联调成功。

关键事实与决策：
- 原仓库 `/Users/yang/projects/feedback-platform` 是只读事实源，约 506 项用户改动；候选 `/private/tmp/feedback-platform-redo.BihSYL` 是完整副本且无 Git，最终目录尚未覆盖。
- 用户拒绝的精简版只在 `/private/tmp/feedback-platform-rejected-backup.uP4CQr` 备份，不得作为功能事实源。
- MyBatis 底层的 MySQL JDBC Driver/JDBC URL 必须保留；清零对象是业务层 JdbcTemplate、手写 JDBC、JPA 和内嵌 SQL。
- 用户已授权连续执行；每批仍做作者自审、自动验证、独立复核，持续 goal 保持 active 直到最终替换与全量门禁完成。

硬约束：
- 不删减 SDK、Station、Jira、导入、归簇、权限、前端功能或断言；业务 SQL 只在单表 MyBatis XML，无 JOIN、跨表子查询、N+1。
- 不编辑已应用 Flyway；保留飞书 OAuth/JWT、独占角色、唯一管理员、authVersion、数据范围、字段遮蔽和审计。
- 不使用浏览器；原仓库不修改；生成物清理和全量验证完成后才替换最终目录。

工作区与修改：
- 原仓库分支 `codex/phase-one-delivery` 只读；当前候选无 Git。
- 已完成并定向验证：完整 SDK 接入/安全、飞书核心登录和一期权限、Station、Jira、导入域、统一质量、通用附件的无损 MyBatis 迁移。
- 归簇现已新增 `ClusterReclassificationPersistence.kt`、`ClusterReclassificationRepository.kt` 及 Run/Snapshot/Content/Link/Cluster 单表 XML；`ClusterReclassificationService` 保留启动、租约、全量处理、覆盖门禁、名称质量修复、成员修复、回滚和审计，但已移除 JDBC/EntityManager。
- `ClusterAiAssistedDecisionService` 与 `ClusterAiEvaluationService` 已改用扩展后的 `ClusterAiTaskTableMapper`/`ClusterAiTaskRepository`；保留 prompt/skill 快照、候选校验、确定性回退、租约、重试、token、延迟、供应商请求号和评估报告。
- 一期列表、详情、分析已有 MyBatis Repository/Mapper；归簇与这些投影的联合回归已通过。
- `Cluster`/`ClusterLink` 的 JPA 注解和 `Day4Repositories` 旧接口尚未删除，因为 MySQL 集成测试仍直接依赖旧 Repository；需与 AI 旧 JPA 边界一起迁移，不能先删导致上下文启动失败。
- `AiTask`、`FeedbackAi` 已退出 JPA；`AiTaskRepository`/`FeedbackAiRepository` 保持原方法合同但改为单表 MyBatis。`AiTaskPipeline` 的来源失效关闭、SKIP LOCKED 认领、租约、重试、处理完成、prompt 快照、shadow 评估、渠道上下文和 enqueue 扫描均已迁移，分类级联和隐私门禁未改。
- `DerivedIndexSyncWorker` 已改用 `DerivedIndexRepository` 与五组单表 Mapper；保留稳定 outbox key、500 条上限、过期租约接管、五次终止、隐私拒绝终止、ES/Milvus 命名空间、删除和重建。分表扫描会越过已有 outbox key，避免首 500 条已存在时饿死后续任务。

验证结论：
- 后端主代码和测试代码编译成功。
- 归簇服务、AI 辅助、AI 评估、Cluster XML 治理、一期列表/详情/分析投影联合定向测试成功；证据限本地自动化。
- 新增 Repository 级回归验证：启动快照先于链接替换和旧簇隔离；覆盖校验按安全正文与活动 MAIN 分表组装；回滚先清运行输出再完整恢复旧 MAIN。
- 独立复核已修正：回滚生命周期只恢复原 SQL 允许的归档/状态变化簇；回滚归档新簇不清空原 `status_changed_by`；AI 完成/失败遇到租约丢失继续保持原先 0 行更新语义。
- AI pipeline、AI 安全契约、派生索引 Repository 和 XML 契约联合回归成功；新增测试证明来源失效任务先关闭再认领、enqueue 跳过稳定键、派生索引跨过已有第一页继续扫描。证据限本地自动化。

未解决 / 待核验：
- 全仓仍有 JDBC/JPA/内嵌 SQL，主要在核心旧实体/Repository、BusinessAuditService、AdminManagementService、alert/report/scheduler/query 辅助服务以及测试中的直接 JdbcTemplate/JPA；AI pipeline 与 DerivedIndexSync 已清零。
- 前后端全量门禁、全仓静态零计数、最终独立复核、生成物清理和 `/Users/yang/feedback-platform` 替换未完成。

下一步（最多 3 项）：
1. 迁移 masking/query/ai/normalization/knowledge/sync 的剩余业务 JDBC，再迁移 alert/report/scheduler；保持原功能与状态机。
2. 将依赖 JdbcTemplate mock 或直接执行 SQL 的测试迁移到 Repository/Mapper 契约，达到主代码和测试代码静态零计数。
3. 执行后端/前端全量门禁和最终复核，清理生成物，精确替换 `/Users/yang/feedback-platform`，确认原仓库未修改。

2026-09-02 最新续接：
- `FeedbackSource`、`FeedbackContent`、`FeedbackIdentity`、`ChannelSdk`、`SdkIngest`、`SystemConfig`、`SystemAuditLog` 均已退出 JPA；原 Repository 方法名按实际调用合同保留并由单表 MyBatis XML 实现。
- 已删除 `spring-boot-starter-data-jpa`、`@EntityScan`、`@EnableJpaRepositories`；三处 Jakarta 事务注解改为 Spring 事务注解。主代码的 JPA 实体/Repository/启动依赖静态清零。
- Source/Content 新增 Repository 和 XML 契约回归；Identity/Channel/SystemConfig/Audit/SdkIngest 完整编译成功。`SdkIngestService` 与 `SdkFeedbackController` 未改名、未删减。
- `LlmQuotaGuard`、`NarrationShadowEvaluator`、`GovernedSkillExecutionService`、`NerAllowlistRegistry`、`FieldConversionHistoryService`、`KnowledgeSearchSkillService`、`NotificationMetrics`、`ReportGovernanceService` 已等价迁移到 Repository + 单表 MyBatis XML；AI 配额与字段转换审计定向测试通过。
- 最新主代码与测试代码编译成功；当前仍有 59 个主代码文件直接依赖 JdbcTemplate（按文件计），测试代码另有 41 个文件直接依赖或 mock JdbcTemplate。最终目录仍未替换。

2026-09-02 第二次续接压缩：
- 新完成 `SeverityPolicyService`、`PromptEvaluationService`、`PromptAutoRollbackMonitor`、`OutboundPrivacyGate` 的 Repository + 单表 MyBatis XML 迁移。
- Prompt 自动回滚已改成候选版本一次读取、近 15 分钟调用指标一次批量读取后在代码中组装，无自 JOIN、无 N+1；失败率和回滚调用语义不变。
- 出站隐私门禁仍保留内容 SHA-256 绑定的 FALSE_POSITIVE_RELEASE 放行、拒绝审计、放行审计和原文不落审计；对应测试已改为 Repository 合同并通过。
- 本批主代码、测试代码编译成功，SeverityPolicy、PromptEvaluation、OutboundPrivacyGate 定向测试成功。当前主代码 JDBC 文件 55 个，测试侧 40 个。
- 下一文件优先级：`AiReviewService`（按 Source/Content/Ai 三张单表分页批量组装）、`AutomaticMaskingRecoveryService`、`MaskingSecurityService`，之后 query 域；`SkillPromptManagement` 较大，AI 小项清完后单独迁移。

2026-09-02 第三次续接压缩：
- `AutomaticMaskingRecoveryService` 已迁移：SDK、ImportRow、ImportBatch、Content、ClusterLink 五个单表 Mapper；批次唤醒和安全正文归簇对账均按 500 条分页/批量组装，无跨表子查询或 N+1。
- `ChannelAcceptanceService` 已迁移：验收、用户显示名、渠道源计数、视图配置分表读取；最新验收用单表窗口函数精确取每渠道最新行，发布/回滚状态机和管理员权限保持。
- `SimilarFeedbackService` 已迁移：Source 权限范围分页，Content 安全状态批量过滤，FeedbackAi 向量同步状态批量过滤，最终可见 ID 集才传入 Milvus；对应测试已改为 Repository 合同并通过。
- 最新主代码/测试代码编译成功，SimilarFeedback 与 OutboundPrivacyGate 定向测试成功。当前主代码 JDBC 文件 52 个，测试侧 39 个。
- 下一步优先：`AiReviewService`；再处理 `MaskingSecurityService` 和 `EnumMappingRegistry`；然后剩余 query 与知识域。大文件不得通过删功能或减少断言清零。

2026-09-02 第四次续接压缩：
- `AiReviewService` 已完整迁移：Source、Content、FeedbackAi 分页批量组装待复核队列；保留 BOTH/HIGH_RISK/LOW_CONFIDENCE/LEGACY_PENDING 筛选、阈值摘要、评论脱敏、单条确认/改判、500 条批量复核、权限范围、PENDING CAS 和审计。
- AI 确认不再用 Content JOIN FeedbackAi 更新，而是批量读取待确认 AI 结果后通过单条 CASE 批量更新 Content；不存在 N+1。作者复核移除了改判 affected-row 的过严断言，保持原实现由 FeedbackAi PENDING CAS 决定并发成败。
- `EnumMappingRegistry` 已迁移到单表 SystemConfig MyBatis 读取，保留无 Repository 时的内嵌 v1/v2 回退、快照摘要和全部渠道映射语义。
- AI Review 6 项测试、EnumMapping 测试、ImportBatchService 40 项联合回归及主/测试代码编译成功。
- 当前主代码 JDBC 文件 50 个，测试侧 38 个。下一步先做 `MaskingSecurityService`（需 SDK/Import/Station/Identity/Audit 多单表编排）和 `MaskingEvaluationEvidenceService`，再清 query/knowledge。

2026-09-02 第五次续接压缩：
- `MaskingSecurityService` 已完整移除 JDBC：SDK 隔离列表/重试/关闭/原文信封、ImportRow 重试/原文、Station 可复核判定/保持隔离/放行、授权联系方式和审计均为独立单表 Mapper + `MaskingSecurityRepository` 编排。
- 保留角色/permission 门禁、联系方式工单与用途校验、SDK/表格/Station 原文解密、密钥和明文字节清零、人工复核三决策、评论与输出审计、失败审计、批量上限和异常状态机。
- 作者安全复核将联系方式从通用 Identity 全量读取改为专用查询：只读取授权依据有效且未过期的密文，避免扩大敏感数据读取面。
- 新增 MaskingSecurity/AiReview/ChannelAcceptance/SimilarFeedback/AutomaticRecovery XML 静态检查无 JOIN/EXISTS；主代码与测试编译、MaskingSecurity 权限、AiReview、EnumMapping 测试通过。
- 当前主代码 JDBC 文件 49 个，测试侧 37 个。下一步优先迁移 `JiraKnowledgeCandidateService`，再 `KnowledgeClosureService`；随后处理剩余 query，`MaskingEvaluationEvidenceService` 独立成批。

2026-09-02 第六次续接压缩：
- `JiraKnowledgeCandidateService` 已接入 `JiraKnowledgeCandidateRepository` 与 Source/Content/Jira/Knowledge/Audit 五个单表 Mapper；完整保留预览口径、权限、1..500 上限、候选排序、排除规则、标题/模块/类型归一、评分、SHA-256 指纹、证据/内容/tags JSON、`INSERT IGNORE` 幂等和审计。生成后重新读取事实，保持旧实现返回最新 `alreadyDrafted` 统计的语义；空安全正文批次已防止无效 `IN ()`。
- `KnowledgeClosureService` 已接入 Knowledge/Incident/Audit 三个单表 Mapper；保留 SHOWN/ADOPTED/EFFECTIVE/INEFFECTIVE 校验、评价前必须采纳、事件幂等、审计详情、四类累计、六位有效率和最后使用时间。旧跨表相关子查询改为审计表单次聚合后单表更新知识。
- 两批主代码/测试代码编译成功；新增 XML 静态扫描无 JOIN/EXISTS，服务与 Repository 无 JDBC/内嵌 SQL。证据限本地自动化。
- 当前主代码 JDBC 文件 47 个。下一步先迁移 `MetricGovernanceQueryService` 等 query 小批，再处理 `DashboardQueryService`/`IngestionOperationsQueryService` 大批；测试侧直接 JDBC 仍待最终统一清零。

2026-09-02 第七次续接压缩：
- `MetricGovernanceQueryService` 已迁移为 SystemConfig/Source/Content/FeedbackAi/ClusterLink 五个单表 Mapper。来源按 id 每 500 条分页，当前页批量装配权限范围、隐私、AI 和活动 MAIN 事实；13 个计数、六项对账门禁和 `metric-reconciliation-v3` 返回保持。空模块/地域权限显式失败关闭；异常 source 同时有新簇/已有簇链接时继续暴露对账不一致。定向 3 项测试通过。
- `IndexConsistencyAdminService` 已迁移为 AiTask/FeedbackAi/Cluster/Audit 单表 Mapper；保留 outbox 状态、待同步 gauge、漂移 gauge、失败任务重放 CAS、重算审计和 outbox 补种。主/测试代码编译通过。
- `MaskingEvaluationEvidenceService` 已迁移为 SystemConfig 与 MaskingAudit 单表 Mapper；保留数据集登记/独立复核/独立批准/过期、运行唯一性、四项阈值、失败摘要哈希、运行审批门禁、发布 EVALUATED CAS 和定时过期。运行/数据集/发布由有限单表读取在代码中装配，列表数据集按 ID 批量读取，无自 JOIN/N+1；新旧记录统一 businessId 兼容。
- 上述新增 XML 静态扫描无 JOIN/EXISTS，三个服务与 Repository 无 JDBC/内嵌 SQL；主/测试代码编译成功。证据限本地自动化。
- 下一步优先迁移 `ProductFeedbackRequestService`、`FeedbackAssignmentService` 等 query 小批；随后处理 IngestionOperations/Dashboard 大批和 alert/report/scheduler。

2026-09-02 第八次续接压缩：
- `ProductFeedbackRequestService` 已迁移为 Request/Event/User/Source/Content/Audit 六个单表 Mapper；完整保留创建、权限范围、管理员/个人列表、状态机、负责人有效性、验收确认/驳回、评论、事件时间线、CSV 导出和审计。列表/事件用户名称批量加载，无 JOIN/N+1。独立复核恢复详情历史 `SELECT *` 的蛇形字段合同与 accepted/completed 时间，未擅自改成列表驼峰合同。
- `BusinessAuditService` 与 `HttpAuditQueueWorker` 已迁移为 Lock/SystemAudit/HttpQueue 三个 Mapper；保留 MySQL JSON 规范化、GET_LOCK/RELEASE_LOCK、epoch 2 前序哈希、哈希材料顺序、正式审计插入、最新链验证、队列原子认领、租约接管、五次终止和安全错误摘要。
- 产品反馈与公共审计主/测试代码编译成功；`RequestAuditFilterTest` 通过；新增 XML 静态扫描无跨表 JOIN，服务/Repository 无 JDBC 或内嵌 SQL。证据限本地自动化。
- 当前主代码 JDBC 文件 42 个（公共审计迁移后预期），测试侧约 36 个。下一步先做 `FeedbackDistributionCompensationService`/`FeedbackAssignmentService`，再进入 query 大批。

2026-09-02 第九次续接压缩：
- `FeedbackDistributionCompensationService` 已迁移为 HandoffTask/DistributionBatch/Assignment/Workflow/Content/DispositionEvent 六个单表 Mapper；保留 DELIVERED/UNKNOWN/FAILED 回执、已接受任务保护、整批补偿、状态与所有权并发门禁、原负责人/工作流/兼容状态恢复、逐条回滚事件、空批次失败、指数退避和定时对账。
- 补偿前一次批量读取当前 owner，移除逐任务查询；重复 assignment 继续失败关闭，不会用 `associateBy` 静默吞掉异常。主/测试代码编译成功，XML 无跨表 JOIN，服务/Repository 无 JDBC/内嵌 SQL。
- 当前主代码 JDBC 文件精确为 41；测试侧约 36。下一步迁移 `FeedbackAssignmentService`，然后按体量处理 AnalysisAssistant/IngestionOperations/Dashboard；alert/report/scheduler 仍是最大尾部。

2026-09-02 第十次续接压缩：
- `IdempotencyConflictService` 已迁移为 AlertOccurrence/User 两个单表 Mapper；保留导入冲突稳定 fact key、recordId 与 payload 指纹只存 SHA-256、`INSERT IGNORE`、OPEN/RESOLVED/IGNORED 列表、200 条上限、处理人名称、OPEN 状态 CAS 和认证操作者。用户名称一次批量装配，无 JOIN/N+1。
- 幂等冲突主/测试代码编译成功，新增 XML 无 JOIN，服务/Repository 无 JDBC/内嵌 SQL。
- 当前精确静态计数：主代码直接 JDBC 40 个文件，测试侧直接使用或 mock JDBC 36 个文件；候选仍在 `/private/tmp/feedback-platform-redo.BihSYL`，最终 `/Users/yang/feedback-platform` 未替换，原仓库未修改。
- 下一步优先按小批迁移 AlertTrendBaseline/NotificationOperations/AlertRuleAcceptance 等 alert 服务，再处理 FeedbackAssignment 与 query 大批；之后 report/scheduler/admin/SkillPromptManagement，最后测试 JDBC 清零和全量门禁。

2026-09-02 第十一次续接压缩：
- `NotificationOperationsService` 已迁移到单表 `NotificationOperationsMapper` + Repository；保留策略矩阵、渠道归一、状态校验、分页、窗口内分渠道送达率、失败/死信、全量队列积压和最老积压秒数语义。主/测试代码编译成功，服务无 JDBC/SQL，XML 无 JOIN。
- 当前精确主代码 JDBC 文件 39 个，测试侧仍约 36 个。下一步直接迁移 `AlertRuleAcceptanceService` 与 `AlertQualityFactService`，再 `AlertTrendBaselineService`；随后进入 FeedbackAssignment 和大型 query。

2026-09-02 第十二次续接压缩：
- `AlertRuleAcceptanceService` 已迁移为 AiTask/AlertRule/AuthUser 单表 Mapper；保留 REPLAY 完成且零外发、演练无生产影响且仅 IN_APP、精确率/召回率、VALIDATED、评估人与批准人隔离、routing PASSED 才可批准。列表规则码和人员名批量装配；缺失规则仍按原 INNER JOIN 语义过滤。
- `AlertQualityFactService` 已迁移为 SystemConfig/AlertOccurrence/Attachment/ImportRow/Source/User 单表 Mapper；脱敏指标、错误去重确认/修复、来源存在性、处理人、附件完成数与失败率均保留。原附件 UNION 改为两表各自聚合后代码相加；缺失确认人仍按原 INNER JOIN 语义过滤。
- `AlertTrendBaselineService` 已迁移为 Source/Content/ClusterLink/Occurrence 单表 Mapper；小时事实按 500 条 source 分页与批量装配，簇按 source 去重，基线批量 upsert。当前所有簇的 28 天基线一次读取，消除逐簇 N+1；median/MAD、672 小时缺失补零、阈值和最小样本保持。定向测试通过。
- 三批主/测试代码编译成功，服务/Repository 无 JDBC/内嵌 SQL，XML 无 JOIN。当前精确静态计数：主代码 JDBC 36 文件，测试侧 35 文件。
- 下一步继续小型 alert：ExternalSecurityEvent/AlertLoopHealth/AlertReviewGovernanceMetric/OncallSchedule；再进入 FeedbackAssignment 和大型 query。

2026-09-02 第十三次续接压缩：
- `AlertLoopHealthService` 已迁移为 Rule/Occurrence/Instance/Notification 四个单表 Mapper；保留四类风险覆盖、最近评估、开放/24h恢复告警、通知积压/死信/最近送达和 NOT_READY/DEGRADED/HEALTHY 判定。2 项定向测试通过。
- `AlertReviewGovernanceMetricService` 已迁移为 ClusterLink/SystemConfig/SdkIngest/ImportRow/Occurrence 单表 Mapper；保留 SLA 开关、SDK 紧急度专属配置优先且 ALL 回退、导入 SLA、7 天人工改判、规则输入完整性。规则合同与数据合同分表读取后按 contractCode/ruleCode 完整组装，同一 ruleCode 多配置保持原 GROUP BY 语义。定向测试通过。
- `ExternalSecurityEventService` 已迁移为 Audit/AlertInstance/Occurrence/Incident/IncidentAlertLink 五个单表 Mapper；HMAC、5 分钟重放窗、provider secret、P0、Incident、manual closure、payload 仅 SHA-256、嵌套 evidence、幂等审计与事务回滚均保留。
- 三批主/测试代码编译成功，服务/Repository 无 JDBC/内嵌 SQL，XML 无 JOIN。当前精确计数：主代码 JDBC 33 文件，测试侧 33 文件。
- 下一步迁移 `OncallScheduleService`，然后继续 AlertIncidentLifecycle/AlertLifecycle/VersionReleaseFact 等；再进入 query 大批。

2026-09-02 第十四次续接压缩：
- `OncallScheduleService` 已迁移为 SystemConfig/AuthUser/AuthUserRole/TeamMemberLegacy/OncallScheduleLegacy 单表 Mapper；保留团队汇总、成员/可选用户、唯一负责人清理、成员未来排班保护、93 天日历、空档回退、团队编辑权限、排班冲突、旧表写入触发器同步链和取消乐观锁。
- 独立复核保持用户可见性差异：成员只展示有效用户；日历历史排班不因用户停用而消失；displayName 为空不擅自回退 username。主/测试代码编译成功，服务/Repository 无 JDBC/SQL，XML 无 JOIN。
- 当前精确计数：主代码 JDBC 32 文件，测试侧 33 文件。
- 下一步迁移 AlertIncidentLifecycle、AlertLifecycle、VersionReleaseFact、NotificationProviderReceipt 等中小 alert；随后进入 FeedbackAssignment/AnalysisAssistant/IngestionOperations/Dashboard。

2026-09-02 第十五次续接压缩：
- `AlertIncidentLifecycleService` 已迁移为 AlertInstance/Incident/SystemAuditLog 三个单表 Mapper + Repository；完整保留告警 21 条状态边、Incident 5 条状态边、行锁与 lifecycleVersion 乐观锁、ACK/恢复/归档时间、commander、resolution/closure 证据、关联反馈关闭顺序和 `alert-lifecycle-v1` 幂等审计时间线。
- 作者自审修复 P0 结论缺失时 `null.toString()` 可能误过门禁的问题；现在必须存在 actor 且 conclusion 非空并至少 2 字符。Commander 收口为数值 ID，不向持久层传递 `Any`。
- 新增 5 项生命周期测试，覆盖全部允许状态边、非法状态、版本冲突、P0 人工证据、Incident 证据和先关闭关联反馈再更新；定向测试及主/测试编译成功。新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/内嵌 SQL。
- 当前精确计数：主代码 JDBC 31 文件，测试侧 33 文件。下一步迁移 `AlertLifecycleService`、`VersionReleaseFactService`、`NotificationProviderReceiptService`；之后继续其余 alert 和 query 大批。

2026-09-02 第十六次续接压缩：
- `NotificationProviderReceiptService` 已迁移为 AlertNotification/SystemAuditLog 两个单表 Mapper + Repository；保留 HMAC-SHA256、5 分钟时间窗、provider secret 长度、渠道/事件白名单、DTMF 1、回执幂等、payload SHA-256、DELIVERED/CONNECTED/FAILED 分支及升级链回调。
- 回执列表改为字段名完全等价的 typed PO，HTTP JSON 合同不变；测试已改为 Repository 合同并通过。主/测试编译成功，新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/内嵌 SQL。
- 当前精确计数：主代码 JDBC 30 文件，测试侧 32 文件。`AlertLifecycleService` 与 `VersionReleaseFactService` 都含多表统计/N+1，必须分表分页批量组装，不得机械搬迁；下一步先迁移 `DeadLetterService`，再完成这两个大批。

2026-09-02 第十七次续接压缩：
- `DeadLetterService` 已迁移为 AlertNotification/AlertOccurrence/SystemAuditLog 三个单表 Mapper + Repository；保留按渠道分页、通知详情、投递尝试、人工证据、REPLAY/IGNORE 状态 CAS、认证操作者、重放计数、错误清理和审计。
- 详情与列表继续输出原字段名；持久层改为 typed PO，Service 只做合同映射。新增 3 项测试覆盖详情双证据区、成功重放后审计、CAS 失败不写审计；定向测试与主/测试编译成功。新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/内嵌 SQL。
- 当前精确计数：主代码 JDBC 29 文件，测试侧 32 文件。下一步完成 `AlertLifecycleService` 的 Suppression/Rule/Config/Instance/IncidentLink/Notification 分表批量编排，再迁移 `VersionReleaseFactService`，随后继续剩余 alert/query。

2026-09-02 第十八次续接压缩：
- `AlertLifecycleService` 已迁移为 Notification/Rule/SystemConfig/AlertInstance/IncidentLink/AuthUser 六个单表 Mapper + Repository；保留全局与规则静默、P0 不可静默例外、时间重叠、未来静默删除、冷却期、30M/1H/2H 升级策略、原升级 JSON 标记、Incident 严重度联动和站内升级事实。
- 升级任务改为告警主表按 id 每 500 条分页，当前页批量读取活动规则、已有升级标记和最新 Incident；严重度按目标批量更新，但每条仍调用原 `reflectSeverityUpgrade`，不存在 N+1 或功能缩减。重复活动 ruleCode 失败关闭。
- 新增 4 项测试覆盖全局 P0 静默例外、规则专属静默、认证创建静默和升级标记跳过；与 AlertCombinatorRecovery、NotificationEscalationSeverityUpgrade 联合回归及主/测试编译成功。新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/内嵌 SQL。
- 当前预期主代码 JDBC 28 文件、测试侧 32 文件；下一步迁移 `VersionReleaseFactService` 的版本/Source/Content/ClusterLink 分表有界计算，再继续剩余 alert/query。

2026-09-02 第十九次续接压缩：
- `VersionReleaseFactService` 已迁移为 SystemConfig/Source/Content/ClusterLink 四个单表 Mapper + Repository；保留发布列表与保存、30 分钟新版本窗口、7 天对照窗口、20 条最小样本、1% 当前占比、2 倍对照率、双渠道、S0/S1 30% 和可配置发布观察窗口。
- Source 按 id 每 500 条分页，当前页批量读取 PASS 且未删除 Content 与活动 MAIN；按实际配置主键分别累计发布事实，列表仍用 legacyId 兼容展示。多渠道统计只扫描最近 30 分钟；发布窗口末端保持原 SQL 的包含边界；Content 一源多行失败关闭。
- 新增 2 项可复算阈值测试并通过；与 AlertLifecycle 联合回归、主/测试编译成功。新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/内嵌 SQL。
- 当前精确计数：主代码 JDBC 27 文件，测试侧 32 文件。下一步继续迁移剩余 alert（优先 EntityClosure/AlertQuery/Rule 等），随后 query/report/scheduler/admin/SkillPromptManagement，最后测试 JDBC 清零与全量门禁。

2026-09-02 第二十次续接压缩：
- `EntityClosureService` 已迁移为 Incident/ClusterRootCause/Cluster/RCA/Knowledge/Audit/ClusterLink/Source/Content 九个单表 Mapper + Repository；活动关联按 id 每 500 条分页，其余按 500 ID 批量加载并在代码中组装。
- 完整保留数据范围拒绝、六项闭包检查、READY/PASSED/BLOCKED、反馈关闭与 `ENTITY_CHAIN_CLOSE` 审计。独立复核修正有效知识口径：有效使用证据统计全部关联知识，不错误限定为当前 PUBLISHED；发布覆盖仍是独立门禁。
- `EntityClosureServiceTest` 已改为 Repository 合同并新增越权失败关闭；与 AlertIncidentLifecycle 联合测试及主/测试编译成功。新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/SQL。
- 当前精确计数：主代码 JDBC 26 文件，测试侧 31 文件。下一步迁移 `AlertQueryService`，再处理 AlertRule/Engine/ProductionGovernance/Notification 链；之后 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第二十一次续接压缩：
- `VersionReleaseReportScheduler` 已迁移为 SystemConfig 单表 Mapper + Repository；保留发布后 3 天触发、config_key 包名/版本回退、Asia/Shanghai 09:00、系统 ADMIN 上下文、发布日至后两日且不超过今天的报告区间。
- `MaintenanceSchedulers` 已迁移为专用 MyBatis 命名锁 Mapper + Repository；五个任务及锁名不变，并在公开调度入口增加事务边界，确保 GET_LOCK/RELEASE_LOCK 使用同一线程绑定连接。新增测试覆盖锁不可用跳过与任务异常仍释放。
- 上述两批与 EntityClosure/VersionRelease 联合测试、主/测试编译成功；新增 XML 单表且无 JOIN/EXISTS，服务无 JDBC/内嵌 SQL。
- 当前精确计数：主代码 JDBC 24 文件，测试侧 31 文件。`AlertQueryService` 已完成只读拆解，下一步实施 AlertInstance 主分页 + Rule/Link/Occurrence/Incident/User/RCA/Knowledge/Audit 分表装配并迁移 SQL 字符串测试。

2026-09-02 第二十二次续接压缩：
- `AlertQueryService` 已完整迁移为 AlertInstance 主扫描与 Rule/IncidentLink/Occurrence/ClusterLink/Source/Content/User/UserRole/Incident/RCA/ClusterRCA/Knowledge/Notification/Audit 单表 Mapper + Repository。
- 保留告警列表全部筛选/排序/字段/metricVersion、权限范围、Operator 无来源告警、统计/P3 摘要、详情、证据、Incident 完整蛇形字段、ACK/解决/转派/静默、RCA、知识推荐、升级链和时间线。列表按主表 500 条分块，权限过滤后计算准确 total/page；缺失 Rule 继续按原 INNER JOIN 语义过滤。
- 转派按用户定向读取活动 ADMIN/OPERATOR 角色与有效账号；Incident 逐个做 version/status CAS，事务内最终 affected=1。静默保留行锁、版本、状态、P0 与重复静默门禁。独立复核恢复审计时间线使用 created_at，并保持 processedToday 为今日零点以后。
- 原 SQL 字符串扫描测试已改为 Repository 行为合同；新增权限分页测试证明越权记录不进入 total/page，管理员也不接纳缺失规则。AlertQuery/动作/生命周期/EntityClosure 联合测试及主/测试编译成功；新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/SQL。
- 当前精确计数：主代码 JDBC 23 文件，测试侧 31 文件。下一步迁移 AlertRuleService/AlertProductionGovernance/AlertEngine，再处理 NotificationDispatcher/Escalation/InApp；随后 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第二十三次续接压缩：
- `ReportAiCircuitBreaker`（位于 `ReportNarrationService.kt`）已迁移为 SystemConfig/Audit/Incident 三个单表 Mapper + Repository；`ReportNarrationService` 的 Prompt、隐私门禁、LLM 调用和降级返回未改。
- 完整保留状态初始化、CLOSED/OPEN/HALF_OPEN、连续 10 次失败、HALF_OPEN 失败、5 分钟下次探测、成功恢复、首次开路审计、单一开放降级 Incident 和恢复解决 Incident。Incident 防重使用同一 `t_incident` 表内的原子 NOT EXISTS，不是跨表查询。
- `ReportAiCircuitBreakerTest` 已改为 Repository 合同并新增第 10 次失败测试；定向测试及主/测试编译成功。服务/Repository 无 JDBC/SQL，XML 无 JOIN。
- 当前精确计数：主代码 JDBC 22 文件，测试侧 30 文件。下一步继续 AlertProductionGovernance/AlertRule/AlertEngine，随后 Notification 与剩余 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第二十四次续接压缩：
- `AlertProductionGovernanceService` 已迁移为 SystemConfig/AlertRule/AiTask/AuthUser/Audit 单表 Mapper + Repository；保留合同 DRAFT/VALIDATED/APPROVED/SUSPENDED/EXPIRED、验证与批准人隔离、供应商生产验收与双人审批、规则合同门禁、replay evidence、SHADOW 发布、旧 ACTIVE 退役、值班门禁及运行时自动禁用。
- 原合同关联、活动规则治理、值班人员三类跨表查询已拆为单表批量读取和代码装配；供应商渠道一次批量读取，无 N+1。合同 ready 使用数据库 `NOW(3)` 计算，避免应用/数据库时钟偏差；重复合同状态失败关闭。
- `AlertProductionGovernanceServiceTest` 已改为 Repository 合同，定向测试和主/测试编译成功。新增 XML 无 JOIN/EXISTS，服务/Repository/测试无 JDBC 或内嵌 SQL。
- 当前精确计数：主代码 JDBC 21 文件，测试侧 29 文件。下一步迁移 `AlertRuleService`，再 `AlertEngine`、NotificationDispatcher/Escalation/InApp；随后剩余 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第二十五次续接压缩：
- `AlertRuleService` 已迁移为 SystemConfig/AlertRule/AlertInstance/AiTask/AuthUser 单表 Mapper + Repository；目录规则、规则列表、完整规则创建/版本化更新、启停生产门禁、批量排序、认证模拟运行与历史列表全部保留。
- 原规则列表相关子查询改为 AlertInstance 一次分组批量读取；模拟历史的 Rule LEFT JOIN、AuthUser INNER JOIN 与过滤后 LIMIT 通过 AiTask 主键向后分页、批量读取用户/规则并在代码中恢复。规则排序改为单条 CASE 批量更新，参数全部绑定且无动态 SQL 字符串构造。
- 作者自审修复 MySQL “值未变化不计 affected rows”可能导致重复排序误失败的问题；不再错误要求所有 priority 都发生变化。新增 4 项服务测试覆盖完整创建载荷、版本更新保留 enabled、生产门禁失败关闭和模拟零外发；与生产治理联合回归、主/测试编译成功。
- 新增 XML 无 JOIN/EXISTS，服务/Repository/测试无 JDBC/SQL。当前精确计数：主代码 JDBC 20 文件，测试侧 29 文件。下一步迁移 `AlertEngine`，再 NotificationDispatcher/Escalation/InApp；随后剩余 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第二十六次续接压缩（AlertEngine 批次进行中，尚未关闭）：
- 已新增执行链 `AlertEngineRepository` 与规则/实例/Incident/关联/Occurrence/命名锁单表 Mapper；`evaluateAll`、活动规则评估、开放实例复用、告警与 Incident 原子创建、Occurrence、RCA 触发、通知路由和 P0 人工闭包语义已接入 Repository，主/测试代码当前可编译。
- 已新增 `AlertMetricRepository` 及 Source/Content/Identity/ClusterLink/Cluster/Occurrence/Notification/ImportBatch/ImportRow/SdkIngest/SystemConfig/AiTask/Jira/SdkChannel 单表 Mapper。原直接单表指标保持数据库时间窗；原跨表指标改为 Source/Jira 主键每 500 条分页、关联表批量加载、代码聚合，涵盖联系升级、复发、内容错误、红黑洪峰、长期未闭环、设备持续失败、高风险跨渠道、用户安全/隐私/升级变砖/数据丢失/儿童内容、关键簇、设备型号和公开负面等全部原指标分支。
- 作者自审已修复 Jira 页内源记录全部缺失时空 IN 查询风险。当前仍在进行：`AlertEngine.kt` 内为逐分支语义复核临时保留了一段块注释形式的旧 JDBC 指标源码，必须在本批关闭前用 `apply_patch` 彻底删除；随后补 AlertEngine/Metric Repository 测试、检查 XML、定向回归并复核时间边界与多行乘法语义。最终目录仍未替换。

2026-09-02 第二十七次续接压缩：
- `AlertEngine` 批次已关闭：临时旧 JDBC 对照块已彻底删除；70 个原指标代码全部存在且逐项映射。执行链、规则组合/恢复、生产治理、Incident/RCA、通知路由均保留。
- 独立语义复核确认 Source+Content 分母、身份/簇去重、MAIN 与全部活动关联差异、规则复发、Jira 日期回退、用户风险正则、AI 连续失败、最新 5 条脱敏状态等口径。修复设备持续失败原 SQL `SUM` 按 JOIN 行累计而非按 distinct source 累计的隐含语义，并新增测试锁定。
- 指标事实加载改为按需批量读取身份、MAIN/活动簇关联与 SDK 数据；简单指标不再读取无关表。新增 3 项 Repository 测试覆盖跨渠道联系升级、有效内容分母与设备 JOIN 行阈值；与组合恢复、生命周期、生产治理联合回归及主/测试编译成功。
- 新增 XML 无 JOIN/EXISTS，服务/Repository 无 JDBC/内嵌 SQL。当前精确计数：主代码 JDBC 19 文件，测试侧 29 文件。下一步迁移 `AlertRuleIsolatedTestService`，再 NotificationDispatcher/Escalation/InApp；随后剩余 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第二十八次续接压缩（AlertRuleIsolatedTest 批次进行中）：
- 已新增 `AlertRuleIsolatedRepository` 与 Rule/AiTask/AuthUser/Audit 单表 Mapper；保留 REPLAY/SHADOW/DRILL 三模式、运行输出回写、演练子任务、IN_APP/零生产影响隔离、OPEN→ACKNOWLEDGED→RESOLVED 状态机与安全审计。
- 演练列表原 AiTask 自连接 + AuthUser JOIN 已拆成 Drill 主键每 500 条向后分页、父任务与用户批量读取、代码恢复双 INNER JOIN 后 LIMIT 语义。
- 已为共享指标事实 Mapper 增加任意 `[start,end)` 历史窗口的 Source、Occurrence、ImportBatch、ImportRow、SdkIngest 查询，为完整历史回放等价迁移做准备。当前服务尚未切换，批次未关闭；下一步实现历史窗口代码聚合、接入 Service、修复审计 targetId 必须使用外部展示 ID、补测试与回归。

2026-09-02 第二十九次续接压缩：
- `AlertRuleIsolatedTestService` 已完整迁移为 Repository + 共享指标事实边界；REPLAY 的 55 个原历史指标分支逐项对齐且集合计数一致，SHADOW 与 DRILL 未简化。旧 JDBC 对照块已彻底删除。
- 历史指标严格使用 `[start,end)`；Source 每 500 条分页并批量读取 Content/Identity/ClusterLink/Cluster/SdkChannel。Import、Occurrence、SdkIngest 等单表聚合留在 XML。长期未闭环继续使用 end 前 7 天窗口，设备持续失败继续保留 JOIN 行 SUM，趋势/版本/质量/安全服务继续使用历史 end 参数。
- Drill 主列表恢复父任务和用户双 INNER JOIN 后 LIMIT；状态锁、IN_APP/零生产影响门禁、路由建议输出回写、审计均保留。审计 targetId 已修正为外部展示 ID，而非内部主键。MySQL TIMESTAMPDIFF(MINUTE) 的整数截断也已恢复。
- 新增 4 项测试（含历史半开窗口分母、父/用户过滤、展示 ID 审计），隔离演练与指标联合回归、主/测试编译成功。当前精确主代码 JDBC 18 文件、测试侧 29 文件。下一步迁移 NotificationDispatcher、NotificationEscalationService、InAppNotificationService。

2026-09-02 第三十次续接压缩：
- `NotificationDispatcher` 与同文件 `NotificationOutbox` 已迁移为 AlertNotification/AuthUser/AlertOccurrence/Audit 单表 Mapper + Repository；八个治理渠道、P3 日报、不可持久化明文联系方式、飞书私信/任务 token 门禁、同渠道 QUICK_RCA 前序投递门禁、网络错误分类与外部 I/O 均保留。
- Outbox 保留 `FOR UPDATE SKIP LOCKED`、SENT 60 秒回收、全局递增 attempt_no、指数退避、DELIVERED/FAILED/DEAD_LETTER、投递证据、死信审计和升级回调。投递时长继续以毫秒存储并用预计算微秒绑定重建 window_start，避免 XML 算术表达式兼容风险。
- 两个原 JDBC mock 测试已改为 Repository/typed PO 合同；8 渠道、快 RCA 阻断、配置失败、快照、重试、死信、成功和重放 attempt 回归通过。新增 XML 无 JOIN/EXISTS，服务/Repository/测试无 JDBC/SQL。
- 当前精确计数：主代码 JDBC 17 文件，测试侧 27 文件。下一步迁移 `NotificationEscalationService` 与 `InAppNotificationService`，再进入剩余 query/report/scheduler/admin/SkillPromptManagement。

2026-09-02 第三十一次续接压缩：
- `NotificationDispatcher`/`NotificationOutbox` 批次复核完成。通知入队、P3 日报、同渠道源告警门禁、活动用户加密联系方式读取、Outbox claim、Attempt、完成状态、死信审计分别使用单表 Mapper。
- 作者自审将投递窗口起点的 `durationMs*1000` 从 XML 算术改为应用预计算 `durationMicros` 后参数绑定，保持原微秒时间窗且规避驱动/SQL 方言解析差异。联系方式 Mapper 仅允许 EMAIL/PHONE 两个固定列分支，不接受任意列名。
- `NotificationDispatcherTest` 与 `NotificationOutboxTest` 已彻底移除 JDBC mock，定向测试及主/测试编译通过。当前仍为主代码 JDBC 17、测试 27；下一步继续 NotificationEscalation，再 InApp。

2026-09-02 第三十二次续接压缩：
- 用户明确调整交付范围：当前候选中已经保全或完成 MyBatis 迁移的非一期功能代码不再裁除，告警、通知、RCA、知识库等与完整一期系统一起保留；最终交付仍须排除 build、缓存、虚拟环境、运行附件、APK 和验收输出等生成物。
- `NotificationEscalationService` 已迁移为 Notification/Config/User/Rule/Occurrence/Incident/Audit 七个单表 Mapper + Repository；保留 P0/P1 公共基线、个人确认链、级别升级、超时推进、失败 CAS、确认、无值班/链耗尽和审计语义。
- 失败通知通过持久化的 `escalation_target_id` 解析原责任人顺序，避免链推进后错误使用当前责任人；展示 ID 与内部主键继续分别处理。配置解析保持 PRIMARY/TEMPORARY、BACKUP、LEADER 顺序和有效用户去重。
- 两个原 JDBC mock 测试已改为 Repository 合同；首次回归发现升级测试缺少 `firstContent` 桩，补齐原有“从链首条通知恢复事件”的测试输入后，告警升级、基线、Dispatcher、Outbox 共 18 项定向测试通过，主/测试代码编译通过。新增 XML 无 JOIN/跨表子查询，Service/Repository/测试无 JDBC 或内嵌 SQL。
- 当前精确生产 JDBC 文件仍为 17（本批服务已不在其中）；下一步先迁移 `InAppNotificationService`，随后处理 query/report/scheduler/admin/SkillPromptManagement 与剩余测试 JDBC，最终执行全量门禁。

2026-09-02 第三十三次续接压缩：
- `InAppNotificationService` 已迁移为 AlertNotification/Handoff/Reminder/Disposition/Assignment/ProductEvent/ProductRequest/AuthUser/Audit/IncidentLink/Incident/AlertInstance/AlertRule 单表 Mapper + Repository；原五类站内通知全部保留，没有缩减为仅告警通知。
- 列表继续按各来源 `page*size` 窗口读取并做稳定全局时间排序；相同时间保持告警→交接→催办→处置→产品反馈的原聚合顺序。未读筛选会按 500 条继续向后读取，避免最新一批均已读时错误返回空页；跨表可见性通过 Assignment/ProductRequest 批量组装。
- 告警通知继续补齐活动 Incident、同 Incident 活动告警数、源告警/规则、源告警是否对当前用户可见、RCA/反馈/告警/报告目标路径。已读回执按五个 target_type 保留原负数 ID 命名空间、幂等键和安全可见性门禁；全部已读改为每批一次多值 INSERT IGNORE，不产生逐条 N+1。
- Controller 的 Map 结果已收口为带中文 Schema 说明的 `InAppNotificationPage/Item`、未读计数、单条已读和全部已读类型对象，JSON 字段名保持现有前端合同。
- 新增 Service 与 Repository 测试，覆盖五类通知、目标路径、未读总数、整批均已读后继续扫描、五类批量已读和 Repository 可见性委托；与 Escalation/Dispatcher/Outbox 联合回归通过。新增 XML `xmllint` 通过且无 JOIN/EXISTS，Service/Repository/测试无 JDBC/SQL。
- 当前精确静态计数：生产 JDBC 15 文件、测试 JDBC 25 文件。下一步进入剩余 query/report/scheduler/admin/AI 模块，优先选择边界较小且可独立关闭的文件。

2026-09-02 第三十四次续接压缩：
- `ReportService` 活动链已迁移为 AiReport/AlertNotification/AlertOccurrence/AiTask 四个单表 Mapper + `ReportRepository`；七类报告列表、详情、生成、数字校验、权限范围、元数据快照、审计与硬删除保持。
- 报告列表不再一次加载整表：按 generated_at/id 稳定游标每 500 条扫描，在代码中应用 `ReportAccessPolicy`，只保留当前页但继续计算精确授权 total。报告删除先分批读取 report notification ID，再从 Occurrence 单表删除 DELIVERY_ATTEMPT，随后依次删除 Notification、AiTask 和 AiReport，事务顺序与原链一致。
- 生成继续调用完整 `SixReportFactsService.collect`，写入完整事实、摘要、Prompt/Skill/模型/Token/成本/新鲜度/范围快照并返回详情。`ReportService` 内原 45-64 行私有 `collect/q/count` 从无调用，且已由当前 six-report-v4 事实服务替代；为清除死代码中的直接 SQL 予以删除，不影响任何活动入口。
- 新增 Repository/Service 测试，覆盖 500 条无权记录后的分页继续、精确 total、删除依赖顺序、生成降级、事实/新鲜度元数据和删除审计；与 `ReportPdfServiceTest` 联合回归通过。新增 XML `xmllint` 通过，无 JOIN/EXISTS，Service/Repository/测试无 JDBC/SQL。
- 当前精确静态计数：生产 JDBC 14 文件、测试 JDBC 25 文件。下一步迁移 `ClusterReportService`，再按依赖顺序处理其余报告编排与事实服务。

2026-09-02 第三十五次续接压缩：
- `ClusterReportService` 已迁移为 Cluster/ClusterLink/ClusterRootCause/Source/Content/Identity/RCA/Knowledge/Incident 九个单表 Mapper + Repository；簇可见性、时间范围、技术数据范围、学习机逻辑渠道、首末时间、渠道计数、用户/设备指纹去重、典型现象、activeDays 均保留。
- RCA、知识与 Incident 先读取活动关联，再按 ID 批量加载并按原时间口径排序；反馈详情的 cluster/RCA/knowledge 聚合只建立一次可见性快照，不再重复读取簇详情。无 MAIN 或不可见来源继续返回空聚合/拒绝，不泄露其他范围簇。
- 独立复核修复典型现象候选：只能使用时间最新来源的正文或标题，最新正文为空时不能跳过它改选更旧记录。Identity 仍以 keyId+fingerprint 去重，用户未知保持 null，设备未知保持 0。
- 新增 3 项服务测试覆盖完整 RCA 字段、反馈聚合的一次可见性快照和空渠道范围失败关闭；与一期簇展示、ReportService、ReportPdf 联合回归通过。新增 XML `xmllint` 通过，无 JOIN/EXISTS，Service/Repository/测试无 JDBC/SQL。
- 当前精确静态计数：生产 JDBC 13 文件、测试 JDBC 25 文件。下一步继续 `ReportGenerationOrchestrator` 或 `ReportGenerationCoordinator`，再进入 `SixReportFactsService` 大批。

2026-09-02 第三十六次续接压缩：
- `ReportGenerationOrchestrator` 与同文件 `ReportTaskLifecycle` 已迁移为 AiTask/AiReport/AuthUser/AuthUserRole/AuthRolePermission/AlertNotification 单表 Mapper + `ReportTaskRepository`；任务创建、PENDING/RETRY_PENDING→RUNNING 租约、完成、取消竞态、失败退避、人工重试、列表、到期恢复和调度入口全部保留。
- 工作线程继续从活动用户、未过期角色和角色权限恢复原操作者 `WebPrincipal`；系统任务继续使用仅 ADMIN 的 system principal。安全上下文始终在 finally 恢复，不把上一任务主体泄漏给下一任务。
- 取消在报告生成完成后赢得竞态时，仍按 Notification→支撑 Idempotency/Schedule Task→AiReport→当前生成 Task CANCELLED 的原顺序清理；没有复用管理员硬删除路径，避免额外删除其他已关联 REPORT_GENERATION 任务。
- 新增生命周期/Orchestrator 测试，覆盖取消竞态、租约持有者、原操作者限制、工作线程权限恢复和成功完成；与 ReportSchedulers、VersionReleaseReportScheduler、ReportService/Repository 联合回归通过。新增 XML `xmllint` 通过，无 JOIN/EXISTS，Service/Repository/测试无 JDBC/SQL。
- 当前精确静态计数：生产 JDBC 12 文件、测试 JDBC 25 文件。下一步迁移 `ReportGenerationCoordinator` 的幂等与再生成链，再进入 `SixReportFactsService`。

2026-09-02 第三十七次续接压缩：
- `ReportGenerationCoordinator` 已迁移为 AiTask 与 AiReport 两个单表 Mapper + `ReportIdempotencyRepository`；基础键、兼容历史 input_snapshot、唯一 SHA-256 占位、并发 DuplicateKey 收敛、完成证据、再生成序号和 previous_report_id 谱系全部保留。
- 幂等查询使用 typed result wrapper 区分“没有占位行”和“占位存在但 reportId 仍为空”，继续以 `REPORT_GENERATION_IN_PROGRESS` 失败关闭；前序再生成记录同样区分缺行与存在但尚无结果，保持原事务锁语义。
- 归一化周期、six-report-v4、来源、包名/版本和当前数据范围继续参与 baseKey；用户主动再次生成仍分配下一个 regenerationNo，而不是错误复用旧报告。
- 新增 3 项协调器测试，覆盖兼容历史已完成结果、新报告完成与初始谱系、并发占位未完成门禁；与 Orchestrator、TaskLifecycle、ReportService、ReportSchedulers 联合回归通过。新增 XML `xmllint` 通过，无 JOIN/EXISTS，Service/Repository/测试无 JDBC/SQL。
- 当前精确静态计数：生产 JDBC 11 文件、测试 JDBC 25 文件。下一步进入 `SixReportFactsService` 大批；该服务含七类报告事实，必须逐分支拆成单表事实读取与代码聚合，不能删除或降级任一报告板块。

2026-09-02 第三十八次续接压缩：
- 在进入大型事实服务前，先关闭相邻 `ReportSchedulers` 批次：日报三类、周报、Incident RCA 扫描、MySQL 命名锁、system ADMIN 安全上下文、计划运行记录及 SUCCEEDED/SKIPPED_LOCK/FAILED 状态全部保留。
- 调度命名锁复用已验证的 `MaintenanceLockRepository`，`incidentRca` 与 ScheduledGenerationExecutor 继续在事务内获取/释放锁，保证 GET_LOCK/RELEASE_LOCK 使用线程绑定同连接。Incident 与 RCA 拆成单表分批查询；若最早 500 个 Incident 都已有 RCA，会继续扫描直到取得最多 20 个真正无 RCA 的最早候选。
- ReportScheduleRun 使用 MyBatis generated key 写入 AiTask，成功、锁跳过和安全错误摘要更新保持 REQUIRES_NEW 事务。原调度测试已移除 JDBC mock。
- 新增候选扫描与 Incident 调度测试，覆盖整批已有 RCA 后继续扫描、精确幂等键、system principal 和 finally 释放锁；与 VersionReleaseReportScheduler、Orchestrator、Coordinator 联合回归通过。XML `xmllint` 通过，无 JOIN/EXISTS，Service/Repository/测试无 JDBC/SQL。
- 当前精确静态计数：生产 JDBC 10 文件、测试 JDBC 24 文件。下一步正式拆解 `SixReportFactsService` 七类输出；不得恢复其已知多表 SQL，也不得删除任一 sections 键。

2026-09-02 第三十九次续接压缩：
- 当前目标：从只读事实源无损恢复完整系统到 `/Users/yang/feedback-platform`；用户再次明确，候选中已经保留或迁移的全部功能一起留下，不再裁剪告警、通知、RCA、知识库等模块。仅最终排除 build、缓存、虚拟环境、运行附件、APK、验收输出等生成物。
- `RcaOrchestrationService` 已迁移为单表 `RcaOrchestrationTaskMapper` + `RcaOrchestrationRepository`；五阶段 FACTS/CORRELATION/FIVE_WHYS/IMPACT/PERSIST、幂等主任务与批量步骤初始化、超时、SKIP LOCKED 租约、取消竞态、失败重试、指数退避、JSON 输出快照和 SHA-256 均保留。
- 原 status 内父任务子查询改为先读取 typed 主任务的 internalId，再单表读取步骤；所有读写仍仅访问 `t_ai_task`。测试已改为 Repository 合同，不再 mock JDBC。
- 作者自审与独立静态复核完成：主/测试代码编译成功，RcaOrchestration 与 ReportSchedulers 联合定向测试成功，XML 通过 `xmllint`，无 JOIN/EXISTS，Service/Repository/测试无 JDBC 或内嵌 SQL。证据仅限本地自动化，不代表 MySQL 集成或生产成功。
- 当前精确静态计数：生产 JDBC 9 文件、测试 JDBC 23 文件。下一步优先迁移 `SixReportFactsService` 七类报告事实；随后处理剩余 8 个生产 JDBC 文件；最后测试 JDBC 清零与全量门禁。

2026-09-02 第四十次续接压缩（SixReportFacts 批次进行中）：
- 当前目标与排除项不变：候选中所有功能一起保留；最终仅排除 build、缓存、虚拟环境、运行附件、APK 和验收输出等生成物。原仓库保持只读，最终目录尚未替换。
- 已新增 `SixReportFactsPersistence.kt`、14 个单表 ReportFact Mapper XML 与 `SixReportFactsRepository`；Source 按 id 每 500 条分页，Content/ClusterLink/Cluster/Identity/SDK/Jira/Redblack/FeedbackAI 只按当前页 ID 批量读取，ImportBatch/ImportRow、Config、AlertInstance/Rule 各自单表。
- `SixReportFactsService` 已移除 JDBC 并恢复 DAILY_INGEST、JIRA_IMPORT_RECONCILIATION、WEEKLY_TREND、VERSION_RELEASE、QUALITY_LINKAGE、REDBLACK_ANALYSIS、RISK_BRIEF 七类报告及全部既有 sections 键；输出键集合与只读事实源机械对照一致。日报/周报同比、版本全历史与七日基线、质量关联、红黑榜共振、Jira 时效和风险评分均改为代码聚合。
- 自审已修复模块权限大小写、历史失败批次全量加载和跨渠道严重度误把普通渠道计入的问题；失败导入只跨页保留累计值，严重风险只按实际 S0/S1 渠道计算。新增空事实七类 sections、周报、质量关联、版本基线和严重渠道回归测试。
- 当前验证：主/测试 Kotlin 编译成功；第一轮七类 sections 与报告生成/PDF/编排联合回归成功；新增严重渠道测试尚待本次续接运行。XML 已通过 xmllint，Service/Repository/测试静态无 JDBC/内嵌 SQL，Mapper 静态无 JOIN/EXISTS/UNION。MySQL Mapper 集成仍待核验。
- 下一步：1) 运行新增回归并完成 SixReportFacts 独立复核；2) 若通过，确认生产 JDBC 精确降至 8；3) 迁移其余生产 JDBC 文件并最终清零测试侧 JDBC。

2026-09-02 第四十一次续接压缩：
- `SixReportFactsService` 批次已关闭：七类报告及旧实现全部 sections 键完整保留，Service/Repository/测试无 JDBC、手写 SQL 或 `java.sql`，14 个 Mapper XML 均为单表且无 JOIN/EXISTS/UNION。
- 作者自审修复模块权限大小写、历史失败导入全量内存加载和严重渠道口径；独立复核又确认日报/周报历史渠道分组、Jira 整数小时 AVG、版本全历史严重数/七日基线、质量 link 乘法、红黑榜 burst >10、风险分数组合及 drilldown 键保持。旧/新输出键集合机械对照无差异。
- 新增合同测试覆盖七类空事实 sections、周报 Source+Content+MAIN 装配、质量跨渠道关联、版本七日基线、严重事实渠道与普通渠道隔离；新增 MyBatis XML 解析/单表合同测试。全部报告域测试、ReportSchedulers 联合回归及主/测试编译成功。
- `tools/dev-check.sh --quick` 无法直接用于该 detached 候选：脚本依赖 Git 根并固定检查 `/Users/yang/projects/feedback-platform`，候选不含同级 Git 布局；不能让它误检或修改原仓库。该限制留到最终候选进入受控 Git 布局后执行，当前证据仅为本地编译、单元和静态合同，不是 MySQL/生产集成。
- 当前精确静态计数：生产 JDBC 8 文件、测试 JDBC 23 文件。下一步：1) 迁移 `IngestionOperationsQueryService` 或其他独立剩余模块；2) 逐批清零其余生产 JDBC；3) 测试 JDBC 清零后执行全量后端/前端/静态门禁。
