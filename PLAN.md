# Feedback Platform 一期页面治理实施计划

> 计划状态：本地业务代码及本机 MinIO 历史真实迁移已完成；服务器/生产 MinIO 与 Git 元数据未提供，服务器部署及提交分别为 `BLOCKED_EXTERNAL`  
> 编制日期：2026-09-03（Asia/Shanghai）  
> 治理基线：`docs/delivery/16-ai-engineering-remediation-governance.md`  
> 一期页面需求：`docs/delivery/14-phase-one-simplification.md`  
> 当前进度口径：以用户本次明确确认的 P1-A、P3-01-R1 已完成为准；项目快照中“待复审”的旧表述待最终同步

## 1. 用户问题与成功标准

本计划先治理一期页面直接调用链，再实施 P1-C 对象存储切换。页面目标是：附件缺失不拖垮反馈详情；每个图片、视频和日志均作为独立极简缩略项展示；视频显示首帧并覆盖播放键；关键信息在不产生 N+1 的单条详情中直接展示；日志状态不再无事实地统称“过期”；问题簇页面忠实呈现 API 事实。除第 7.4 节已经专项批准的原始日志授权与下载端点外，不新增页面业务接口。不得修改 `ClusterDecisionModel`、`ClusteringEngine` 等归簇核心，不直接执行 SQL、不改写已应用 Flyway、不改变业务模型或权限角色边界。

用户于 2026-09-03 已专项批准第 7.4 节的合同/API 扩展：ADMIN/具备 RAW 权限的技术角色可预览未脱敏原文，并通过独立用途令牌下载未截断、未解压、未转码的原始日志或原始压缩包；产品角色继续只能预览局部隐藏内容。页面预览仍限制为 5 MB。现有预览流语义保持不变，原始下载不得通过保存预览 Blob 冒充。

成功标准：

1. 反馈详情不再为同一附件摘要重复请求；附件为空、缺失或按需加载失败时，主详情仍可阅读并给出明确空态/错误态。
2. 反馈详情不再把同类证据合并成一个带数量的卡片：每个图片显示自己的缩略图，每个视频显示自己的首帧缩略图和居中播放键，每个日志显示独立的无文字文件图标缩略项；不展示数量、格式、大小或说明文字。缩略项保留 `aria-label`/title 等非视觉可访问描述。
3. 附件按当前角色权限直接展示，不存在“受控查看”或填写查看理由的产品流程；底层继续使用 `列表 -> 实时权限/安全复核 -> 一次性同源流`，不暴露本地路径、对象键、永久 URL 或令牌，页面不增加附件直传文件系统的路径。
4. 问题簇列表和详情分别展示 API 原样返回的簇名称/现象、相似度、应用维度、模块和分类，不从标题或其他字段拼造应用或簇名。
5. ADMIN/OPERATOR 的人工确认、创建、拒绝、暂缓和解除关联继续通过 `/api/v1/cluster/**` 标准 API；一期技术/产品角色不被页面擅自放权。
6. 低信息反馈不以页面字符串猜测伪装成可靠分类；在缺少获批、可分页的状态事实时，不伪造“已从 MAIN 统计排除”的结论。
7. 定向测试、前端全量测试/lint/类型检查/构建及治理门禁通过；最终形成 `COMPLETION_REPORT.md`、更新 `docs/PROJECT_SNAPSHOT.md`，并在具备 Git 元数据后使用包含 P1/P2/P3 编号的提交信息提交。
8. 日志状态仅依据现有安全状态、HTTP 结果和明确的保留期事实展示；授权失效、检查中、检查失败、文件缺失/已清理分别提示，未知原因不得显示“已过期”。
9. 原始日志下载必须返回存储中的原始日志或原始压缩包字节，保留原始 MIME/安全扩展名，使用独立的一次性下载授权并记录 `ORIGINAL_LOG_DOWNLOAD` 审计；不得复用内联查看令牌，不得把 5 MB 预览文件标成原始文件。
10. 日志预览按角色冻结：ADMIN/RAW 技术角色看到未脱敏原文，产品角色看到局部隐藏内容；普通日志不做内容改写，压缩包只做安全解压以生成可读文本，所有页面预览最多 5 MB。预览上限只限制浏览器展示，不限制获批后的原始文件下载。
11. 页面治理通过后，新附件写入和权威读取切换到私有 MinIO/S3；历史附件以空间有界的幂等小批次迁移并完成长度、哈希和数量对账。每个附件仅在对象长度、SHA-256、数据库 `AVAILABLE` 与安全路径复核全部成功后精确删除对应旧文件；禁止目录级、递归或通配符删除，禁止删除 MinIO 数据。

## 2. 探索结论

### 2.1 工作区与治理事实

- 实际应用根目录是 `/Users/yang/Feedback_platform/feedback-platform`，不是快照中旧的 `/Users/yang/projects/feedback-platform`。
- 当前候选目录没有 `.git` 元数据，`git status`、`git diff`、提交和依赖 Git 根的 `dev-check` 无法执行。实施可以在用户批准后进行，但“最终 Git 提交”必须先由用户提供/恢复受控 Git 工作区。
- 合同要求的上下文脚本在当前候选中不存在：`tools/context-start.sh` 和文档所述 `../tools/context-start.sh` 均不可用。现有 `tools/` 只有运行、验收和数据脚本。
- 指定的 `backend/src/main/kotlin/com/feedback/service/page/` 不存在；实际页面应用服务位于 `service/query/`、`service/query/analysis/`、`service/query/detail/` 和 `service/query/listprojection/`。
- 当前前端媒体组件已包含 IntersectionObserver 懒加载、3 个自动预览并发槽、弹窗关闭取消、Blob URL 清理、视频 `preload="metadata"`、失败原因和日志 5 MB 有界预览；本批是在这些既有能力上补齐事实一致性和安全边界，不重复重写。
- 实施前 P1-A 只有基础设施和可空对象位置合同；本次已补齐统一发布器、`AVAILABLE` 权威对象读取、历史小批迁移与逐附件清理 worker。本机 `127.0.0.1:19000/feedback-private` 已完成 32,730 个附件、128,331,368,229 字节的真实迁移与逐项清理，活跃迁移/清理候选及两个历史根 `.fbs` 均为 0；服务器/生产 MinIO 尚未提供，服务器部署仍为 `BLOCKED_EXTERNAL`。

### 2.2 确定的待修改点

| 编号 | 证据 | 影响 | 计划处置 |
|---|---|---|---|
| P1-PAGE-01 | `useFeedbackDetailReadModel.ts` 同时调用详情和附件列表，但详情 DTO 已内嵌附件；五个请求由一个 `Promise.all` 绑定 | 附件请求失败会使整个详情失败，并产生重复读取 | 直接使用详情响应的 `attachments`，移除页面初始化的重复附件请求；保留簇列表按需取附件摘要 |
| P1-PAGE-02 | 流响应文件名只移除双引号，页面对其他附件使用通用下载；空/未知元数据依赖零散兜底 | 响应头和缺失态合同不够集中 | 收紧安全文件名和附件空态；保留一次性授权、`no-store`、`nosniff` |
| P2-PAGE-01 | 所谓缩略图实际下载完整图片 Blob 后由 CSS 缩放，且媒体判定接受任意 `image/*`/`video/*` | 网络/内存收益有限，过宽格式可能把不支持或不应内联的类型送入渲染器 | 增加严格白名单与客户端有界缩略图生成；未知类型只显示受控附件状态，不内联渲染 |
| P2-PAGE-02 | API 响应已有严格 CSP，但前端 Nginx/Vite 页面壳未冻结媒体 CSP | 生产页面壳的 `blob:` 媒体来源和其他来源策略没有可审查合同 | 为生产 Nginx 和本地 Vite 开发头配置对应 CSP，冻结 `img-src`/`media-src`/`connect-src` 等最小来源 |
| P3-PAGE-01 | 问题分析页已有应用维度，簇详情 API 已返回内部名称、产品现象、模块和分类，但详情页未直接展示后三类事实 | 用户无法直接核对 API 字段；回退表达可能混淆簇名和现象 | 分字段原样展示，空值统一说明；不使用标题或前端拼接生成簇名/应用名 |
| P3-PAGE-02 | 人工审核 API/页面已存在，但页面大量使用 `any`，`SKIP` 文案只是“稍后处理” | 交互有标准 API，但页面类型和状态语义不够清晰 | 收口现有类型，文案改为“暂缓/待补充”，继续使用 CAS、UUID 幂等号和原因字段 |
| P3-BLOCK-01 | P3-01 的 `classification_status` 固定为 `PENDING`；活动页面投影只包含 MAIN 成员；低信息当前仅能从核心格式化哨兵文案猜测 | 无法在不改核心/投影/查询合同的情况下可靠分页展示低信息并从 MAIN 总数排除 | 本批禁止用文案过滤、少算当前页或循环翻页伪造统计；列为审批阻塞，见第 7 节 |
| P3-BLOCK-02 | 人工归簇接口只允许 ADMIN/OPERATOR，一期技术/产品角色仅有读权限 | 在一期簇详情直接加确认按钮会稳定返回 403，并违反已批准权限包 | 不修改 `WebSecurityConfig`，不向一期角色暴露写按钮；人工操作保留在现有审核页 |
| P1-PAGE-03 | 渠道列表的“关键信息”列只有 `diagnosticAvailability.types`，真实值只由单条 `GET /feedback/{id}/diagnostics` 返回 | 当前按钮只显示类型，不是用户要求的关键信息；逐行自动调用会形成被治理合同禁止的 N+1 | 单条反馈详情使用现有诊断 API 直接展示值；渠道列表保持类型/可用性摘要并明确列为接口合同阻塞，不用逐行请求或标签冒充实际值 |
| P2-PAGE-03 | 视频卡片当前只有固定播放图标，受控 Blob 仅在弹窗打开时加载 | 证据区无法快速辨认视频内容 | 从一次性受控 Blob 解码首个可寻址画面并生成临时缩略 Blob；缩略与播放复用内容会话，避免为同一次令牌二次取流 |
| P1-PAGE-04 | `available=false` 和 HTTP 404 文案把检查中、检查失败、缺失、清理和授权问题混写成“过期” | 页面展示超出 API 事实，导致用户误判日志生命周期 | 用现有四项安全状态及 HTTP 状态精确映射；诊断 DTO 无 `retentionUntil`，因此该入口不声称已过保留期 |
| P2-PAGE-04 | `GovernedEvidenceViewer` 按图片/视频/日志类型各合并为一个带计数和文字的卡片，弹窗再批量展示 | 用户不能在证据区直接逐项识别附件 | 详情页改为按附件 ID 渲染极简缩略项；移除可见计数、格式、大小、footer 和说明文字，图片/视频点击只打开对应附件，日志点击只打开对应日志 |
| P2-BLOCK-05 | 现有 `/attachments/{attachmentId}/stream` 对 LOG 固定执行 `consumeForView`，按角色返回 RAW 原文或局部隐藏内容，压缩包安全解压且预览最多 5 MB；合同明确“日志只查看、不下载” | 现有获批 API 只能提供角色化有界预览，不能下载原始日志或原始压缩包 | 保留并测试现有原始/局部隐藏预览语义；下载需先修订治理合同并专项批准独立原始下载授权/API，详见第 7.4 节 |

### 2.3 当前调用链和逐步数据流

#### 附件与媒体

```text
反馈详情页
  -> GET /api/v1/feedback/{id}
  -> FeedbackDetailApplicationService
  -> SecureAttachmentRepository.findNonLogListFacts（单表、有界）
  -> FeedbackDetailResponse.attachments
  -> GovernedEvidenceViewer
  -> POST /api/v1/feedback/{id}/attachments/{attachmentId}/authorize
  -> GET 一次性 downloadUrl
  -> Blob URL，仅在当前页面/弹窗会话内存在
```

反馈详情直接使用详情 DTO 内嵌的逐项图片/视频摘要，并使用本页唯一一次 diagnostics 响应取得逐项日志元数据；每个缩略项按 ID 绑定对应附件。缩略内容进入视口后才走相同授权流，自动加载全局最多 3 个并发。列表/簇成员行只有数量而没有逐项附件 ID，不为逐项展示发起逐行请求，详见第 7.3 节。

#### 问题簇展示

```text
GET /api/v1/dashboard/problem-analysis
  -> 固定唯一 ACTIVE generation
  -> 成员投影单表分页/聚合
  -> 簇事实、身份、应用、地域、安全正文按页批量单表读取
  -> ProblemClusterItemResponse
  -> ProblemAnalysis.vue 原样展示

GET /api/v1/reports/cluster/{id} + /feedback
  -> 同 generation、同时间/权限范围
  -> PhaseOneClusterDetailResponse + PhaseOneClusterFeedbackPageResponse
  -> ClusterDetail.vue 展示模块、分类、来源、相似度和决策依据
```

#### 人工确认

```text
ADMIN/OPERATOR 打开 /monitor/cluster-audit
  -> GET /api/v1/cluster/review
  -> POST confirm/create/reject/skip/unlink
  -> expectedVersion 精确 CAS + UUID requestId + 原因
  -> 服务端统一权限、审计和业务写入
```

页面不直接修改簇身份，不调用数据库，不复制服务端权限判断。

## 3. 页面相关文件清单

### 3.1 前端页面层（现状 148 个 `.vue`/`.ts` 文件）

- 应用与路由：`src/App.vue`、`src/main.ts`、`src/router/index.ts`、`src/router/guards.ts`。
- API：`src/api/admin.ts`、`aiReview.ts`、`alert.ts`、`auth.ts`、`channelGovernance.ts`、`cluster.ts`、`dashboard.ts`、`feedback.ts`、`import.ts`、`notification.ts`、`productFeedback.ts`、`report.ts`、`request.ts`、`search.ts`。
- 页面：`src/views/LoginPage.vue`；`admin/{AlertConfigPage,ClusterEvaluationPage,FeishuRoleMappingPage,MaskingRulePage,SystemConfigPage,UserPermissionPage}.vue`；`alert/AlertCenter.vue`；`channel/{AllFeedbackPage,FeedbackDetailPage,JiraFeedbackPage,QualityFeedbackPage,RedblackFeedbackPage,SdkFeedbackPage,StationFeedbackPage}.vue`；`dashboard/ProblemAnalysis.vue`；`monitor/{AiReviewPage,ClusterAuditPage,ImportManagePage}.vue`；`notification/NotificationCenter.vue`；`report/{ClusterDetail,ReportList}.vue`；`tasks/{ChannelAcceptancePage,ProductFeedbackPage}.vue`。
- 页面子组件：`src/views/channel/components/{ChannelFeedbackList,ChannelPageHero}.vue`、`ChannelSourceIcon.vue`、`src/views/dashboard/components/StatisticCard.vue`、`src/views/monitor/components/{ClusterReviewDetail,ClusterReviewList,ImportBatchDetail,ImportBatchList,ImportUploadDialog}.vue`。
- 通用组件：`src/components/charts/{BarChart,HeatmapChart,PieChart,TrendChart}.vue`、`src/components/common/{ChannelCard,DataTable,FilterPanel,LearningMachineIcon,MetricCard,PageHeader,PageSkeleton,StateResult,StatusBadge}.vue`、`src/components/evidence/{GovernedDiagnosticsDialog,GovernedEvidenceViewer,GovernedLogPreview}.vue`、`src/components/feedback/FeedbackDetail.vue`、`src/components/layout/{AppLayout,GlobalSearch,Navbar,Sidebar}.vue`。
- 页面状态/展示逻辑：`src/composables/useGovernedAttachmentSession.ts`、`src/query/client.ts`、`src/stores/{app,auth,feedback,preferences}.ts`、`src/views/channel/{feedbackDetailPresentation,useFeedbackDetailCompatibility,useFeedbackDetailReadModel,useRelatedFeedbackPage}.ts`、`src/views/channel/components/{phaseOneChannelColumns,phaseOneFeedbackListPresentation,usePhaseOneFeedbackList}.ts`、`src/views/monitor/aiReviewRules.ts`、`src/views/report/{phaseOneClusterPresentation,usePhaseOneClusterDetail}.ts`、`src/views/admin/alertConfigValidation.ts`。
- 页面工具：`src/utils/{authTransition,bulkOperations,controlledAttachmentDownload,datetime,externalWindow,governedLogPreview,notificationGroups,performance,phaseOneAccess,privacyStatus,region,requestId}.ts`。
- 对应测试与样式：上述目录中的 `*.spec.ts`/`*.test.ts`，以及 `src/styles/global.css`、`views/channel/{FeedbackDetailPage.css,components/ChannelFeedbackList.css}`、`views/report/ClusterDetail.css`。

本批预计只修改第 5 节明确列出的子集；其余均只作为回归面，不顺手调整。

### 3.2 后端 Controller（现状 30 个文件）

- `controller/admin/`：`AdminManagementController.kt`、`AiAssetAdminController.kt`、`AlertRuleController.kt`、`ApplicationIdentityAdminContracts.kt`、`ApplicationIdentityAdminController.kt`、`ClusterAiEvaluationController.kt`、`ClusterReclassificationController.kt`、`DeadLetterController.kt`、`DynamicConfigAdminController.kt`、`IndexConsistencyAdminController.kt`、`MaskingRuleAdminController.kt`、`MaskingSecurityController.kt`。
- `controller/alert/`：`AlertController.kt`、`ExternalSecurityEventController.kt`、`InAppNotificationController.kt`、`NotificationProviderReceiptController.kt`。
- 其他：`controller/auth/AuthController.kt`、`controller/imports/ImportController.kt`、`controller/report/ReportController.kt`、`controller/sdk/SdkFeedbackController.kt`、`controller/station/StationFeedbackController.kt`。
- `controller/query/`：`AiReviewController.kt`、`ChannelAcceptanceController.kt`、`ClusterReviewController.kt`、`IngestionOperationsController.kt`、`PhaseOneAnalysisControllers.kt`、`PhaseOneFeedbackController.kt`、`PhaseOneHttpContracts.kt`、`ProductFeedbackRequestController.kt`、`SearchController.kt`。

本批只计划触及 `PhaseOneFeedbackController.kt`；`ClusterReviewController.kt` 和 `PhaseOneAnalysisControllers.kt` 仅作为获批接口合同读取，不新增路由。

### 3.3 实际页面应用服务（`service/page/` 不存在）

- `service/query/`：`ChannelAcceptanceService.kt`、`ClusterReviewService.kt`、`FeedbackDiagnosticsApplicationService.kt`、`FeedbackDistributionCompensationService.kt`、`FeedbackQuery.kt`、`MetricGovernanceQueryService.kt`、`PhaseOneFeedbackAttachmentApplicationService.kt`、`PhaseOneReadContracts.kt`、`PhaseOneRelatedFeedbackApplicationService.kt`、`PhaseOneSearchSuggestionApplicationService.kt`、`ProcessingChainTimeRange.kt`、`ProductFeedbackRequestService.kt`、`SimilarFeedbackService.kt`。
- `service/query/analysis/`：`PhaseOneProblemAnalysisApplicationService.kt`、`PhaseOneProblemAnalysisContracts.kt`。
- `service/query/detail/`：`FeedbackChannelFacts.kt`、`FeedbackDetailApplicationService.kt`、`FeedbackDetailAssembler.kt`、`FeedbackDetailResponse.kt`、`FeedbackNavigationResolver.kt`、`FeedbackTimelineResponse.kt`。
- `service/query/listprojection/`：`FeedbackListProjectionAssembler.kt`、`FeedbackListProjectionBackfillApplicationService.kt`、`FeedbackListProjectionBackfillContracts.kt`、`FeedbackListProjectionBackfillRunner.kt`、`FeedbackListProjectionDirtyMarker.kt`、`FeedbackListProjectionGenerationVerifier.kt`、`FeedbackListProjectionMutationCoordinator.kt`、`FeedbackListProjectionRefreshWorker.kt`、`FeedbackListProjectionSynchronizer.kt`、`PhaseOneActiveGenerationProvider.kt`、`PhaseOneFeedbackListApplicationService.kt`、`PhaseOneFeedbackListAssembler.kt`、`PhaseOneFeedbackListContracts.kt`。
- 媒体边界：`service/attachment/{AttachmentSecureStreamService,FeedbackAttachmentSummaryResponse,InlineLogArchivePreview,InlineLogView}.kt`、`service/attachment/storage/{AttachmentObjectStorage,S3CompatibleAttachmentObjectStorage}.kt`。

## 4. 现有获批 API 清单

| 页面需要 | 现有 API | 本批用法 | 是否新增 |
|---|---|---|---|
| 反馈详情及内嵌附件 | `GET /api/v1/feedback/{id}` | 页面初始化唯一附件摘要来源 | 否 |
| 单条反馈关键信息/日志元数据 | `GET /api/v1/feedback/{id}/diagnostics` | 详情页直接展示，日志按需预览 | 否 |
| 渠道列表关键信息安全投影 | `GET /api/v1/feedback` 的 `records[].keyDiagnostics` | 当前页由后端批量读取、按角色呈现并批量审计；前端不逐行请求 | 扩展现有响应 |
| 簇行按需附件摘要 | `GET /api/v1/feedback/{id}/attachments` | 仅进入视口/打开媒体时调用 | 否 |
| 一次性附件查看地址 | `POST /api/v1/feedback/{id}/attachments/{attachmentId}/authorize` | 图片、视频、其他附件按权限直接预览；无请求体、无理由输入 | 否 |
| 一次性附件流 | `GET /api/v1/feedback/{id}/attachments/{attachmentId}/stream?grantId=...` | 同源 Blob，关闭即销毁 | 否 |
| 问题分析 | `GET /api/v1/dashboard/problem-analysis` | 全部/跨渠道两个独立分页区 | 否 |
| 簇详情及成员 | `GET /api/v1/reports/cluster/{id}`、`GET /api/v1/reports/cluster/{id}/feedback` | 原样显示现有字段 | 否 |
| 人工归簇 | `GET/POST /api/v1/cluster/review/**`、`POST /api/v1/cluster/{id}/**` | 只供 ADMIN/OPERATOR 现有审核页 | 否 |

浏览器反馈媒体上传 API 当前不存在，本批不得新增。`/api/v1/import/**` 是管理员表格/受控导入域，`/api/v1/sdk/**` 是设备接入域，均不得被页面复用为反馈详情上传旁路。

原始日志下载独立授权链已经专项批准，端点冻结如下：

```text
POST /api/v1/feedback/{id}/attachments/{attachmentId}/authorize-original-download
  -> 无请求体、不要求用户填写用途或理由；服务端使用固定用途码 ORIGINAL_LOG_DOWNLOAD
  -> 实时复核反馈范围、附件归属、全部安全状态和原文权限
  -> 签发仅限 ORIGINAL_LOG_DOWNLOAD 的一次性令牌并写授权审计
GET /api/v1/feedback/{id}/attachments/{attachmentId}/original?grantId=...
  -> 再次绑定用户/反馈/附件/用途并原子消费令牌
  -> Content-Disposition: attachment
  -> 原始 MIME、清洗后的安全文件名、no-store、nosniff
  -> 原始日志或原始 ZIP/GZIP/TAR/TGZ 字节，不解压、不截断、不转码
```

下载令牌与现有内联查看令牌必须用途隔离，不能互换。用户已确认：仅 ADMIN 和具备 RAW 原文权限的技术角色下载原件；产品角色继续只能查看服务端局部隐藏后的 5 MB 有界预览，不得下载原件。ADMIN/RAW 技术角色的普通日志预览保持未脱敏原文；压缩日志在页面内安全解压后展示原始文本条目，仍限制 5 MB，但下载返回未解压、未截断的原始压缩包。

## 5. 拟实施批次与文件改动

### 5.1 P1-B-PAGE：附件详情容错与安全交付收口

预计修改：

- `frontend/src/views/channel/useFeedbackDetailReadModel.ts`
  - 移除初始化阶段的重复 `getFeedbackAttachments` 调用。
  - 使用 `GET /feedback/{id}` 已返回的 `data.attachments ?? []`；缺失字段按空列表向后兼容。
  - 保持路由代次、敏感状态清理和相关反馈分页不变。
- `frontend/src/views/channel/FeedbackDetailPage.spec.ts`
  - 冻结“详情内嵌附件为唯一初始化来源”“空附件不影响主详情”“不重复请求附件摘要”。
- `frontend/src/components/evidence/GovernedEvidenceViewer.vue`
  - 空/缺失/不可用附件分别显示稳定状态；按需摘要失败只影响证据组件，不冒泡拖垮页面。
- `frontend/src/components/evidence/GovernedDiagnosticsDialog.vue`、`frontend/src/utils/controlledAttachmentDownload.ts`
  - 根据 `securityStatus`、`integrityStatus`、`malwareScanStatus`、`contentReviewStatus` 区分“安全检查中”和“安全检查未通过”。
  - 将 401/403 表述为授权失效或无权限，将 404 表述为文件缺失、已清理或当前不可用；诊断合同没有 `retentionUntil`，不得再凭 404 或 `available=false` 显示“已过期”。
  - 保持实时授权复核；列表时刻的 `available` 仅决定入口状态，不替代授权结果。
- `frontend/src/utils/controlledAttachmentDownload.test.ts`、`frontend/src/components/evidence/GovernedDiagnosticsDialog.spec.ts`（按现有测试职责补充）
  - 覆盖四项安全状态组合、授权失效、404、409/422、5xx 及未知状态，冻结不误报“过期”。
- `backend/src/main/kotlin/com/feedback/controller/query/PhaseOneFeedbackController.kt`
  - 使用响应头安全构造方式处理文件名，移除 CR/LF、路径分隔和控制字符影响。
  - 在现有 `no-store`、`nosniff` 基础上补附件流的同源资源和沙箱策略头；不改变 URL、请求或响应体合同。
- `backend/src/test/kotlin/com/feedback/controller/query/PhaseOnePublicControllerContractTest.kt`（若现有控制器测试职责匹配则在原文件追加，否则新增聚焦合同测试）
  - 覆盖头注入、缓存、CSP、空附件和一次性流合同。

页面阶段不修改 SDK/Jira/Station 上传器、对象存储表字段和附件扫描规则；这些写入器及存储 Repository 的修改在页面验收后按第 5.5 节独立实施。

### 5.2 P2-PAGE：缩略图、格式兼容、懒加载和 CSP

预计修改：

- `frontend/src/components/evidence/GovernedEvidenceViewer.vue`
  - 保留 IntersectionObserver、并发槽和取消机制。
  - 详情模式按 `attachment.id` 逐项渲染图片和视频，不再按类型聚合为单个卡片；去除可见边框卡片、计数、格式、大小、footer 和说明文字，使用紧凑缩略图流布局。
  - 每张图片显示自己的缩略图，点击只打开该图片；不再点击一个聚合入口后批量加载全部图片。
  - 将可内联媒体限制为明确白名单：JPEG/PNG/WebP 图片和 MP4/WebM 视频；QuickTime 等浏览器不稳定格式只显示“不支持/受控下载不可替代预览”的解释，不尝试危险降级。
  - 下载后核对 Blob MIME；不匹配时不创建可渲染 URL。
  - 对小型白名单图片用 `createImageBitmap`/Canvas 生成有尺寸和像素上限的客户端缩略 Blob；能力不存在或解码失败时进入明确回退态，不影响点击后的受控原图查看。
  - 对白名单视频从同一次受控 Blob 创建隐藏视频元素，等待 `loadedmetadata` 后 seek 到首个可寻址时间点（优先接近 0 秒），在 `seeked` 后用 Canvas 生成有尺寸上限的首帧缩略 Blob；解码/seek 失败显示明确回退态。
  - 每个白名单视频进入视口后按全局并发限制生成首帧，缩略图中央覆盖播放键；点击只打开该视频并立即使用 controls 播放。首帧和播放复用同一个内容 Blob/会话，不使用已消费的一次性授权再次取流。
  - 首帧只存在当前页面会话；切换反馈、取消或卸载时销毁视频元素、Canvas、原始及缩略 Blob URL。
  - 日志缩略项由父页传入已读取的 `GOVERNED_ATTACHMENT` 诊断项，每个日志附件单独显示无文字文件图标；缩略项主点击只打开对应日志的页面内预览。原始下载只放在预览工具栏的独立图标动作中。
  - 正常态不显示任何说明文字；加载、不可用或失败以骨架/图标视觉状态表达，并通过 `aria-label`/title 提供可访问信息。错误详情只在用户点击失败项后用消息提示，不常驻占据缩略区。
- `frontend/src/utils/governedMedia.ts`（新增纯函数模块）
  - 集中 MIME/附件类型映射、预览允许策略、缩略尺寸计算和 Blob 类型核对，避免组件内散落字符串规则。
- `frontend/src/composables/useGovernedAttachmentSession.ts`
  - 如测试发现队列取消竞态，补齐已排队任务的确定性释放；不改变全局并发上限 3。
- `frontend/src/components/evidence/GovernedEvidenceViewer.spec.ts`
  - 覆盖 N 个图片/N 个视频/N 个日志生成对应数量的独立缩略项、无可见计数/格式/大小/说明文字、点击只打开目标附件、视口外不加载、3 并发上限、取消、空附件、MIME 欺骗、图片解码失败、每个视频首帧、播放键、首帧与播放不重复授权、seek/Canvas 失败、Blob URL 全释放和旧浏览器能力回退。
- `frontend/src/utils/governedMedia.spec.ts`（新增）
  - 覆盖类型白名单、未知类型、尺寸上限和 MIME 一致性。
- `deploy/nginx/frontend-cache.conf`
  - 为 HTML/SPA 响应增加生产 CSP：脚本仅同源，样式仅同源及 Element Plus 所需内联样式，图片仅同源/`data:`/`blob:`，媒体仅同源/`blob:`，连接仅同源；禁止 object/frame/base 扩张。
- `frontend/vite.config.ts`
  - 为开发服务器设置等价但允许 HMR WebSocket 的 CSP 响应头，确保开发验收无 CSP 控制台错误。
- CSP 合同测试（优先放入现有前端配置测试；若无匹配文件则新增 `frontend/src/utils/cspConfiguration.spec.ts`）
  - 冻结生产和开发策略差异仅限 HMR，防止后续放宽为任意远端媒体。

缩略图仅是页面临时派生 Blob，不写服务器、不落数据库、不成为权威附件。现有一次性流仍是唯一内容来源。由于现有服务没有服务端视频缩略图或 Range 专用合同，“每个视频自动显示首帧”意味着首次进入视口时需读取该视频的受控 Blob；以懒加载、最多 3 并发、取消和及时回收控制成本，不通过新增接口规避。

原始日志下载已经专项批准：日志预览弹窗工具栏增加纯图标下载动作；点击后单独申请 ORIGINAL_LOG_DOWNLOAD 授权并下载对应原始附件。缩略项主点击仍只打开预览，避免误触下载；下载失败不关闭已打开的安全预览。该动作不复用 `saveControlledBlob` 保存内联预览，也不改变现有 `consumeForView` 行为。

日志预览语义冻结如下：

| 来源与角色 | 页面预览 | 原始下载 |
|---|---|---|
| 普通日志；ADMIN/RAW 技术角色 | 未脱敏原文，最多 5 MB，不改写正文 | 完整原始日志字节 |
| 压缩日志；ADMIN/RAW 技术角色 | 安全解压后的原始可读文本条目，最多 5 MB | 完整原始 ZIP/GZIP/TAR/TGZ 字节，不解压 |
| 普通或压缩日志；产品角色 | 服务端局部隐藏后的可读文本，最多 5 MB | 禁止，返回 403 |

“原始预览”不表示把 ZIP/GZIP 二进制字节直接塞进文本组件，也不表示取消页面 5 MB 内存边界；它表示 RAW 角色的可读正文不经过局部隐藏或业务改写。

### 5.3 P1/P2-PAGE：渠道关键信息直接展示

预计修改：

- `frontend/src/views/channel/FeedbackDetailPage.vue`（以及现有详情读取模型/展示辅助文件）
  - 在单条反馈详情中调用现有 `GET /feedback/{id}/diagnostics` 一次，直接展示服务端已经按角色处理的关键信息值；技术角色显示 RAW，产品角色显示 PARTIALLY_HIDDEN，不在前端重新解密、拼接或扩大可见范围。
  - 诊断加载失败只影响关键信息区，不拖垮反馈正文、附件或簇信息；切换记录/卸载时取消请求并清除敏感状态。
  - 将同一次响应中的每个 `GOVERNED_ATTACHMENT` 日志项按附件 ID 传给证据组件，避免为日志缩略项增加请求。
- `frontend/src/views/channel/components/ChannelFeedbackList.vue`、`phaseOneChannelColumns.ts`
  - 保留现有 `diagnosticAvailability` 类型/可用性摘要，但把表头和说明改为“关键信息类型”，避免把类型按钮误称为实际值。
  - 不在列表逐行调用诊断 API；当前列表 DTO 没有实际值，在禁止新增 API 的约束下无法合规把所有值直接铺在列表中。
- `frontend/src/views/channel/FeedbackDetailPage.spec.ts`、`frontend/src/views/channel/components/ChannelFeedbackList.spec.ts`（若无现有匹配文件则新增）
  - 覆盖详情单请求直接展示、角色可见性原样采用、失败隔离、切换清理；列表 0 次逐行诊断请求且标签语义准确。

该子项的“详情直接显示”可执行；“渠道列表每行直接显示实际值”标记为 **BLOCKED_BY_CONTRACT**，详见第 7.3 节。

### 5.4 P3-PAGE：字段忠实展示与现有人工审核交互收口

预计修改：

- `frontend/src/views/dashboard/ProblemAnalysis.vue`
  - 问题簇现象只展示 `phenomenon`，不再回退到未在合同中的 `title`。
  - 影响应用按 API `affectedApplications[].application` 逐项展示，计数作为独立徽标，不拼造应用名；空值统一为“应用信息待补充”。
  - 保持两个独立分页和现有时间范围不变。
- `frontend/src/views/dashboard/ProblemAnalysis.spec.ts`
  - 冻结簇名/应用字段不跨字段回退、不显示内部哈希、空值和分页行为。
- `frontend/src/views/report/ClusterDetail.vue`
  - 将 API 的 `name`、`canonicalSymptom`、`functionModule`、`issueCategory` 分字段直接展示。
  - 成员行直接展示 `sourceRecordId`、`decisionType`、`similarity`、`decisionReason`、AI 字段；格式化只做百分比/空值，不用其他字段替代。
  - 当前接口没有簇详情级应用集合或成员应用字段，页面明确显示“当前接口未提供应用事实”，不从簇名、标题、渠道或模块推断。
- `frontend/src/views/report/phaseOneClusterPresentation.ts`、`phaseOneClusterPresentation.spec.ts`
  - 收口空值、未知枚举和相似度显示；未知决策保留服务端值或明确“未提供”，不统一冒充“自动归簇”。
- `frontend/src/views/report/ClusterDetail.spec.ts`
  - 覆盖上述字段逐项一致、空值、不拼接和原有证据/权限/分页合同。
- `frontend/src/views/monitor/ClusterAuditPage.vue`
  - 将 `selected` 和操作参数从 `any` 收口为 `ClusterReviewItem`/`ClusterCandidate`。
  - 将 `SKIP` 操作文案明确为“暂缓/待补充”，提示该动作不会由页面直接改 MAIN 身份。
  - 保留标准 API、CAS 版本、UUID requestId、3～500 字原因及 409 刷新处理。
- `frontend/src/views/monitor/components/ClusterReviewList.vue`、`ClusterReviewDetail.vue`
  - 使用具名 props/emits 类型；展示现有 `decision`、应用/模块/候选相似度事实，不引入开放 Map 作为新稳定接口。
- `frontend/src/api/cluster.ts`
  - 仅在现有响应真实字段范围内补严可空性和封闭枚举；不新增请求字段或端点。
- `frontend/src/views/monitor/components/ClusterReviewDetail.spec.ts`，必要时新增 `frontend/src/views/monitor/ClusterAuditPage.spec.ts`
  - 覆盖确认/新建/拒绝/暂缓/解除关联只走标准 API、命令字段完整、403 不展示越权成功、409 刷新及失败保留输入。

不会修改 `WebSecurityConfig`、`ClusterReviewService`、`ClusterLifecycleService`、任何 Cluster Mapper/XML 或核心算法类。

### 5.5 P1-C-OBJECT-STORAGE：真实写入、读取与历史附件切换（页面治理完成后实施）

本阶段已获用户批准，但必须在第 5.1～5.4 节页面/API 修改通过定向测试后开始，避免把页面回归与存储迁移故障混在同一验收面。

实施范围：

- 新附件写入：SDK、Station、Jira 和受控导入附件在完成既有校验、解密、哈希与安全状态流程后写入私有 MinIO/S3；对象键由服务端生成，不接受浏览器路径或对象键输入。
- 权限读取：页面按角色权限直接展示，一次性页面流和获批的原始日志下载流从权威对象位置读取；不要求填写理由，不返回永久 URL，不向前端暴露 bucket、object key 或凭据。
- 历史迁移：新增可断点续跑、幂等、有限批次的应用层迁移任务，逐个读取本地加密附件、写入对象存储、通过长度与 SHA-256 校验后，才用 Repository 原子登记对象位置及 `AVAILABLE` 状态。
- 双读过渡：对象位置为 `AVAILABLE` 时优先且只读对象存储；尚未迁移的旧行继续走本地隔离区。对象行读取失败不得静默回退本地副本，以免掩盖 MinIO 故障或读到不一致版本。
- 工作区迁移兼容：数据库中的历史本地绝对路径只可从显式配置的只读根读取；读取器和迁移发布器共用路径策略，校验真实路径、普通文件并拒绝符号链接逃逸。不得为解决历史 404 放宽到任意绝对路径。
- 事务边界：一次性令牌的归属、安全状态、用途、角色复核和原子消费在短事务内完成；本地文件打开、解密及 MinIO 网络读取必须在事务提交后执行。
- 本地清理：用户已明确批准迁移验证成功后逐附件精确删除。清理器必须重新读回对象并核对 bucket、key、version、长度、SHA-256、数据库 `AVAILABLE` 与白名单路径，写入删除意图审计后只调用单文件删除；随后以独立短事务 CAS 清空旧路径。任何一步失败均保留候选重试，且不得删除 MinIO 数据。
- 部署配置：私有 bucket、最小权限服务账号、TLS、端点和凭据通过环境变量或密钥管理提供；仓库不写真实凭据。若目标 MinIO 环境尚未提供，代码与迁移工具可完成，但真实数据切换标记为 `BLOCKED_EXTERNAL`，不得伪报已部署。

实现约束：

- 复用 V236 已存在的可空对象位置和生命周期字段，不修改已应用 Flyway，不直接执行 SQL，不以脚本绕过 Repository。
- 数据库登记失败时删除本次新写对象；对象校验失败时保留本地权威副本并把任务标记失败，不更新为 `AVAILABLE`。
- 写入切换采用显式开关与启动就绪检查；启用后缺 bucket、区域、TLS/端点或凭据时阻止启动，不回退为本地新写。
- 迁移任务限制并发、批次和重试次数，日志只记录附件 ID、对象键摘要、状态和错误码，不记录内容、令牌或凭据。
- 读取、迁移和清理均保持现有数据范围、安全扫描、内容审核、预览 5 MB 上限及 `ORIGINAL_LOG_DOWNLOAD` 权限合同不变。

## 6. UI 字段表（审批冻结稿）

### 6.1 反馈详情证据区

| 状态 | 页面直接显示 | 用户动作 |
|---|---|---|
| 图片 | 每个附件一张独立缩略图；无计数、格式、大小和说明文字 | 点击只查看该原图 |
| 视频 | 每个附件一张独立首帧缩略图，中央叠加播放键；无计数和说明文字 | 点击只打开该视频并播放 |
| 日志 | 每个日志附件一个独立无文字文件图标缩略项 | 主点击打开该日志预览；原始下载获批后由预览工具栏独立下载图标触发 |
| 加载中 | 对应位置的无文字骨架/占位图标 | 无 |
| 不可用/失败 | 无文字状态图标；原因保留在 `aria-label`/title | 点击后显示精确消息并允许重试，不影响主详情 |
| 无附件 | 证据区不渲染缩略项 | 无 |

### 6.2 渠道关键信息

| 位置 | 页面直接显示 | 数据与调用约束 |
|---|---|---|
| 渠道列表 | 关键信息类型及是否可查看 | 只使用列表 DTO 的 `diagnosticAvailability`，不逐行请求值 |
| 单条反馈详情 | 手机号、邮箱、设备号、用户 ID、账号上下文等现有诊断值 | 每条详情最多一次现有 diagnostics 请求；完全采用服务端 RAW/PARTIALLY_HIDDEN 结果 |
| 日志诊断 | 每个日志的独立无文字缩略入口 | 日志正文仍经实时授权和一次性流，不在列表预取 |

### 6.3 问题簇列表与详情

| 位置 | 字段来源 | 展示规则 |
|---|---|---|
| 问题分析列表簇现象 | `ProblemClusterItemResponse.phenomenon` | 原样，不回退标题 |
| 影响应用 | `affectedApplications[].application/count` | 名称和计数分开显示，不拼接推断 |
| 簇详情内部名称 | `ClusterDetailResponse.name` | 独立字段原样显示 |
| 产品现象 | `canonicalSymptom` | 独立字段原样显示 |
| 模块/分类 | `functionModule` / `issueCategory` | 独立字段，空值为待补充 |
| 成员相似度 | `similarity` | 仅数值百分比格式化 |
| 归簇依据 | `decisionReason`、`aiExplanation` | 各自标识来源，不互相伪装 |
| 应用事实缺失 | 当前簇详情/成员 API 无此字段 | 明确未提供，不从其他字段推断 |
| 人工操作 | 现有 cluster review API | 仅 ADMIN/OPERATOR 审核页显示 |

## 7. 阻塞项与需要用户在批准计划时确认的边界

### 7.1 低信息“独立显示且不计 MAIN”目前不能合规完成

这是数据合同缺口，不是单纯 UI 缺口：

1. P3-01 主问题事实的 `classification_status` 按已批准合同固定为 `PENDING`，不能区分低信息、应用未知和普通待处理。
2. 当前问题分析 API 只读取活动 MAIN 成员投影；没有低信息独立分页或状态字段。
3. 当前核心实现仍可能使用固定中文哨兵名创建低信息 MAIN；用 `phenomenon === 固定文案` 在前端过滤会造成总数、分页和跨渠道统计错误，也会把文案变化误当业务状态。
4. 正确修复需要 P3-02 的强类型低信息终态以及投影/API 合同，或新增获批接口；二者均被本次“不得修改核心、不得新增接口”约束禁止。

因此本计划将该项标为 **BLOCKED_BY_CONTRACT**：本批不会修改核心或 SQL，也不会声称低信息已从 MAIN 统计排除。用户若批准当前计划，即表示批准先完成其余页面治理，同时保留该验收项为未完成阻塞；不能据此关闭整个 P3/P4。

### 7.2 “暂缓/待补充”的可见范围

现有 `SKIP` 标准 API 会结束当前 PENDING 候选，现有 GET 只返回 PENDING，因而页面可提供并清晰命名该动作，但不能在操作后列出历史暂缓记录。没有现成获批历史列表 API，本批不会新增。若验收要求“操作后仍有独立暂缓列表”，同样需要另行审批 API/读模型。

### 7.3 渠道列表关键信息值直接展示已解除合同阻塞

用户于 2026-09-03 明确要求关键信息直接出现在渠道列表，不再点击字段打开窗口。现扩展既有列表行合同：`keyDiagnostics` 仅包含当前页非日志关键信息，后端在已验证列表范围内用有界批量查询读取，并复用统一解密、类型映射及技术/产品角色呈现规则；成功读取按反馈批量写安全审计。前端只渲染响应值，不发起逐行 diagnostics 请求，不持久化敏感值。

日志内容仍不进入列表合同，只在用户点击日志附件后走一次性安全流。逐附件图片/视频 ID 仍未进入列表投影，因此列表逐媒体缩略图保持原合同边界；这不影响本次“关键信息直接展示”完成。

### 7.4 原始日志/压缩包下载需要合同修订和 API 专项批准

当前存在三重硬阻塞：

1. `docs/delivery/14-phase-one-simplification.md` 第 4.1、5.2 和验收条款明确规定日志“只查看、不下载”。
2. `docs/delivery/16-ai-engineering-remediation-governance.md` 只允许页面内有界文本预览，不允许下载旁路。
3. 唯一获批流端点对 LOG 固定调用 `consumeForView`：RAW 角色取得未隐藏文本，产品角色取得局部隐藏文本，压缩包先安全解压，全部最多 5 MB；该结果是页面预览而非原始文件字节，仓库没有原始日志下载 API。

因此不能仅增加前端下载按钮，也不能把现有 stream 对日志改为原始响应：前者下载到的是预览而非原件，后者会破坏页面查看、向产品角色泄漏未隐藏内容，并改变既有 API 语义。正确实现需要先完成：

- 明确批准把一期合同改为“允许具备 RAW 原文权限的角色受控下载原始日志/原始压缩包”；
- 批准第 4 节所列独立一次性下载授权/API，或由 API 审批给出等价的强类型设计；
- 产品角色禁止原始下载（用户已确认）；
- 冻结独立用途令牌、实时安全复核、原始文件名清洗、`attachment` 响应头、`no-store`、`nosniff`、审计和测试合同。

上述合同和 API 扩展已于 2026-09-03 获批，本项解除 `BLOCKED_BY_CONTRACT` 并纳入本次实施；权限、用途隔离、安全复核、响应头和审计条件不得弱化。

### 7.5 提交门禁

当前候选无 `.git`，最终 commit 无法产生。计划批准不等于授权在其他原仓库覆盖文件；实施完成前需要用户提供当前候选对应的受控 Git 根或明确导入方式。

## 8. 测试矩阵与执行顺序

### 8.1 已完成的探索基线

- 前端定向：7 个测试文件、52 项测试全部通过。
- 后端定向测试尝试被当前沙箱禁止写用户级 Gradle wrapper 锁文件而未运行：`/Users/yang/.gradle/...gradle-8.14.5-bin.zip.lck (Operation not permitted)`。这不是测试失败；实施阶段需在允许的 Gradle 缓存环境或受控 Git 工作区重跑。

### 8.2 P1 完成后

- 前端：详情读取模型/详情页/证据组件/诊断状态映射定向测试。
- 后端：`PhaseOneFeedbackController`、`PhaseOneFeedbackAttachmentApplicationService`、`AttachmentSecureStreamService` 合同测试。
- 检查：同一详情初始化无重复附件 GET；单条详情最多一次诊断 GET；列表不产生逐行诊断请求；附件 0 条、404、403、409/422、5xx 均不污染主页面状态；没有保留期事实时不显示“过期”。

### 8.3 P2 完成后

- 前端：媒体纯函数、Evidence Viewer、Attachment Session、日志预览定向测试。
- 安全：MIME 欺骗、SVG/HTML/未知视频拒绝内联、CSP 头、Blob URL 释放、关闭取消、并发上限。
- 展示：N 个图片、N 个视频、N 个日志分别产生 N 个独立缩略项；正常态 0 个可见计数/格式/大小/说明文本；视频首帧中央有播放键且点击只播放目标视频。
- 性能：视口外 0 次媒体请求；所有进入视口的图片/视频共享不超过 3 的并发上限；缩略图像素/Blob 大小受限；同一次视频首帧和播放不重复消费授权；关闭或切换记录后 0 个遗留 Blob URL。
- 日志预览测试：ADMIN/RAW 技术角色的普通日志保持未脱敏原文且最多 5 MB；其压缩日志安全解压后保持原始可读文本且最多 5 MB；产品角色继续局部隐藏；压缩二进制不直接进入文本组件。
- 原始日志下载测试：RAW 技术/管理员成功下载完整原始日志和未解压原始压缩包、产品角色 403、查看令牌不能下载、下载令牌不能查看、令牌只能消费一次、实时安全状态失败关闭、文件名头注入防护、审计失败回滚，且下载不受 5 MB 页面预览上限截断。

### 8.4 P3 完成后

- 前端：ProblemAnalysis、ClusterDetail、presentation、ClusterAudit/Review 组件定向测试。
- 合同：字段逐项来源、空值、独立分页、CAS/幂等请求和权限可见性。
- 后端只重跑现有分析/详情/权限合同，不改其实现：`PhaseOneProblemAnalysisApplicationServiceTest`、Controller 合同、`HttpRoleAuthorizationMatrixTest`。

### 8.5 总回归

在具备正确工具链后依次执行：

```text
frontend: npm test
frontend: npm run lint
frontend: npx vue-tsc --noEmit
frontend: npm run build
backend: ./gradlew :backend:test
governance: tools/dev-check.sh --static
governance: tools/dev-check.sh --quick
```

若跨栈合同或部署配置变化达到 full 门禁条件，再运行 `tools/dev-check.sh --full`。真实 MinIO/MySQL/目标环境验证必须单独标明证据等级，不把 Mock 或本机构建写成生产成功。

### 8.6 P1-C 对象存储专项验收

- 单元/合同：四类写入器使用对象存储端口；对象键不可穿越；写入、数据库登记及补偿删除语义；对象读取不存在、缺桶、无权限、网络失败均失败关闭。
- 迁移：空批次、重复运行、进程中断续跑、同一附件竞争、校验不一致、数据库更新失败、对象已存在及本地源缺失。
- 双读：未迁移行读取本地；`AVAILABLE` 行读取 MinIO；`AVAILABLE` 对象缺失或读取失败时不回退本地。
- 真实环境：使用私有 MinIO 和测试 MySQL 上传普通图片、视频、日志与压缩日志，校验页面预览、原始日志下载、完整字节哈希、服务重启后续跑及凭据不可见。
- 切换门禁：历史对象数、总字节数和哈希校验数完全对账；活跃迁移候选与本机清理候选均为零，永久缺源均有明确失败分类，删除意图全部闭合，旧根无仍应迁移的 `.fbs`，本机 MinIO 健康。只有以上条件同时满足才判定本机迁移完成。

## 9. 风险、兼容性、部署与回滚

| 风险 | 控制措施 | 回滚 |
|---|---|---|
| 详情改用内嵌附件后遇到旧后端不返回字段 | TypeScript 保持可选，`undefined/null -> []`；不删除按需附件 API | 恢复单独请求，但必须改为不阻断主详情的 settled 分支 |
| 严格 MIME 白名单使历史异常元数据显示为不支持 | 保留元数据和明确状态，不删除附件，不绕过安全流 | 回退单个 MIME 映射，不放宽为任意 `image/*` |
| CSP 阻断 Vite HMR 或 Element Plus 样式 | 开发/生产分别冻结，开发只额外允许 HMR WebSocket；浏览器控制台验收 | 单独回滚 CSP 配置文件，不回滚媒体安全校验 |
| 客户端缩略解码占用内存 | 只处理小文件、限制像素、及时关闭 bitmap/回收 URL，全局 3 并发 | 退回“点击后查看”，不恢复全图自动预览 |
| 每个视频首帧需要读取受控 Blob，较大或较多视频的网络/内存成本高 | 仅进入视口后加载、全局最多 3 并发、首帧和播放复用 Blob、关闭立即取消并回收；在 `COMPLETION_REPORT.md` 单列大文件实测 | 若真实验收仍不可接受，停止自动首帧并申请服务端缩略图 API 审批；不绕过一次性令牌或偷偷恢复聚合计数卡片 |
| 极简无文字缩略项降低状态可见性 | 保留可访问名称、键盘焦点、title 和点击后的精确错误消息；加载/失败用一致图标状态 | 可单独回滚视觉层，不改变授权、Blob 或诊断数据流 |
| 渠道列表没有关键信息值 | 不做 N+1；在单条详情直接展示，并明确列表合同阻塞 | 回滚详情直显与列名，不改变 API/数据 |
| 日志 404 无法证明保留期过期 | 只显示“缺失/已清理/当前不可用”；安全快照按四项状态细分 | 回滚文案映射，不改变授权或安全状态 |
| 原始日志下载暴露敏感数据或恶意归档 | 专项 API、RAW 权限、实时四项安全复核、用途绑定一次性令牌、强制 attachment/no-store/nosniff、完整审计；产品角色明确禁止 | 关闭原始下载授权入口和前端按钮；不影响角色化内联预览 |
| 原始下载与预览共用令牌导致用途混淆 | 两类令牌不可互换、分别授权和原子消费；预览 API 行为保持不变 | 回滚独立下载链，不修改 `consumeForView` |
| 现有人工审核页和一期权限混淆 | 路由和后端仍仅 ADMIN/OPERATOR；一期簇详情不引入写 API | 回滚文案/类型改动，权限无变化 |
| 低信息统计被误宣称完成 | 在报告和快照明确 BLOCKED_BY_CONTRACT，不做字符串过滤 | 无数据变更，无需回滚 |
| MinIO 写入成功但数据库登记失败 | 删除本次新对象并保留本地权威文件；补偿失败进入明确告警 | 关闭新写开关，保留对象待审计清理 |
| 已迁移对象缺失或内容不一致 | `AVAILABLE` 行失败关闭且不回退本地；迁移前后校验长度和 SHA-256 | 暂停切换并按迁移清单恢复对象，不删除本地副本 |
| 迁移中断或重复运行 | 小批次、幂等状态机和断点续跑；单附件条件更新防竞争 | 停止 worker，不影响未迁移旧行继续本地读取 |

页面批次无数据库迁移、无数据回填和聚类算法发布。P1-C 复用既有 V236 字段，通过应用服务/Repository 写入对象位置并切换对象存储开关；不直接执行 SQL。验证成功的旧附件按单文件审计流程精确删除，MinIO 对象始终保留。回滚时停止新写和迁移，并切回已经完成对象与 version 追平的 MinIO endpoint；不得假设已清理的旧文件仍可作为回退源。

## 10. 明确不在本批处理

- P3-02/P3-03/P3-04 的指纹、候选、低信息终态、历史影子重分类、generation 切换。
- `ClusterDecisionModel`、`ClusteringEngine`、`ProductPhenomenonFormatter`、`ClusterLifecycleService`、Cluster Mapper/XML。
- 服务器/生产 MinIO 基础设施、bucket、凭据与 TLS 仍由目标环境提供；本机已批准的逐附件清理属于本批，服务器切换必须另按 runbook 完成 mirror、全量校验、应用层 version CAS 重绑和 endpoint 切换。
- 新增浏览器附件上传接口、新增低信息/暂缓列表接口、新增渠道列表诊断值批量接口、修改角色权限包。
- 原始日志/压缩包下载只实现第 4、7.4 节冻结的独立下载链，不复用或改写内联查看链。
- 直接 SQL、Flyway、表结构、业务模型重构、全仓 C0-C5 债务治理。
- 生产部署、真实用户操作、活动 Cluster/MAIN 修改或目标环境数据验证。

## 11. 实施与交付顺序

1. 用户已明确批准本修订版、接受第 7 节阻塞边界，并批准第 7.4 节合同/API 扩展及页面完成后的 P1-C 对象存储切换。
2. 实施 P1-B-PAGE，运行定向测试，提交一次进度摘要。
3. 实施 P2-PAGE 及已获批原始日志下载链，运行定向测试与 CSP/性能检查，提交一次进度摘要。
4. 实施 P3-PAGE 可执行部分，运行定向测试，提交一次进度摘要；低信息项继续标记阻塞，不伪造关闭。
5. 运行总回归，作者自审及独立审核视角检查实际 diff；发现方案外事实立即暂停并重新审批。
6. 生成页面阶段进度报告并更新 `docs/PROJECT_SNAPSHOT.md`，确认页面定向测试通过后进入 P1-C。
7. 实施对象存储新写入器和受控读取器，先以关闭开关状态完成单元/合同验证，再连接私有 MinIO 做真实读写验收。
8. 运行历史附件迁移的只读盘点与 dry-run；核对数量和字节数后分批迁移、哈希回读验证和断点续跑。迁移过程中保留本地源文件。
9. 完成对象存储对账后切换读取策略，运行页面、原始日志下载与全量回归；生成最终 `COMPLETION_REPORT.md`。若目标 MinIO 或受控 Git 根未提供，分别明确标记 `BLOCKED_EXTERNAL`，不得伪报部署或提交成功。

本计划及第 7.4、5.5 节均已获用户明确批准，按上述顺序实施；超出冻结边界的新业务模型、权限或破坏性清理仍须重新确认。
