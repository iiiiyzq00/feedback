# 代码文件功能地图

本文按模块逐一说明 `feedback-platform.backup-20260905` 中每个源文件负责的功能，是代码结构的“文件级索引”。统计口径：后端主代码 207 个 Kotlin 文件、167 个 Flyway 迁移、121 个后端测试文件；前端 93 个源文件（不含 `.DS_Store`）；SDK 33 个 Kotlin 文件；三个 Demo 24 个 Kotlin 文件。

## 1. 后端 backend

后端位于 `backend/src/main/kotlin/com/feedback`，包结构即分层结构：`controller`（API）→ `service`（业务）→ `repository`/`entity`（数据），辅以 `security`、`config`、`scheduler`、`audit` 等横切层。

### 1.1 启动入口

| 文件 | 功能 |
|---|---|
| `FeedbackApplication.kt` | Spring Boot 启动类（`main` 入口），`@SpringBootApplication` 装配整个后端。 |

### 1.2 公共基础 common

| 文件 | 功能 |
|---|---|
| `ApiResponse.kt` | 统一 API 响应包装（`code/message/data`），提供 `success/error/of` 工厂方法。 |
| `ErrorCode.kt` | 全局错误码枚举（认证、权限、参数、业务、幂等等），含 `retryable` 标记。 |
| `BaseEntity.kt` | JPA 实体基类，提供 `createdAt/updatedAt` 与 `preUpdate` 审计钩子。 |
| `SafeErrorDetail.kt` | 安全化异常信息工具：只暴露异常类名摘要，避免向客户端泄漏堆栈/敏感细节。 |

### 1.3 配置 config

| 文件 | 功能 |
|---|---|
| `AppConfig.kt` | 应用级 Bean 配置：UTC 时钟、Caffeine 本地缓存（导入批次）、Bean 等。 |
| `JacksonConfig.kt` | Jackson `ObjectMapper` 配置（JSON 序列化规范）。 |
| `RedisConfig.kt` | Redis 模板配置（通用与字符串两种 Template）。 |
| `ElasticsearchConfig.kt` | Elasticsearch 连接配置（可选能力，未配置时降级）。 |
| `WebSecurityConfig.kt` | Spring Security 过滤链：认证方式、放行路径、会话与 CORS 等。 |
| `SdkAuthProperties.kt` | SDK 认证配置属性（app secrets、签名、密钥等）及启动时合法性校验。 |
| `GlobalExceptionHandler.kt` | 全局异常处理器：参数校验、非法参数、缺参、403/401、业务异常的标准化响应。 |
| `ApiObservabilityFilter.kt` | API 可观测性过滤器：按状态分组记录请求指标，联动 SDK 遥测。 |
| `RuntimeConfigService.kt` | 运行时动态配置读取服务：从 `t_system_config` 读取 int/string/decimal/map 类型配置。 |
| `DynamicConfigGovernanceService.kt` | 动态配置治理：变更集、版本、历史、回滚与审计（配置即代码的运行时投影）。 |
| `AiProductionReadinessValidator.kt` | AI 生产就绪校验器：启动时检查 LLM 合规参数、密钥、模型配置等是否满足生产门槛。 |
| `MaskingProductionReadinessValidator.kt` | 脱敏生产就绪校验器：启动时检查脱敏版本、数据集证据等生产门槛。 |

### 1.4 安全 security

| 文件 | 功能 |
|---|---|
| `WebSessionStore.kt` | Redis 不透明会话：签发 256 位随机 ID，仅存 ID 摘要、用户 ID、会话版本和 TTL。 |
| `WebSessionAuthenticationFilter.kt` | 请求过滤器：从 HttpOnly Cookie 解析不透明会话 ID，经 Redis 与数据库双重校验后注入 `SecurityContext`。 |
| `WebSessionCookieService.kt` | Web 会话 Cookie 边界：签发/清除 HttpOnly、Secure、SameSite Cookie，隔离浏览器 JavaScript。 |
| `WebUserDetailsService.kt` | 用户详情服务：从数据库加载用户、角色、数据权限范围。 |
| `WebPrincipal.kt` | 认证主体模型：userId、用户名、角色、权限集合。 |
| `AdminBootstrap.kt` | 管理员引导：首次启动时按环境变量创建一次性管理员账号。 |
| `DataPermissionService.kt` | 数据权限服务：渠道/模块/地区维度范围判定、字段级允许/拒绝策略。 |
| `LoginProtectionService.kt` | 登录保护：Redis 计数 + 限流 + 锁定（防暴力破解）。 |
| `RequestAuditFilter.kt` | 请求审计过滤器：写操作按类目（业务/安全/查询/系统）写入审计链。 |
| `SecurityErrorWriter.kt` | 安全错误响应写入器：统一输出 401/403 JSON。 |

### 1.5 审计 audit

| 文件 | 功能 |
|---|---|
| `BusinessAuditService.kt` | 业务审计服务：审计事件入队/落库、字段校验（operation/auditClass/outcome）、链完整性校验（`verify`）。 |

### 1.6 控制器 controller（API 层）

控制器按领域分包，路径均以 `/api/v1` 开头：

#### auth

| 文件 | 功能 |
|---|---|
| `AuthController.kt` | 登录（`POST /api/v1/auth/login`）、当前用户（`/me`）、登出（`/logout`）。 |

#### admin（系统管理）

| 文件 | 功能 |
|---|---|
| `AdminManagementController.kt` | 用户 CRUD、角色权限矩阵、知识库 CRUD/审核/废弃、动态配置变更与回滚、审计日志查询（`/api/v1/admin`）。 |
| `AlertRuleController.kt` | 告警规则全生命周期：规则 CRUD/启停/排序/测试、发布与契约治理、供应商验收、幂等冲突处置、误归并修正、安全信号、版本发布事实、值班排班（`/api/v1/admin/alert-rules`）。 |
| `MaskingRuleAdminController.kt` | 脱敏规则启停、NER 白名单维护、脱敏版本评测/发布、评估数据集注册与审批（`/api/v1/admin/masking-rules`）。 |
| `MaskingSecurityController.kt` | 脱敏安全运维：隔离区查询、重试、关闭 SDK 异常、联系信息解密（工单审批）、原始数据检查（`/api/v1/masking-security`）。 |
| `AiAssetAdminController.kt` | AI 技能/Prompt 资产管理：创建技能、发布/回滚版本、提示词与统计（`/api/v1/admin/ai-assets`）。 |
| `ClusterAiEvaluationController.kt` | 归簇质量验收：提交评测、查看最新结果、导出（`/api/v1/admin/cluster-evaluation`）。 |
| `DeadLetterController.kt` | 通知投递死信与投递矩阵：死信重放/忽略、投递证据、通知总览（`/api/v1/admin/alert-delivery`）。 |
| `IndexConsistencyAdminController.kt` | 索引一致性管理：状态查看、outbox 重放、统计对账（`/api/v1/admin/index-consistency`）。 |

#### alert（告警与通知）

| 文件 | 功能 |
|---|---|
| `AlertController.kt` | 告警列表/统计/P3 摘要/详情/证据，确认、解决、转派、静默、生命周期流转，事件（incident）生命周期与实体闭环快照（`/api/v1/alerts`）。 |
| `InAppNotificationController.kt` | 站内通知：列表、未读数、已读、全部已读（`/api/v1/in-app-notifications`）。 |
| `ExternalSecurityEventController.kt` | 外部安全事件接入：HMAC 校验后接收安全事件（`/api/v1/provider/security-events`）。 |
| `NotificationProviderReceiptController.kt` | 通知供应商回执回调：HMAC 校验回执并落库（`/api/v1/provider/notification-receipts`）。 |

#### imports（表格导入）

| 文件 | 功能 |
|---|---|
| `ImportController.kt` | 导入全流程 API：模板列表/下载、整文件或分块上传、批次详情/映射预览/错误导出/对账导出、确认、重试、回滚、渠道数据质量（`/api/v1/import`）。 |

#### query（查询与分析）

| 文件 | 功能 |
|---|---|
| `FeedbackQueryController.kt` | 反馈查询：列表筛选、详情、时间线、批量状态、导出、附件授权（`/api/v1/feedback`）。 |
| `DashboardQueryController.kt` | 看板 API：总览、漏斗、SDK 速率、渠道趋势、问题分布、模块排行、热点问题、业务 KPI、告警标记、指标定义/对账、分析助手问答（`/api/v1/dashboard`）。 |
| `AiReviewController.kt` | AI 分类复核：待复核列表、单条/批量复核、分类法（`/api/v1/ai-reviews`）。 |
| `ClusterReviewController.kt` | 归簇审核：候选列表、确认/新建/驳回/合并/拆分/状态流转（`/api/v1/cluster-reviews`）。 |
| `IngestionOperationsController.kt` | 处理链操作台：正式接收、隐私放行、AI 结果、归簇决策明细（`/api/v1/ingestion-operations`）。 |
| `SearchController.kt` | 搜索：联想建议、相似反馈检索（`/api/v1/search`）。 |

#### report（报告与 RCA）

| 文件 | 功能 |
|---|---|
| `ReportController.kt` | AI 报告列表/详情/删除/生成/任务管理/PDF 导出，RCA 列表/详情/触发/审核/草稿/任务，问题簇详情与关联反馈/知识（`/api/v1/reports`）。 |

#### sdk（SDK 上报）

| 文件 | 功能 |
|---|---|
| `SdkFeedbackController.kt` | SDK 上报入口：提交反馈（HMAC + 信封加密）、查询进度、附件/分块上传、遥测上报、配置下发（`/api/v1/sdk`）。 |

### 1.7 服务层 service（核心业务）

#### ai（AI 分类与治理）

| 文件 | 功能 |
|---|---|
| `AiModels.kt` | AI 领域模型：`AiClassification`、`LlmNarration`、`LlmClient` 接口、`AiTransientException`。 |
| `AiClassificationTaxonomy.kt` | AI 分类法：类目/严重度定义与归一化、契约对外暴露。 |
| `AiGovernancePolicy.kt` | AI 治理策略：灰度桶、影子调用、回滚判定、第二审批人规则。 |
| `RuleClassifier.kt` | 规则分类器：离线规则优先分类（降级路径），返回分类与置信度。 |
| `SeverityPolicy.kt` | 严重度策略 v1 历史基线（保留可回滚）；运行时激活版本由 `SeverityPolicyService` 提供。 |
| `SeverityPolicyService.kt` | 严重度策略运行时服务：从 `t_system_config`（`SEVERITY_POLICY`）读取激活版本（当前 v2），无配置回退 v1；提供 rubric、等级定义与规则降级判定。 |
| `OpenAiCompatibleLlmClient.kt` | OpenAI 兼容 LLM 客户端：分类/叙述调用、JSON 解析、成本与指标记录。 |
| `AiTaskPipeline.kt` | AI 任务流水线：`AiTaskService` 入队、`AiTaskClaimService` 抢占领取、`AiTaskProcessor` 执行（LLM 或规则降级）。 |
| `AiReviewService.kt` | AI 复核服务：待复核分页、单条/批量复核、权限校验。 |
| `AiSafetyGuard.kt` | AI 安全护栏：输入问题与输出内容的敏感词/注入检测。 |
| `LlmQuotaGuard.kt` | LLM 配额保护：按动态配置限制调用量。 |
| `GovernedSkillExecutionService.kt` | 受治理技能执行：技能调用记录（版本、prompt、token、费用）、降级回退、确定性记录。 |
| `SkillPromptManagement.kt` | 提示词管理：技能/Prompt 的版本、渲染、DRAFT→TESTED→APPROVED→PUBLISHED 状态机。 |
| `PromptAssetBootstrap.kt` | Prompt 资产启动导入：以 manifest + Git 文件为权威源校准数据库。 |
| `PromptEvaluationService.kt` | Prompt 评测：用标注集/安全集用例计算准确率、S0/S1 召回、安全通过率；临时 LLM 异常自动退避重试，支持多次采样多数表决（`AI_EVALUATION_*` 配置）。 |
| `PromptAutoRollbackMonitor.kt` | Prompt 自动回滚监控：按失败率阈值触发版本回滚。 |
| `NarrationShadowEvaluator.kt` | 叙述影子评估：新旧 Prompt 并行对比，评估降级与质量。 |
| `KnowledgeSearchSkillService.kt` | 知识检索技能：基于问题类型/模块检索知识库（受治理执行）。 |

#### alert（告警与通知）

| 文件 | 功能 |
|---|---|
| `AlertEngine.kt` | 告警引擎：定时评估全部规则，计算条件组合、触发/恢复判定。 |
| `AlertRuleService.kt` | 告警规则 CRUD、启停、排序、模拟测试与测试运行记录。 |
| `AlertLifecycleService.kt` | 告警生命周期：静默、冷却、升级执行。 |
| `AlertIncidentLifecycleService.kt` | 事件（incident）生命周期：状态流转、时间线、审计事件写入。 |
| `AlertQueryService.kt` | 告警查询：列表、统计、P3 日报摘要、可指派人员。 |
| `AlertTrendBaselineService.kt` | 告警趋势基线：基于 28 天中位数/MAD 的异常检测。 |
| `AlertRouteSkillService.kt` | 告警路由技能：LLM 生成处置建议（含演练场景）。 |
| `AlertRuleIsolatedTestService.kt` | 告警规则隔离测试：历史回放、影子运行、演练（drill）与处置。 |
| `AlertRuleAcceptanceService.kt` | 告警验收：规则接受度指标（TP/FP/FN/TN）与审批。 |
| `AlertProductionGovernanceService.kt` | 告警生产治理：契约、供应商验收、激活缺口、发布审批。 |
| `AlertQualityFactService.kt` | 告警质量事实：脱敏 F1、误归并事件登记与修复。 |
| `AlertReviewGovernanceMetricService.kt` | 告警治理指标：SLA 违约、复核决策数、人工推翻率。 |
| `AlertLoopHealthService.kt` | 告警回路健康：环路健康状态聚合。 |
| `NotificationPolicy.kt` | 通知策略：按严重度的必达/允许渠道、模式与版本。 |
| `NotificationDispatcher.kt` | 通知分发：渠道分发、每日 P3 摘要、批量投递队列。 |
| `NotificationEscalationService.kt` | 通知升级链：超时升级到上级、重试基线。 |
| `NotificationMetrics.kt` | 通知指标：投递耗时/结果、渠道计数（Prometheus + 表统计）。 |
| `NotificationOperationsService.kt` | 通知运营：投递矩阵、列表、总览。 |
| `NotificationProviderReceiptService.kt` | 供应商回执：HMAC 校验、回执存储与查询。 |
| `InAppNotificationService.kt` | 站内通知：列表、未读数、已读。 |
| `EmailSender.kt` | 邮件发送：SMTP 实现与 Webhook 负载构建、有效性校验。 |
| `DeadLetterService.kt` | 通知死信：列表、详情、重放、忽略、投递证据。 |
| `OncallScheduleService.kt` | 值班排班：团队、成员、日历与升级人。 |
| `VersionReleaseFactService.kt` | 版本发布事实：包/版本/基线版本管理，多渠道回归候选计算。 |
| `SecuritySignalService.kt` | 安全信号：敏感残留阻断、外部安全事件登记与结论。 |
| `ExternalSecurityEventService.kt` | 外部安全事件处理：HMAC 校验、事件入库、告警联动。 |
| `IdempotencyConflictService.kt` | 幂等冲突：记录冲突事实、列表与解决/忽略。 |
| `EntityClosureService.kt` | 实体闭环：事件处置后的关联反馈关闭检查与快照。 |
| `AlertNotification.kt` | 通知领域模型：事件、知识匹配、条件明细、投递结果。 |

#### attachment（附件）

| 文件 | 功能 |
|---|---|
| `AttachmentSecureStreamService.kt` | 附件安全流：按授权理由开放资源流，未配置存储时禁用（`DisabledAttachmentContentProvider`）。 |

#### cluster（问题归簇）

| 文件 | 功能 |
|---|---|
| `ClusteringEngine.kt` | 归簇引擎：按产品/模块/类目/症状签名匹配候选簇，生成归簇决策并落库。 |
| `ClusterDecisionModel.kt` | 归簇决策模型：问题画像、候选证据、决策策略与计划、产品范围解析。 |
| `ClusterLifecycleService.kt` | 簇生命周期：状态流转、合并、拆分。 |
| `ClusterReviewGovernance.kt` | 簇审核治理：优先级、截止时间、审核策略契约。 |
| `ClusterStatisticsService.kt` | 簇统计：成员数/渠道数重算、缓存与实际漂移检测。 |
| `ClusterAiEvaluationService.kt` | 簇 AI 质量验收：标注集 F1、决策版本评测。 |

#### imports（表格导入）

| 文件 | 功能 |
|---|---|
| `ImportFileParser.kt` | 文件解析：xlsx/csv 解析为结构化行。 |
| `ImportBatchService.kt` | 导入批次：上传登记、去重（内容 SHA-256）、映射预览、确认入库（按渠道适配器持久化）。 |
| `ImportBatchWorker.kt` | 导入批次 Worker：公平领取批次、执行导入、失败标记。 |
| `ImportChunkUploadService.kt` | 分块上传：分块暂存、合并状态、渠道归属。 |
| `ImportUploadStagingWorker.kt` | 上传暂存 Worker：扫描并完成待合并分块。 |
| `ImportTemplateService.kt` | 导入模板：渠道模板管理、模板下载、渠道边界校验。 |
| `ImportRawCrypto.kt` | 原始文件加密：导入文件落盘加密（AES-GCM）。 |
| `ImportAttachmentTaskService.kt` | 导入附件任务：URL 引用校验、哈希登记。 |
| `ImportAttachmentUploadService.kt` | 导入附件上传：声明哈希校验、安全状态。 |
| `ImportQualityService.kt` | 导入质量：质量评分与批次质量快照。 |
| `ImportReconciliationExportService.kt` | 对账导出：批次行级对账结果导出 Excel。 |
| `UnifiedDataQualityService.kt` | 统一数据质量：渠道级质量快照、阈值策略、定时刷新。 |

#### masking（脱敏与隐私）

| 文件 | 功能 |
|---|---|
| `MaskingPipeline.kt` | 脱敏流水线：指纹/正则/上下文姓名/座机等规则串联执行，输出 `MaskingResult`。 |
| `MaskingRuleRegistry.kt` | 脱敏规则注册表：规则目录、启停配置、当前版本读取。 |
| `IdentityFingerprintService.kt` | 身份指纹：归一化 + 加盐 SHA-256 指纹，用于跨记录身份识别。 |
| `HanlpNerMasker.kt` | HanLP NER 脱敏器：人名/地名等实体识别与替换（无模型时 Noop 降级）。 |
| `NerAllowlistRegistry.kt` | NER 白名单：产品/功能/领域术语放行，带敏感词与正则校验。 |
| `ContactStorageCrypto.kt` | 联系信息加密：目的密钥派生 + AES-GCM 加解密。 |
| `EnvelopeCrypto.kt` | 信封加密：数据密钥的加密/解密、目的密钥派生。 |
| `OutboundPrivacyGate.kt` | 外发隐私门禁：对外输出前检查脱敏版本与残余敏感信息，未通过直接拒绝。 |
| `MaskingSecurityService.kt` | 脱敏安全运维：隔离区、重试、解密审批、原始数据检查授权。 |
| `MaskingVersionService.kt` | 脱敏版本：候选规则集、评测、发布与历史。 |
| `MaskingEvaluationEvidenceService.kt` | 脱敏评估证据：数据集注册/审批、评估运行、F1/召回等证据链。 |
| `SdkSecureProcessingService.kt` | SDK 安全处理：信封解密、联系信息解包、脱敏入库。 |
| `SdkSecureProcessingScheduler.kt` | SDK 安全处理定时任务：轮询待处理 SDK 数据。 |
| `IngestFailureAlertPublisher.kt` | 接入失败告警：SDK 处理失败时发布告警事件。 |

#### normalization（字段归一化）

| 文件 | 功能 |
|---|---|
| `EnumMappingRegistry.kt` | 枚举映射注册表：渠道字段映射版本、别名归一化。 |
| `FieldConversionHistoryService.kt` | 字段转换历史：记录每行字段转换过程，支撑对账与追溯。 |

#### query（查询与分析）

| 文件 | 功能 |
|---|---|
| `FeedbackQueryService.kt` | 反馈查询：列表/详情/时间线/联想建议/导出/批量状态/附件授权。 |
| `DashboardQueryService.kt` | 看板查询：总览、漏斗、SDK 速率、渠道趋势、问题分布、热点、KPI、告警标记（带缓存）。 |
| `AnalysisAssistantService.kt` | 分析助手：意图解析 + LLM 叙述生成 + 安全门禁。 |
| `ClusterReviewService.kt` | 簇审核查询与命令：候选、确认、驳回、合并、拆分、状态流转。 |
| `IngestionOperationsQueryService.kt` | 处理链操作台查询：接收/隐私/AI/归簇各阶段明细。 |
| `MetricGovernanceQueryService.kt` | 指标治理：指标定义目录、口径对账校验。 |
| `SimilarFeedbackService.kt` | 相似反馈检索：混合检索（向量 + 关键词）。 |
| `ProcessingChainTimeRange.kt` | 处理链时间范围工具：range 参数转起止时间。 |

#### report（报告与 RCA）

| 文件 | 功能 |
|---|---|
| `ReportService.kt` | AI 报告主服务：列表、详情、删除、生成（含事实快照与权限过滤）。 |
| `ReportGenerationOrchestrator.kt` | 报告生成编排：任务创建、重试、取消、查询。 |
| `ReportGenerationCoordinator.kt` | 报告生成协调：幂等键、再生成版本递增。 |
| `SixReportFactsService.kt` | 六类报表事实：按报告类型聚合原始数、唯一问题数、指标字典。 |
| `ReportNarrationService.kt` | 报告叙述：LLM 生成叙述（含熔断降级）。 |
| `ReportNumberValidator.kt` | 报告数字校验：LLM 输出中的数字与事实快照逐项核对。 |
| `ReportAiCircuitBreaker.kt` | 报告 AI 熔断器：失败率触发 OPEN，恢复后 CLOSED（状态存于动态配置）。 |
| `ReportAccessPolicy.kt` | 报告访问策略：生成权限、数据范围包含关系判定。 |
| `ReportGovernanceService.kt` | 报告治理：时效性 SLA 检查、审计写入。 |
| `ReportPdfService.kt` | PDF 导出：CJK 字体校验、报告渲染为 PDF。 |
| `RcaService.kt` | RCA 服务：触发、深度/快速 RCA 生成、审核、草稿、知识入库。 |
| `RcaOrchestrationService.kt` | RCA 编排：任务队列、领取、轮询执行、重试、取消。 |
| `ClusterReportService.kt` | 簇报告查询：簇详情、关联反馈、RCA、知识、事件。 |

#### sdk（SDK 服务端）

| 文件 | 功能 |
|---|---|
| `SignatureVerifier.kt` | 签名校验：HMAC-SHA256 请求签名与时间戳防重放。 |
| `NonceValidator.kt` | Nonce 校验：Redis 去重防止重复上报。 |
| `RateLimiter.kt` | 限流：按 appId/IP 的速率策略。 |
| `SdkIngestService.kt` | SDK 接入主服务：反馈入库、幂等回执、进度查询、附件关联。 |
| `SdkConfigService.kt` | SDK 配置下发：按包名返回运行时配置。 |
| `SdkRequestTelemetryRecorder.kt` | SDK 请求遥测：状态分类、耗时记录、过期清理。 |
| `SdkIngestHealthContract.kt` | SDK 接入健康契约：健康指标描述。 |
| `TransportKeyService.kt` | 传输密钥：加载私钥、解包数据密钥（信封加密）。 |

#### sync（派生索引同步）

| 文件 | 功能 |
|---|---|
| `DerivedIndexSync.kt` | 派生索引同步：搜索文档模型、`SearchIndexGateway` 接口（ES/Milvus 实现）、upsert/delete/search。 |
| `HybridCandidateRetriever.kt` | 混合检索：向量 + 关键词候选融合打分。 |
| `BgeM3OnnxEmbeddingProvider.kt` | BGE-M3 ONNX 向量生成（本地推理）。 |
| `IndexConsistencyAdminService.kt` | 索引一致性：状态、outbox 重放、统计对账。 |

#### knowledge（知识库）

| 文件 | 功能 |
|---|---|
| `KnowledgeClosureService.kt` | 知识闭环：知识使用统计（展示/采纳/有效/无效）、效果分重算、过期与痕迹。 |

### 1.8 实体 entity

| 文件 | 表名 | 功能 |
|---|---|---|
| `FeedbackSource.kt` | `t_feedback_source` | 统一反馈源（四渠道归一后的核心主表，含业务键/事件时间/导入批次）。 |
| `FeedbackContent.kt` | `t_feedback_content` | 反馈内容（脱敏后文本、脱敏/处理状态）。 |
| `FeedbackIdentity.kt` | `t_feedback_identity` | 身份指纹（按来源记录各类身份标识）。 |
| `FeedbackAttachment.kt` | `t_feedback_attachment` | 反馈附件（客户端附件 ID、存储引用）。 |
| `FeedbackAi.kt` | `t_feedback_ai` | AI 处理结果（分类、严重度、索引状态）。 |
| `ChannelJira.kt` | `t_channel_jira` | Jira 渠道反馈明细。 |
| `ChannelQuality.kt` | `t_channel_quality` | 质量平台渠道反馈明细。 |
| `ChannelRedblack.kt` | `t_channel_redblack` | 红黑榜渠道反馈明细。 |
| `ChannelSdk.kt` | `t_channel_sdk` | SDK 渠道反馈明细。 |
| `SdkIngest.kt` | `t_sdk_ingest` | SDK 上报原始信封（加密负载、处理状态）。 |
| `Cluster.kt` | `t_cluster` | 问题簇（产品/模块/症状签名、状态、统计）。 |
| `ClusterLink.kt` | `t_cluster_link` | 反馈与簇的关联（MAIN/RELATED、有效性）。 |
| `AiTask.kt` | `t_ai_task` | AI/报告/RCA 异步任务（类型、状态、重试）。 |
| `ImportBatch.kt` | `t_import_batch` | 导入批次（渠道、SHA-256、状态）。 |
| `ImportRow.kt` | `t_import_row` | 导入行（原始行、业务键哈希、行状态）。 |
| `ImportTemplate.kt` | `t_import_template` | 导入模板（渠道、列映射）。 |
| `ImportAttachmentTask.kt` | `t_import_attachment_task` | 导入附件任务（URL 哈希、下载状态）。 |
| `MaskingAudit.kt` | `t_masking_audit` | 脱敏审计记录。 |
| `SystemAuditLog.kt` | `t_system_audit_log` | 系统审计日志（统一审计链）。 |
| `SystemConfig.kt` | `t_system_config` | 动态配置（版本化、可回滚）。 |

### 1.9 仓储 repository

| 文件 | 功能 |
|---|---|
| `FeedbackSourceRepository.kt` | 反馈源仓储：业务键幂等查询、渠道计数、批次关联、事件时间范围查询。 |
| `FeedbackContentRepository.kt` | 内容仓储：按脱敏/处理状态查找待处理内容。 |
| `FeedbackIdentityRepository.kt` | 身份指纹仓储：按来源/类型/范围查询。 |
| `FeedbackAttachmentRepository.kt` | 附件仓储：客户端幂等查找、关联来源。 |
| `ChannelImportRepositories.kt` | 三个渠道明细仓储：`ChannelJiraRepository`、`ChannelQualityRepository`、`ChannelRedblackRepository`。 |
| `ChannelSdkRepository.kt` | SDK 渠道明细仓储。 |
| `SdkIngestRepository.kt` | SDK 上报仓储：appKey+clientId 幂等、receipt 查询、待处理信封。 |
| `Day4Repositories.kt` | AI/簇相关仓储：`FeedbackAiRepository`、`AiTaskRepository`、`ClusterRepository`、`ClusterLinkRepository` 的索引同步与候选查询。 |
| `ImportBatchRepository.kt` | 导入批次仓储：批次号/内容哈希/文件名去重、分页查询。 |
| `ImportRowRepository.kt` | 导入行仓储：按批次、类型、状态查询，重置可重试行。 |
| `ImportTemplateRepository.kt` | 模板仓储：激活模板、渠道最新模板。 |
| `ImportAttachmentTaskRepository.kt` | 附件任务仓储：URL 哈希幂等。 |
| `MaskingAuditRepository.kt` | 脱敏审计仓储。 |
| `SystemAuditLogRepository.kt` | 系统审计日志仓储。 |
| `SystemConfigRepository.kt` | 动态配置仓储：按 key 取最新激活版本。 |

### 1.10 定时任务 scheduler

| 文件 | 功能 |
|---|---|
| `MaintenanceSchedulers.kt` | 维护定时任务：数据时效、孤儿数据、租约恢复、密文清理、簇归档（带分布式锁）。 |
| `MaintenanceServices.kt` | 维护服务实现：`DataFreshnessChecker` 时效检查、`OrphanDataScanner` 孤儿扫描、`StaleLeaseRecovery` 租约恢复、审计记录器。 |
| `ReportSchedulers.kt` | 报告定时任务：日报/周报/事件 RCA 触发（系统身份执行、锁保护）。 |
| `VersionReleaseReportScheduler.kt` | 版本发布报告调度：扫描到期版本（发布 +3 天）自动生成报告。 |

### 1.11 DTO、mock 与工具

| 文件 | 功能 |
|---|---|
| `dto/sdk/SdkFeedbackRequest.kt` | SDK 上报请求模型：反馈、加密负载、加密联系信息、附件清单。 |
| `dto/sdk/SdkResponses.kt` | SDK 响应模型：回执、进度、附件回执、遥测请求。 |
| `mock/MockDataCommand.kt` | Mock 数据命令行工具：按任务生成各渠道模拟数据。 |
| `tools/FlywayRepairMain.kt` | Flyway 修复工具入口（独立 main）。 |

### 1.12 Flyway 迁移（backend/src/main/resources/db/migration）

171 个迁移文件按版本号连续演进，文件名即迁移主题。按阶段归纳：

| 版本段 | 主题 |
|---|---|
| V1~V10 | 环境基线、基础表、生成列/约束/索引、认证 RBAC、导入持久化 Worker、渠道与脱敏上下文。 |
| V11~V20 | 报表与 RCA 流程、AI 助手、报告治理、通知可观测性、会话吊销、Prompt 生命周期、索引、通用反馈触发类型、AI 生产治理。 |
| V21~V30 | 业务告警规则治理、脱敏安全闭环、接入失败告警、导入模板契约、附件分块、归一化质量指标、簇决策/复核/生命周期。 |
| V31~V40 | 簇统计一致性、outbox 快照、向量模型版本、AI 评估门禁、SDK 模拟渠道、六报表治理、报表维度、RCA 治理。 |
| V41~V52 | Day7 治理基线：用户角色权限、告警路由值班、八类通知渠道、事件生命周期、知识闭环、NER 版本、动态配置、审计链。 |
| V53~V62 | Day8：报告调度幂等/权限/AI 任务生命周期/六报表业务明细/时效 SLA、附件安全流授权、RCA 编排、告警契约验收。 |
| V63~V79 | Day10：性能索引、HTTP 审计队列、渠道全角列、红黑榜原文、SDK 速率策略/预算/健康指标/遥测告警/传输治理、模板映射。 |
| V80~V92 | SDK 上下文结构、AI 显示与指标澄清、簇复核优先级、报告降级原因、告警方案生命周期、隔离测试、退役泄漏夹具、影子版本。 |
| V93~V100 | 幂等冲突事实、指纹趋势影子规则、剩余需求影子规则、小时级 MAD 基线、版本发布事实、复核治理告警事实、脱敏/安全影子规则。 |
| V101~V110 | 外部安全事件接入、告警验收证据、退役 D6L 夹具、Day7 动态配置基线、通知升级链、AI 复核契约、隐私指标澄清、四治理技能、P1 通知策略。 |
| V111~V120 | 站内通知回执、AI 置信阈值、告警通知幂等、升级失败原因、验证与业务指标分离、分类法版本、SDK IP 限流、知识过期与痕迹。 |
| V121~V140 | 四十表整合：导入/质量/转换历史、影子写触发器、指标目录、报告任务、RCA 任务、告警事实、通知投递、演练、告警治理、值班、数据时效、安全事件、SDK 遥测、模板目录、附件任务。 |
| V141~V167 | 知识使用审计、附件授权、接触访问审计、脱敏 SLA/NER、评估证据、紧凑读模型、审计事件、报告熔断、升级链解耦、复核决策事实、审计目录、紧凑基线身份、退役影子表、告警回路权限、渠道契约、数据质量快照、AI/风险契约、字段级权限、AI 分类事实采纳。 |
| V168~V171 | 严重度策略 v2 运行时配置、用户反馈 P0 规则治理、安全左移治理、生产上线前告警自监控纳管与遗留规则清理。 |

### 1.13 后端测试

- `backend/src/test/kotlin/com/feedback/**`：121 个单元/契约测试，按主代码同包结构组织（`service/ai`、`service/alert`、`service/masking`、`service/report`、`security`、`controller` 等），每个文件对应被测类的行为契约。
- `backend/src/integrationTest/kotlin/com/feedback/integration/`：11 个端到端流程测试（AI 门禁、告警、归簇、导入、脱敏、权限、对账恢复、报告生成、SDK 接入、950 条规模验收），`testinfra/` 提供测试基础设施（环境、认证、夹具、契约工具）。
- `backend/src/realIndexTest/kotlin/com/feedback/integration/RealDerivedIndexConvergenceTest.kt`：真实 ES/Milvus 索引收敛测试。
- `backend/src/main/resources/db/migration` 之外，`backend/src/test` 中 `integration/` 下的 `MilvusLiteJavaSdkIntegrationTest.kt` 等属于轻量索引测试。

## 2. 前端 frontend

Vue 3 + TypeScript 管理端，源码位于 `frontend/src`。技术要点：Vite 构建、Element Plus 组件库、ECharts 图表、Pinia 状态、TanStack Vue Query 请求缓存、vue-router 权限路由。

### 2.1 入口与工程配置

| 文件 | 功能 |
|---|---|
| `main.ts` | 应用入口：创建 Vue 实例、注册 Pinia/Router/Element Plus/全局样式。 |
| `App.vue` | 根组件：路由出口与全局布局挂载。 |
| `env.d.ts` | TypeScript 环境声明（Vite 客户端类型、`.vue` 模块）。 |
| `index.html` | Vite HTML 入口模板。 |
| `vite.config.ts` | Vite 配置：`/api` 代理（`VITE_API_PROXY_TARGET`）、构建输出。 |
| `tsconfig.json` / `tsconfig.node.json` | TypeScript 编译配置。 |
| `package.json` | 依赖与脚本（dev/build/test/lint）。 |
| `.eslintrc.cjs` / `.eslintignore` | ESLint 配置。 |

### 2.2 API 封装 api

| 文件 | 功能 |
|---|---|
| `request.ts` | Axios 实例与 `get/post/put` 封装：Cookie 会话、XSRF 请求头、401 跳转、统一错误处理。 |
| `auth.ts` | 登录、登出、当前用户信息。 |
| `admin.ts` | 系统管理接口：用户、角色权限、知识库、动态配置、审计日志。 |
| `alert.ts` | 告警中心接口：告警列表/详情/统计/操作。 |
| `aiReview.ts` | AI 复核接口：待复核列表、分类法、提交复核。 |
| `cluster.ts` | 归簇审核接口：候选、确认、合并、拆分、状态流转。 |
| `dashboard.ts` | 看板接口：总览、漏斗、渠道趋势、问题分布、热点、分析助手问答。 |
| `feedback.ts` | 反馈查询接口：列表、详情、时间线、导出、批量状态。 |
| `import.ts` | 导入接口：模板、上传、分块、批次详情、确认、重试、回滚、质量。 |
| `ingestionOperations.ts` | 处理链操作台接口：接收/隐私/AI/归簇明细。 |
| `notification.ts` | 站内通知接口：列表、未读、已读。 |
| `report.ts` | 报告与 RCA 接口：报告列表/详情/生成/PDF、RCA 审核。 |
| `search.ts` | 搜索接口：联想建议、相似反馈。 |

### 2.3 路由 router

| 文件 | 功能 |
|---|---|
| `index.ts` | 路由表：登录页 + 五个功能模块（接入监控、渠道数据、公共看板、告警报告、系统管理）的懒加载路由与页面标题。 |
| `guards.ts` | 路由守卫：认证检查、角色/权限校验、页面标题设置。 |

### 2.4 状态 stores

| 文件 | 功能 |
|---|---|
| `auth.ts` | 认证状态：仅保存内存用户信息，负责 Cookie 会话登录/登出和权限判断。 |
| `app.ts` | 应用状态：页面加载中、侧边栏折叠等全局 UI 状态。 |
| `feedback.ts` | 反馈域状态：渠道健康、待办任务等跨页面共享数据。 |
| `preferences.ts` | 用户偏好：表格密度等本地偏好。 |

### 2.5 请求缓存 query

| 文件 | 功能 |
|---|---|
| `client.ts` | TanStack Vue Query 客户端与查询键（queryKeys）定义。 |

### 2.6 工具 utils

| 文件 | 功能 |
|---|---|
| `datetime.ts` | 时间格式化：秒级截断、本地时间字符串。 |
| `privacyStatus.ts` | 隐私状态展示文案映射。 |
| `notificationGroups.ts` | 站内通知分组与孤儿快速 RCA 判定。 |
| `performance.ts` | 前端性能指标：页面耗时、Web Vitals 上报。 |
| `region.ts` | 地区编码转名称。 |

### 2.7 通用组件 components

| 文件 | 功能 |
|---|---|
| `layout/AppLayout.vue` | 应用布局骨架：侧边栏 + 导航栏 + 内容区。 |
| `layout/Sidebar.vue` | 侧边菜单（按权限渲染）。 |
| `layout/Navbar.vue` | 顶部导航：全局搜索、通知入口、用户菜单。 |
| `layout/GlobalSearch.vue` | 全局搜索框（联想建议）。 |
| `common/DataTable.vue` | 通用数据表格封装。 |
| `common/FilterPanel.vue` | 通用筛选面板。 |
| `common/MetricCard.vue` | 指标卡片（标题、数值、说明、点击）。 |
| `common/PageHeader.vue` | 页面标题头（标题/描述/操作区）。 |
| `common/PageSkeleton.vue` | 页面加载骨架屏。 |
| `common/StateResult.vue` | 空态/加载/错误状态组件。 |
| `common/StatusBadge.vue` | 状态徽章。 |
| `common/ChannelCard.vue` | 渠道卡片。 |
| `common/LearningMachineIcon.vue` | 学习机品牌图标。 |
| `charts/BarChart.vue` | ECharts 柱状图封装。 |
| `charts/PieChart.vue` | ECharts 饼图封装。 |
| `charts/TrendChart.vue` | ECharts 趋势折线图封装。 |
| `charts/HeatmapChart.vue` | ECharts 热力图封装。 |
| `feedback/FeedbackDetail.vue` | 反馈详情展示（内容、渠道、处理链）。 |

### 2.8 页面 views（按功能模块）

#### 登录

| 文件 | 功能 |
|---|---|
| `LoginPage.vue` | 登录页。 |

#### 接入监控 monitor

| 文件 | 功能 |
|---|---|
| `OverviewPage.vue` | 接入概览（总览指标、渠道健康）。 |
| `ImportManagePage.vue` | 表格导入管理（上传、批次列表）。 |
| `ClusterAuditPage.vue` | 问题归簇审核。 |
| `AiReviewPage.vue` | AI 分类复核。 |
| `IngestionOperationsPage.vue` | 处理链操作台（按 `operationKind` 切换：接收/隐私/SDK/AI/归簇等视图）。 |
| `monitor/components/ImportBatchList.vue` | 导入批次列表组件。 |
| `monitor/components/ImportBatchDetail.vue` | 批次详情（行级映射、错误、确认/回滚）。 |
| `monitor/components/ImportUploadDialog.vue` | 上传对话框（整文件/分块）。 |
| `monitor/components/ClusterReviewList.vue` | 簇审核列表组件。 |
| `monitor/components/ClusterReviewDetail.vue` | 簇审核详情（证据、决策）。 |

#### 渠道数据 channel

| 文件 | 功能 |
|---|---|
| `AllFeedbackPage.vue` | 全部渠道反馈列表。 |
| `SdkFeedbackPage.vue` | SDK 渠道反馈。 |
| `JiraFeedbackPage.vue` | Jira 渠道反馈。 |
| `QualityFeedbackPage.vue` | 质量平台渠道反馈。 |
| `RedblackFeedbackPage.vue` | 红黑榜渠道反馈。 |
| `FeedbackDetailPage.vue` | 反馈详情页（统一详情 + 时间线）。 |
| `ChannelSourceIcon.vue` | 渠道来源图标。 |
| `channel/components/ChannelFeedbackList.vue` | 渠道反馈列表通用组件。 |

#### 公共看板 dashboard

| 文件 | 功能 |
|---|---|
| `DashboardOverview.vue` | 总览看板（KPI、趋势、分布）。 |
| `ProblemAnalysis.vue` | 问题深度分析（区域复现、模块排行、热点）。 |
| `dashboard/components/StatisticCard.vue` | 统计卡片组件。 |

#### 告警与报告 alert / notification / report

| 文件 | 功能 |
|---|---|
| `AlertCenter.vue` | 告警中心（列表、详情、确认/解决/转派）。 |
| `NotificationCenter.vue` | 站内通知中心。 |
| `ReportList.vue` | AI 报告列表（生成、下载 PDF）。 |
| `RcaAudit.vue` | RCA 审核。 |
| `ClusterDetail.vue` | 问题簇详情（成员反馈、RCA、知识、事件）。 |

#### 系统管理 admin

| 文件 | 功能 |
|---|---|
| `UserPermissionPage.vue` | 用户与权限（角色、数据范围）。 |
| `AlertConfigPage.vue` | 告警规则配置（规则、测试、值班、版本发布）。 |
| `KnowledgeBasePage.vue` | 知识库管理（CRUD、审核、废弃）。 |
| `MaskingRulePage.vue` | 脱敏策略管理（规则、白名单、版本评测）。 |
| `SystemConfigPage.vue` | 动态配置（变更、历史、回滚）。 |
| `ClusterEvaluationPage.vue` | 归簇质量验收。 |
| `AuditLogPage.vue` | 操作审计日志。 |

### 2.9 前端测试

`views/**`、`components/**`、`stores/**`、`query/**`、`utils/**` 下的 `*.spec.ts`（含 `*.test.ts`）为 Vitest 单测，覆盖页面关键交互、状态管理、工具函数与请求缓存，例如 `ClusterEvaluationPage.spec.ts`、`ClusterReviewDetail.spec.ts`、`ImportBatchDetail.spec.ts`、`auth.spec.ts`、`router/guards.spec.ts`、`utils/datetime.test.ts` 等。

## 3. Android SDK sdk

源码位于 `sdk/src/main/kotlin/com/feedback/sdk`，按能力分包。

### 3.1 入口与配置

| 文件 | 功能 |
|---|---|
| `FeedbackSDK.kt` | SDK 门面：`init` 初始化（配置/数据库/Worker）、设置用户、监听器、打开反馈入口。 |
| `config/SDKConfig.kt` | SDK 配置数据类：服务地址、密钥、隐私规则、超时等，含内网 IP 校验。 |

### 3.2 采集 capture

| 文件 | 功能 |
|---|---|
| `capture/AttachmentInput.kt` | 附件输入模型（类型、来源、尺寸限制）。 |
| `capture/ScreenCapture.kt` | 屏幕截图：Activity/View 层级捕获、压缩与缩略。 |
| `capture/ScreenRecordingCoordinator.kt` | 录屏协调：权限请求、录制状态、结果回调。 |
| `capture/ScreenRecordingPermissionActivity.kt` | 录屏权限申请 Activity。 |
| `capture/ScreenRecordingService.kt` | 录屏前台服务：MediaProjection 录制、失败处理。 |
| `capture/SdkLogBuffer.kt` | SDK 日志环形缓冲：按级别/保留时间记录，可导出快照。 |

### 3.3 加密 crypto

| 文件 | 功能 |
|---|---|
| `crypto/EncryptionEnvelope.kt` | 信封加密：数据密钥生成、Nonce、目的密钥派生、AES-GCM 加解密。 |
| `crypto/RsaKeyWrapper.kt` | RSA 公钥包装：加载服务端公钥、包装数据密钥。 |

### 3.4 本地数据库 db

| 文件 | 功能 |
|---|---|
| `db/FeedbackDatabase.kt` | Room 数据库定义与单例。 |
| `db/FeedbackStateMachine.kt` | 反馈状态机：PENDING→UPLOADING→…→DONE/FAILED 的合法迁移。 |
| `db/dao/FeedbackDao.kt` | 反馈记录 DAO（待上传数、按状态查询）。 |
| `db/dao/AttachmentDao.kt` | 附件记录 DAO。 |
| `db/entity/PendingFeedback.kt` | 待上传反馈实体。 |
| `db/entity/AttachmentRecord.kt` | 附件实体。 |

### 3.5 网络 network

| 文件 | 功能 |
|---|---|
| `network/ApiClient.kt` | Retrofit 客户端初始化与单例。 |
| `network/FeedbackApi.kt` | 后端 API 接口定义（提交反馈、进度、附件、遥测、配置）。 |
| `network/HmacInterceptor.kt` | HMAC 签名拦截器：请求签名、时间戳、AppKey 头。 |
| `network/models/Models.kt` | 网络模型：请求/响应/回执/加密负载。 |

### 3.6 隐私 privacy

| 文件 | 功能 |
|---|---|
| `privacy/ClientPrivacyFilter.kt` | 客户端隐私过滤：上报前按规则替换手机号/身份证等敏感信息。 |

### 3.7 遥测 telemetry

| 文件 | 功能 |
|---|---|
| `telemetry/EnvironmentCollector.kt` | 设备环境采集：型号、网络、电量、存储。 |
| `telemetry/TelemetryReporter.kt` | 遥测上报：组装并发送遥测事件。 |

### 3.8 UI

| 文件 | 功能 |
|---|---|
| `ui/FeedbackDialog.kt` | 反馈弹窗：表单、附件列、截图/录屏入口。 |
| `ui/ScreenshotEditorDialog.kt` | 截图编辑弹窗：查看/编辑/重拍/删除。 |
| `ui/FeedbackUiDrawables.kt` | 自定义 Drawable：复选框、截图操作图标等。 |
| `ui/SpeechInputText.kt` | 语音输入文本合并工具（插入识别文本到光标处）。 |

### 3.9 上传 upload

| 文件 | 功能 |
|---|---|
| `upload/BodyUploadWorker.kt` | 正文上传 WorkManager：加密、签名、上传、进度状态。 |
| `upload/AttachmentWorker.kt` | 附件上传 WorkManager。 |
| `upload/BodyUploadAckValidator.kt` | 正文回执校验：clientFeedbackId 匹配与接受状态。 |
| `upload/AttachmentUploadAckValidator.kt` | 附件回执校验。 |
| `upload/HistoricalBodyRetry.kt` | 历史正文补传：旧版本遗留待上传数据重试。 |

### 3.10 诊断 diagnostics

| 文件 | 功能 |
|---|---|
| `diagnostics/CompatibilityDiagnostics.kt` | 兼容性诊断：检查设备/权限/依赖项并输出诊断项。 |

### 3.11 SDK 测试

- `sdk/src/test/kotlin/com/feedback/sdk/**`：单测（日志缓冲、配置、加密、状态机、隐私过滤、UI 文本、回执校验、上传处理器、历史重试）。
- `sdk/src/androidTest/kotlin/com/feedback/sdk/**`：仪器化测试（Room DAO、上传 Worker）。

## 4. Android Demo 应用

### 4.1 demo（通用宿主 Demo）

| 文件 | 功能 |
|---|---|
| `DemoApplication.kt` | 应用入口：SDK 初始化与全局配置。 |
| `HomeActivity.kt` | 首页：模拟已安装学习机应用列表、触发各类反馈。 |
| `MainActivity.kt` | 主界面：SDK 功能演示面板、日志面板。 |
| `SecureTestActivity.kt` | 安全测试页：签名/加密链路演示。 |
| `TransportPublicKey.kt` | 传输公钥：从 BuildConfig 读取服务端公钥。 |
| `universal/UniversalFeedbackGateway.kt` | 通用反馈网关：上下文（应用/页面/模式）与 SDK 入口适配。 |
| `universal/UniversalFeedbackLauncher.kt` | 通用反馈启动器：从外部触发打开反馈。 |
| `universal/UniversalFeedbackActivity.kt` | 通用反馈页面。 |
| `universal/UniversalFeedbackOverlayService.kt` | 悬浮球/悬浮窗服务。 |
| `universal/ForegroundAppDetector.kt` | 前台应用检测：识别当前学习机 App 并构造反馈上下文。 |
| `universal/ScreenCaptureSession.kt` | 录屏会话状态机。 |
| `universal/ScreenCaptureSessionService.kt` | 录屏会话前台服务。 |
| `universal/ScreenCapturePermissionActivity.kt` | 录屏权限页。 |
| `universal/CapturedScreenshotRegistry.kt` | 截图临时登记：token 换字节、过期。 |
| `universal/UniversalLaunchRegistry.kt` | 启动上下文登记：token 换上下文。 |
| `universal/UniversalFeedbackRecoveryRegistry.kt` | 反馈恢复登记：失败草稿可恢复。 |
| `universal/UniversalTriggerActivity.kt` | 外部触发器（deep link/快捷键）入口。 |
| `demo/src/test/...` | 登记表与启动表的单元测试。 |

### 4.2 health-demo（健康/绘制类宿主）

| 文件 | 功能 |
|---|---|
| `HealthDemoApplication.kt` | 应用入口：SDK 初始化与公钥加载。 |
| `MainActivity.kt` | 健康类功能演示界面（侧栏 + 仪表盘）。 |

### 4.3 child-art-demo（儿童美术类宿主）

| 文件 | 功能 |
|---|---|
| `ChildArtApplication.kt` | 应用入口：SDK 初始化。 |
| `DrawingView.kt` | 儿童画板：画笔/颜色/清除/笔画计数。 |
| `MainActivity.kt` | 画板应用主界面（工具栏 + 画布 + SDK 反馈入口）。 |

## 5. AI 提示词资产 prompts

`prompts/` 是受治理的提示词资产库，以 Git 为权威源：

| 路径 | 功能 |
|---|---|
| `manifest.json` | 资产清单：skillCode、语义版本、文件路径、SHA-256、模型参数（temperature、response_format）。 |
| `README.md` | 资产治理说明：版本化、发布状态机（DRAFT→TESTED→APPROVED→PUBLISHED）、回滚规则。 |
| `feedback_classify/` | 反馈分类技能提示词。 |
| `assistant_intent_parse/` | 分析助手意图解析技能。 |
| `assistant_answer_generate/` | 分析助手回答生成技能。 |
| `root_cause_quick/` | 快速 RCA 技能。 |
| `root_cause_deep_agent2/` / `root_cause_deep_agent3/` | 深度 RCA 多智能体技能（agent2/agent3）。 |
| `impact_assess/` | 影响评估技能。 |
| `report_generate/` | 报告生成技能。 |
| `alert_route/` | 告警路由建议技能。 |
| `knowledge_search/` | 知识检索技能。 |
| `evaluations/` | 评测用例与标注集。 |

## 6. 脚本与运维 scripts / tools

### 6.1 scripts（8 个）

| 文件 | 功能 |
|---|---|
| `generate_sdk_mock.sh` | 生成 SDK 模拟数据。 |
| `generate_table_mock.sh` | 生成表格渠道 mock 数据。 |
| `publish_prompt.sh` / `update_prompt.sh` / `rollback_prompt.sh` | Prompt 发布/更新/回滚流程脚本。 |
| `skill_stats.sh` | 技能使用统计。 |
| `verify_ai_governance_mysql.sh` | AI 治理 MySQL 侧验证。 |
| `day8-unified-acceptance.sh` | Day8 统一验收。 |

### 6.2 tools（63 个）

按用途分组：

- **后端生命周期**：`manage-backend-local-secure.sh`（安全启动器 start/status/logs/restart/stop）、`run-backend-local-secure.sh`（Keychain 约定启动）、`backend-instance-guard.sh` / `backend-instance-guard-test.sh`（单实例守护与测试）。
- **数据与模拟**：`generate_demo_realtime_sdk_seeds.mjs`、`generate_full_system_test_data.mjs`、`day10-sdk-load.js`、`verify-section17-test-data.mjs`、`enable-demo-alert-rules.sh`。
- **SQL 修复与基线**：`reset-to-four-import-batches.sql`、`normalize-four-channel-times-to-today.sql`、`redistribute-four-channel-trend-*.sql`、`repair-channel-analysis-simulation.sql`、`rebuild-ai-tutor-duplicate-clusters.sql`、`day10-p05-*.sql`、`day10-p07-*.sql`、`day10-performance-*.sql`、`day10-core-sql-explain.sql`。
- **验收验证（verify-*.sh）**：按主题覆盖 Day4~Day8、Day10、Section15/17、定义 DEF007/DEF008、主题 topic03~08、oncall REM 等，例如 `verify-day6-alert-center.sh`、`verify-day7-user-rbac.sh`、`verify-day8-real-api.sh`、`verify-def007-six-pdf.sh`、`verify-section17-full-dataset.sh`、`verify-topic06-main-chain-reconciliation.sh` 等。
- **索引测试环境**：`index-test-env/`、`milvus-lite/`（本地 Milvus Lite 工具）。

## 7. 其他

| 文件/目录 | 功能 |
|---|---|
| `settings.gradle.kts` | Gradle 根工程模块定义：backend、sdk、demo、health-demo、child-art-demo。 |
| `build.gradle.kts` | 根构建脚本：插件版本（Spring Boot、Kotlin、Android）与仓库。 |
| `gradle.properties` | Gradle JVM 参数、Kotlin 目标、SDK 服务地址。 |
| `requirements.txt` / `venv/` | 辅助 Python 脚本依赖与虚拟环境。 |
| `config/` | 环境变量示例（`alerts.env.example`）与本地密钥目录（不入库）。 |
| `deploy/` | 部署模板（`mdm-policy.json.example` MDM 策略示例）。 |
| `artifacts/` | 产物：`xt11a`（设备产物）、`demo-alert-activation`。 |
| `output/` | 运行输出：PDF、性能、验收结果。 |
| `data/` | mock 数据文件。 |
| `.github/workflows/` | CI 工作流。 |
