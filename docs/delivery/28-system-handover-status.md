# 系统功能与遗留问题交接说明

## 1. 文档目的与结论

本文用于 2026-09-10 的工程交接，工作区为 `/Users/yang/Feedback_platform/feedback-platform`。内容以当前源码、Flyway 迁移、自动化测试结果、专项交付文档和项目记忆为依据，不把旧需求文档、历史演示结果或尚未配置的外部服务当作当前生产验收证据。

系统已经形成可运行的 Kotlin/Spring Boot 后端、Vue 3 管理端、Android SDK、Station 兼容接入、MySQL/Flyway 权威数据层、Redis 安全辅助能力、XXL-Job 任务入口以及完整的安全、审计、脱敏、附件、AI、告警和报告代码框架。登录、权限、附件、应用身份、Station、SDK 六条核心链路已完成经典分层重构和目录统一治理。

当前结论分为三层：

1. **本地代码交付可用**：核心源码能够编译，完整 backend 单元/合同测试、独立附件集成测试、Android SDK 单测和 bootJar 构建通过。
2. **六条已重构链路完成本地收口**：专属架构门禁 48 项通过；Service/Impl、Repository/Impl、Mapper/XML、模型、任务入口和中文 KDoc 规则已固化。
3. **尚不能宣称生产整体就绪**：真实 MySQL 并发、服务器对象存储、真实 AIHub、真实旧 FBP、通知供应商、物理 ES/Milvus 和完整浏览器验收仍有缺口；完整 backend 架构测试还存在一个非六域的 Cluster 异常边界失败。

## 2. 本文范围与明确排除项

用户指定以下两个前台业务模块不纳入本文的完成度评分：

- **渠道数据查看模块**：全部渠道、学习机反馈、Jira、质量平台、红黑榜列表及反馈详情页面。
- **问题深度分析模块**：`/dashboard/analysis` 页面及其四渠道分析读模型。

这两个模块的源码和历史交付记录仍然存在，相关安全、来源事实、附件、权限和公共后端能力也会被其他模块复用；本文只是不对其业务页面完整度、字段口径和用户体验给出百分比。其专项状态分别见 `14-phase-one-simplification.md`、`25-shadow-cluster-read-only-preview.md` 和 `26-four-channel-layered-problem-analysis.md`。

问题簇的后台生成、审核、重分类、质量评估和 Shadow 管理属于平台治理能力，不等同于“问题深度分析页面”，因此仍纳入本文盘点，但会单独说明与该页面的依赖关系。

## 3. 完成度口径

完成度不是简单按文件数量计算，而是分别评估：

- **代码功能完成度**：入口、业务规则、持久化、失败处理、权限、审计和自动化合同是否存在并形成闭环。
- **生产验证完成度**：是否已在目标 MySQL、Redis、对象存储、旧系统、AI、通知、索引及真实浏览器环境完成验收。

等级解释：

| 等级 | 代码功能完成度 | 含义 |
|---|---:|---|
| A | 90%～100% | 主链完整，本地门禁充分，主要剩环境验收或运维演练 |
| B | 75%～89% | 核心功能完整，仍有外部契约、边界测试或次要闭环待补 |
| C | 50%～74% | 已有可运行代码，但关键外部链路或架构收口尚未完成 |
| D | 0%～49% | 仅有局部实现、原型或文档，不能作为完整业务能力交付 |

表中的百分比是交接用工程估算，不是产品验收比例或准确率承诺。排除渠道数据查看和问题深度分析后，第 5 节共评估 22 个模块：逐模块简单平均约为**代码功能 87% / 生产验证 63%**；考虑主业务链、公共安全能力和生产阻断项的重要性后，交接采用较保守的加权结论，即**代码功能完成度约 85% / 目标环境生产验证完成度约 61%**。主要差距集中在外部系统与真实基础设施，不是所有差距都需要继续写业务代码。

## 4. 当前已经完成的系统能力

### 4.1 多来源接入底座

平台保留五类来源事实：Android SDK、旧反馈平台 Station、Jira、质量平台和红黑榜。统一来源表保存渠道、来源业务键、事件时间、更新时间、接入时间和隐私处理状态；各渠道扩展字段保持独立，避免用一个通用 JSON 覆盖渠道业务语义。

已经具备：

- SDK HMAC 请求认证、时间窗、nonce 防重放、加密信封接收、receipt 查询、附件分块、异步物化、遥测和失败重试。
- Station V1/V2 兼容模型、RPC Provider、可靠双写任务、历史 Excel 导入、原始密文信封、附件下载、租约 Worker、幂等指纹和补偿处理。
- Jira Token 只读 API 与 Excel/CSV 双入口、Issue Key 幂等、更新时间旧快照保护、字段映射版本、附件证据拉取和全历史分窗回补能力。
- 质量平台、红黑榜的表头识别、字段映射、业务键生成、批次预览、正式导入、失败行、重试、回滚和对账。
- 每个异步链路独立状态、租约 owner、重试上限和失败分类，避免一个渠道阻断其他渠道。

### 4.2 统一隐私与脱敏链

系统已经实现从加密暂存到安全正文的统一隐私门禁：

- 手机号、邮箱、用户 ID、设备序列号等按类型指纹化或独立 AES-GCM 加密。
- 自由文本执行规则脱敏、NER 辅助、残留高风险文本检查和版本化规则治理。
- 只有 `masking_status=PASS` 的安全正文才能进入普通查询、索引、AI、归簇和报告。
- 技术失败进入独立失败分类、重试和运维路径，不允许通过降级直接放行原文。
- 当前一期已取消人工隐私复核产品流程，但“自动 PASS”仍以成功生成安全正文为前提。
- 受控原文、诊断包、身份密文和普通脱敏正文拥有不同权限与生命周期，不能相互替代。

### 4.3 登录、会话与认证

登录链已经完成分层重构，提供：

- 本地应急账号密码登录和飞书 OAuth/SSO 登录。
- OAuth state、一次性登录兑换码、Redis 不透明会话和 Cookie 安全属性。
- 会话只保存随机 ID 摘要和 TTL，不把用户身份放入浏览器持久 Token。
- 退出登录、会话撤销、账号禁用、`authVersion` 变化后的旧会话失效。
- 外部身份首次登录影子账号、稳定飞书身份绑定以及本地首位管理员引导的受控开关。
- Web 安全测试切片已恢复 Spring Boot 默认隔离规则，不会误加载全量 Mapper。

### 4.4 RBAC、数据范围和字段权限

权限链已经完成分层重构，平台裁决能力包括：

- 角色、原子权限、角色权限关联和用户角色关联。
- 渠道、功能模块、省市区域等数据范围。
- 字段级可见性与敏感诊断权限。
- 一期飞书工作区权限 `access:phase_one_workspace`。
- 管理员用户权限维护、飞书角色映射草稿/批准/拒绝/退役、权限模拟和组织同步状态。
- 高风险授权变更、查看和失败结果进入审计；越权、事实缺失或外部身份不可信时失败关闭。

### 4.5 应用身份域

应用身份域已覆盖：

- 管理员身份查询、诊断、alias 历史和来源事实批量加载。
- SDK、Station 和统一来源事实的应用身份解析。
- alias/identity 创建、稳定 code、生命周期、解析状态和分类状态。
- Shadow 事实生成、批量写入、状态推进和审核前预检。
- reviewed binding 人工审核与重绑。
- `requestId` 同命令稳定重放、异命令冲突、命令指纹和唯一键保护。
- 重绑固定执行 alias → source identity → target identity 锁序；锁后复核幂等，在同一短事务内完成双版本 CAS、旧 alias 失效、新版本写入和必须成功的审计。
- 七个生产 Mapper/XML 的语义清单已固定；第七个 SafeText Mapper 只读取 masking PASS 历史事实，不扩大身份写边界。

### 4.6 附件与安全证据链

附件链已经完成分层和安全闭环：

- SDK、Station、Jira 和导入附件的元数据、完整性、恶意软件、内容审核和存储状态。
- FBA2 客户端加密附件、服务端受控解封装、FBS1 静态加密和明文及时清理。
- 一次性授权令牌、普通查看、媒体预览和原始日志下载用途隔离。
- 授权与审计在短事务中共同提交，事务提交后才打开本地或对象存储流。
- 文件名、Content-Disposition、`no-store`、`nosniff`、CORP、CSP 和下载大小等 HTTP 安全契约。
- 图片/视频派生预览、ZIP/GZIP 流式安全检查、路径穿越、条目数、解压量、压缩比和可执行签名限制。
- 本机 MinIO 已完成历史附件迁移和旧副本清理；JSONL 审计支持删除意图闭合检查。

### 4.7 Android SDK

SDK 客户端与后端接入链已完成：

- 宿主配置、反馈上报、环境上下文、截图/日志/视频附件。
- 每应用密钥、HMAC 签名、AES-GCM 信封、RSA-OAEP 附件密钥封装。
- 弱网队列、重试、receipt 状态查询、分块上传和安全遥测。
- 服务端接收与高成本解密/脱敏/物化分离，HTTP 请求不包围大事务。
- requestId、clientFeedbackId、内容指纹、唯一键和租约共同约束幂等。
- SDK 相关 Controller、Service/Impl、Repository/Impl、Mapper/XML 和安全组件均有专属架构门禁。

### 4.8 Station 旧反馈平台链

Station 链已经具备：

- V1/V2 兼容 HTTP/RPC 数据结构和既有状态 code。
- UC/旧平台认证边界、产品上下文、手机号解析端口及失败关闭策略。
- 可靠双写任务：旧 FBP 成功后再进入新平台；新平台失败重试时不会重复调用旧 FBP。
- 历史导出文件读取、表头/行组装、业务键、旧快照保护和正式导入。
- 原始载荷 AES-GCM 保留、受控重放、隐私失败恢复和附件公网 HTTPS/SSRF 边界。
- owner 租约、过期租约恢复、有限指数退避、命令指纹冲突和批量上限。
- Controller、RPC、Job/Scheduler 只依赖 Service 契约，复杂访问通过 Repository/Impl/Mapper。

### 4.9 导入、对账和接入监控

后台数据接入管理提供：

- 文件上传、模板识别、预览、映射、批次创建和正式执行。
- 行级新增、更新、重复跳过、旧快照跳过、失败和待处理分类。
- 业务键与行指纹幂等，批次恒等式和来源/安全正文/归簇数量对账。
- Worker 公平领取、租约、失败恢复、重试和回滚。
- Jira、质量、红黑榜差异化字段与映射版本。
- 接入运维查询、Station/SDK 重试入口、技术失败和死信查看。

### 4.10 AI 分类、复核与提示词治理

系统已经存在完整的 AI 工程框架：

- 版本化 Prompt/Skill 资产、发布、回滚、哈希和调用审计。
- 严格 JSON Schema 输出、字段白名单、成本、Token、延迟和模型请求号记录。
- AI 分类、摘要、严重度和置信度结果。
- AI 复核列表、单条/批量确认或修正以及质量评估。
- 真实模型不可用时，接入、MySQL 查询、确定性统计和保守规则仍可继续。
- AI 输入只能使用通过隐私门禁的安全事实。

当前代码能力完整，但真实 AIHub Responses 调用仍被网关 HTTP 400 阻断，因此不能声称真实模型已经联调成功或达到生产准确率。

### 4.11 问题簇后台治理（不含问题深度分析页面）

后台已实现：

- 程序候选召回、全文/向量/错误签名/结构化证据组合。
- AI 辅助决策、确定性降级、新建簇或关联已有簇。
- MAIN link、问题簇生命周期、人工审核、解除、合并、拆分和重分类。
- Shadow generation、只读预览、评估、preflight 和应用命令。
- 归簇质量指标、标注集、红黑样本和历史决策证据。
- 任务租约、有限重试、版本 CAS、唯一关系和幂等命令账本。

该域尚未完成与六域相同等级的全链分层重构，完整架构门禁唯一失败也位于此域，详见遗留问题章节。

### 4.12 告警、通知和事件治理

系统具备：

- 告警规则、规则测试、优先级、抑制、生命周期和事件实例。
- 升级链、值班策略、通知 outbox、投递尝试、回执、死信和补偿。
- 站内通知、邮件/飞书等供应商适配边界。
- 外部安全事件与通知供应商回执入口。
- 告警中心、规则管理和运维健康信息。
- 任务分发、提醒、催办等场景的站内通知事实。

代码和 MyBatis 合同存在，但真实邮件、飞书和目标环境调度投递未完成统一生产验收。

### 4.13 AI 报告、RCA 与知识治理

系统已经提供：

- 报告列表、问题簇详情及六类报告相关事实。
- RCA 生成、审核、证据引用、知识候选和采纳状态。
- PDF 导出、报告任务、版本和失败重试。
- Jira 知识候选、技能资产和知识过期追踪。
- 报告、附件、来源和敏感字段的数据范围控制。

真实 LLM、CJK PDF 字体、目标对象存储和生产数据集仍需环境验收，因此当前更适合视为“功能代码可用、生产效果待验”。

### 4.14 需求反馈、任务与渠道验收工作台

除被排除的渠道查看页面外，平台还保留：

- 用户提交产品需求/数据问题，保存页面上下文、关联反馈和附件元数据。
- 管理员/运营维护状态、优先级、计划版本、负责人和完成说明。
- 提出人验收确认或退回，事件只追加不覆盖。
- 我的任务、待接收、已完成、处理状态、分发状态和经办时间线。
- 渠道验收结论、字段配置草稿/发布/历史版本与回滚式重发。

这些能力属于原扩展产品范围，当前一期主目标并不以它们为上线前置条件；审核 SLA 在开发阶段仍关闭，不能把 due/overdue 当作已生效生产能力。

### 4.15 系统管理、配置与审计

系统管理当前包括：

- 用户、角色、权限和数据范围管理。
- 飞书角色映射和组织同步治理。
- 脱敏规则查看、测试、编辑与版本治理。
- 告警规则和值班相关配置。
- 动态配置目录、版本替换、历史和回滚；写开关默认关闭。
- 系统审计、业务审计、HTTP 审计队列和完整性检查。
- 应用身份管理、归簇评估、Shadow 预览和死信管理入口。

审计只保存稳定动作、对象、主体、结果和允许的元数据，不记录密钥、Token、正文、原始路径或对象键。

### 4.16 MyBatis、数据库迁移与事务治理

数据访问已经从旧混合方式迁移到 MyBatis 经典分层：

- 生产 Mapper 位于 `mapper/<domain>`，XML namespace、文件名和接口一一对应。
- PO 位于 `model/po/<domain>`，全目录门禁要求一文件一个同名 `*Po` 类型。
- 普通领域事实位于 `model/<domain>`，不再塞入 `entity` 或 PO 目录。
- ServiceImpl 禁止直接连接 Mapper；RepositoryImpl 禁止反向调用内部 Service。
- XML 禁止 `SELECT *`、`${...}`、字符串拼接 SQL；重点链路限制 JOIN/EXISTS 和无界集合。
- 事务按本地一致性阶段切分；HTTP、RPC、Redis、文件、消息、加解密和高成本组装不进入长事务。
- 当前迁移文件最高版本为 V241，共 240 个 Flyway SQL 文件。环境中的实际表数和 schema 版本必须以目标库 Flyway 校验结果为准。

### 4.17 调度、可观测性和运维底座

平台使用 XXL-Job 作为唯一任务调度入口，当前 manifest 与 45 个 handler 一一对应。任务覆盖导入、Jira、SDK、Station、脱敏、AI、归簇、索引、报告、附件和维护补偿。

已经具备：

- MyBatis statement 和 Repository 方法耗时指标。
- 慢 statement、影响行数、deadlock、lock timeout 和 SQL 错误分类。
- Hikari、Actuator、MDC requestId 和受控审计关联。
- 死信、过期租约恢复、重试、对账和一致性修复任务。
- 本地安全启动脚本、Keychain 密钥读取和健康检查。

XXL-Job 多节点调度、Prometheus 指标序列、真实慢查询、锁等待和超时策略仍需目标环境验证。

## 5. 排除两个业务页面后的模块完成度

| 模块 | 代码功能 | 生产验证 | 状态 | 主要未闭合项 |
|---|---:|---:|---|---|
| 登录与会话 | 96% | 82% | A | 目标域名 Cookie/TLS、正式飞书租户与应急账号演练 |
| RBAC、数据范围、字段权限 | 94% | 78% | A | 正式组织同步最小权限、离职/停用真实演练 |
| 应用身份 | 92% | 62% | A | 专用 MySQL 迁移与并发重绑合同未在当前环境执行 |
| 附件与安全证据 | 93% | 72% | A | 服务器私有桶/KMS、慢速大文件浏览器复验 |
| Android SDK 与服务端接入 | 91% | 68% | A | 专属真实 MySQL 并发、更多宿主真机和生产密钥托管 |
| Station 实时/历史链 | 88% | 56% | B | 真实旧服务 UC/Dubbo 流量、旧 FBP 合成身份 HTTP 500 |
| Jira 接入与证据 | 89% | 76% | B | 正式 Jira 契约/Token、删除归档语义和增量运行验收 |
| 质量/红黑榜文件接入 | 90% | 77% | A | 目标数据样本持续验收、来源表头变化演练 |
| 导入、回滚与对账 | 89% | 74% | B | 真实大批次容量、锁等待和故障恢复演练 |
| 隐私脱敏与安全正文 | 91% | 70% | A | 生产 KMS、正式标注集复核、真实 NER 运行指标 |
| AI 分类与复核 | 80% | 38% | B | AIHub HTTP 400、真实模型效果/成本/熔断验收 |
| 问题簇后台治理 | 76% | 50% | B | Cluster 全链重构、异常边界门禁、真实并发合同 |
| 告警与通知 | 79% | 46% | B | 邮件/飞书供应商、XXL 调度和回执补偿实测 |
| 报告、RCA 与知识 | 77% | 43% | B | 真实 LLM、CJK 字体、生产数据效果和 PDF 目视验收 |
| 需求反馈/任务/验收 | 81% | 60% | B | 当前一期启用范围、SLA 开关和完整业务现场验收 |
| 系统管理与动态配置 | 89% | 69% | B | 生产写开关、回滚演练和高风险双人复核流程 |
| 审计与安全事件 | 90% | 70% | A | 目标环境长期完整性、留存和告警演练 |
| ES/Milvus 派生索引与搜索 | 77% | 39% | B | 物理集群、反向零泄漏扫描、容量和降级恢复 |
| XXL-Job、维护与可观测性 | 86% | 55% | B | 多节点调度、Prometheus、慢查询和锁指标实测 |
| MyBatis/Flyway 数据底座 | 93% | 66% | A | 专用 MySQL 全合同、执行计划、迁移备份恢复演练 |
| 前端公共框架与管理页面 | 85% | 64% | B | 当前改动后的完整浏览器矩阵、可访问性和视觉回归 |
| 测试、交付和运维文档 | 91% | 68% | A | 外部环境证据、文档数字口径统一和 Git 可回滚基线 |

未评分：渠道数据查看模块、问题深度分析模块。

## 6. 最近一次可复现验证结果

2026-09-10 在当前工作区完成：

| 验证 | 结果 |
|---|---|
| 六域定向 backend 测试 | 通过 |
| 六域专属 architectureTest | 48 项通过 |
| 完整 `:backend:test --rerun-tasks` | 1403 项通过，45 项按环境条件跳过 |
| 独立 `:backend:integrationTest --rerun-tasks` | 8 项执行通过，1 项环境跳过 |
| `:backend:bootJar --rerun-tasks` | 通过 |
| Android `:sdk:testDebugUnitTest --rerun-tasks` | 通过 |
| `:rpc-api:test --rerun-tasks` | 任务通过；当前测试源码为 `NO-SOURCE` |
| 完整 `:backend:architectureTest --rerun-tasks` | 79 项中 78 项通过，1 项失败 |

> **后续更新（2026-09-29）**：上表两行的数字已变化，原记录保留不改。
> `architectureTest` 现为 **79 项全部通过、0 失败**：下面列出的那条
> `ExceptionBoundaryArchitectureTest` 失败**已修复**（该类现 6 项 0 失败），
> 连同 2026-09-28 那条记录里余下的失败项**同批共 4 项**一起修掉，`gradle check`
> 因此由红转全绿（该门禁经 `tasks.check dependsOn(architectureTest)` 影响整仓，不是局部）。
> `:backend:test` 现为 **1431 项通过、45 项环境跳过**——1431 与上表的 1403 之间隔了多次改动，
> 不是同一批用例的计数口径变化。修复明细见 `docs/delivery/29-rca-agent-runtime.md` 第 13 节。

完整架构测试唯一失败（**已于 2026-09-29 修复，以下为当时的原始记录**）：

```text
ExceptionBoundaryArchitectureTest
ApplicationSymptomDryRunService.summarize
→ java.lang.IllegalStateException
```

该失败属于 Cluster 域既有异常边界，不属于已经完成分层收口的六域。六域目录、KDoc、依赖方向、事务、Mapper/XML、PO、Job 和批量门禁均通过。

当前 45 个 backend 环境跳过项包括真实 MySQL、MinIO、Milvus、ONNX 和依赖外部文件/服务的测试。与当前核心链直接相关的跳过项包括：

- `ApplicationIdentityRebindMysqlTest`
- `ApplicationIdentityMigrationMysqlTest`
- `AttachmentObjectStorageMigrationMysqlTest`
- `AttachmentObjectStorageMinioIntegrationTest`

这些测试的代码存在，但当前没有专用连接参数，因此没有用 mock 冒充真实环境通过。

## 7. 遗留问题与关闭条件

### 7.1 P0：交接后应优先处理

#### P0-1 Cluster 架构门禁未全绿

- 现象：完整 architectureTest 唯一失败为 `ApplicationSymptomDryRunService.summarize` 直接构造 `IllegalStateException`。
- 影响：全仓架构总门禁无法全绿；Cluster 的异常分类与 HTTP/Job 失败语义不统一。
- 关闭条件：完成 Cluster 完整调用链盘点，将业务异常转换为稳定平台异常类型，补专属 Cluster 架构门禁，并重跑完整矩阵。
- 注意：修复不能只替换一个异常来求绿；应同时审查 Cluster Service/Repository 聚合文件、事务、锁/CAS、Worker 和批量边界。

#### P0-2 真实 AIHub 调用被 HTTP 400 阻断

- 现象：平台专属 Key/模型已注入，但普通 Responses API 最小请求仍被 Transit 网关无明细拒绝。
- 影响：AI 分类、辅助归簇、RCA、知识与报告只能证明代码和降级链，不能证明真实模型效果。
- 关闭条件：AIHub 提供必需认证头/请求字段、确认 Key 权限，或提供脱敏成功 curl；随后完成真实调用、Schema、超时、熔断、成本和评测验收。

#### P0-3 Station 真实旧 FBP 双写未闭合

- 现象：对合成测试身份的旧 FBP 提交返回 HTTP 500；本机没有完整运行中的旧 `study-station-server` UC/审核环境。
- 影响：代码和隔离测试能够证明可靠双写状态机，但不能证明首批真实业务流量。
- 关闭条件：旧 FBP 提供获准测试用户/产品组合及可诊断错误，完成 UC、Dubbo、旧 FBP、新平台 MySQL 的端到端验证和对账。

#### P0-4 关键真实 MySQL 并发合同缺少当前环境证据

- 现象：应用身份重绑合同已编写，但开关和专用数据库凭据未提供；Station 没有专属真实并发合同，SDK 上报幂等并发覆盖也不完整。
- 影响：锁顺序、唯一键、CAS 和幂等已由代码/单测/XML 合同证明，但未形成当前 MySQL 版本下的竞争证据。
- 关闭条件：仅使用固定测试库 `feedback_platform_test`，运行应用身份、SDK、Station 和附件迁移合同，保存通过/失败/跳过数量与数据库版本。

### 7.2 P1：生产部署前必须关闭

#### P1-1 对象存储与密钥托管尚未完成服务器切换

- 本机 MinIO 迁移已闭环，但服务器 endpoint、TLS、最小权限凭据和 KMS/Secret Manager 未提供。
- 关闭条件：私有桶、TLS、凭据轮换、读写/回滚、生命周期和灾备演练全部通过。

#### P1-2 慢速大附件仍需部署后浏览器复验

- 历史问题表现为浏览器固定超时、Blob 全量缓冲、无进度和服务端 Broken pipe。
- 后续代码已改为显式取消、立即展示进度、返回权威大小并保留压缩扩展名，但最新方案尚未完成部署后的 Safari/Chrome 大文件目视复验。

#### P1-3 外部通知供应商未完成真实闭环

- 邮件、飞书、供应商回执和升级链存在代码与 outbox，但没有目标环境完整投递、超时查询、幂等回执和补偿演练。

#### P1-4 ES/Milvus/ONNX 物理环境证据不足

- MySQL 是权威源，索引降级不会阻断接入和普通查询；但物理 Milvus 反向零泄漏扫描、真实召回效果、容量、恢复和 ONNX 模型部署仍待补验。

#### P1-5 生产数据库性能与恢复能力未验收

- 尚缺目标数据量下的 EXPLAIN、慢查询、连接池、锁等待、死锁、statement timeout、备份恢复和 Flyway 回滚预案证据。

#### P1-6 完整前端浏览器矩阵未随最新后端重构重跑

- 当前后端与 SDK 自动化证据是最新的；前端历史 quick 检查曾通过，但本次六域目录治理没有重新执行完整浏览器目视、无障碍和所有外部文件下载场景。

### 7.3 P2：工程治理与文档债务

#### P2-1 根工程缺少可用 Git 变更基线

- 当前工作区不能依赖 `git status/diff`，存在用户及前序重构改动。
- 影响：无法提供精确提交级变更清单和一键回滚点。
- 关闭条件：在不覆盖现有修改的前提下建立可审计仓库基线、提交记录和发布标签。

#### P2-2 顶层 README 存在结构和数字漂移

- README 仍描述旧 `entity`/JPA 目录、旧迁移范围及不同的物理表数量。
- 当前源码已经使用 Mapper/Repository/Model/PO 分层，迁移最高为 V241。
- 关闭条件：以实际源码和目标数据库 Flyway 校验统一 README、数据库文档和表数量口径。

#### P2-3 Kotlin/Java 依赖存在弃用告警

- 编译包含 Kotlin 注解默认目标变化、Jackson `fields()`、MyBatis XML builder、Milvus API 和 Spring `@MockBean` 等弃用提示。
- 当前不影响构建，但升级 Kotlin、Spring Boot、MyBatis 或 Milvus SDK 前应集中治理。

#### P2-4 OpenAPI 展示能力不完整

- DTO 中已有部分 Schema 契约，但当前项目未启用 Swagger UI；真实 API 契约仍以 Controller、DTO、安全矩阵和测试为准。

## 8. 环境、秘密和运行前置条件

### 8.1 必需基础设施

- JDK 17、Gradle 8.14.5。
- MySQL 8，必须使用 Flyway 从干净库或已知版本迁移到 V241。
- Redis 6+，用于会话、nonce 和运行辅助状态。
- 私有附件目录或 S3/MinIO 私有桶。
- XXL-Job 调度中心；未配置时任务 handler 只能证明可装配，不能自动运行。

### 8.2 可选但影响完整能力的外部系统

- 飞书 OAuth 和组织目录。
- 旧 FBP、UC、ZooKeeper/Dubbo。
- Jira 只读 API。
- AIHub/OpenAI 兼容 Responses API。
- 邮件、飞书通知供应商。
- Elasticsearch、Milvus、BGE-M3 ONNX。
- ClamAV、媒体处理工具和 CJK PDF 字体。

### 8.3 不能进入仓库的秘密

- MySQL/Redis 密码。
- SDK 每应用 secret、RSA 私钥。
- 指纹、Station 原文、附件存储和诊断包密钥。
- 飞书、Jira、AIHub、邮件、对象存储凭据。

生产必须通过 KMS/Secret Manager 或等价机制注入，并完成密钥版本、轮换和旧密文读取演练。

## 9. 建议的后续工作顺序

1. **建立可回滚 Git 基线**：保存当前可验证状态，避免继续在不可追踪工作区扩大修改。
2. **完成 Cluster 链完整重构**：关闭唯一 architectureTest 失败，并统一 Service/Impl、Repository、Worker、异常和事务边界。
3. **建立专用 MySQL 验收环境**：运行应用身份、SDK、Station、附件和迁移/并发合同。
4. **关闭 AIHub 与旧 FBP 外部阻塞**：取得真实契约与获准测试身份，不使用生产真实用户试探。
5. **完成对象存储/KMS 服务器验收**：包含 TLS、最小权限、轮换、回滚和慢速下载浏览器测试。
6. **完成通知、XXL、ES/Milvus 运行验收**：保存真实回执、指标、降级和恢复证据。
7. **统一 README 与数据库数字**：避免新维护者继续按旧 JPA/entity 结构理解系统。

渠道数据查看和问题深度分析若后续重新进入范围，应单独按其字段、口径、权限和页面验收方案推进，不应与上述基础设施收口混为一次大改。

## 10. 常用验收命令

从项目根目录执行，Gradle 需要可写的 `GRADLE_USER_HOME` 和本机文件锁通信：

```bash
# 六域专属架构矩阵
./gradlew :backend:architectureTest \
  --tests com.feedback.architecture.MigratedServicePackagePurityArchitectureTest \
  --tests com.feedback.architecture.StationChainArchitectureTest \
  --tests com.feedback.architecture.SdkChainArchitectureTest \
  --tests com.feedback.architecture.ApplicationIdentityChainArchitectureTest \
  --tests com.feedback.architecture.AttachmentChainArchitectureTest \
  --tests com.feedback.architecture.AuthLayeringArchitectureTest \
  --tests com.feedback.architecture.PermissionChainArchitectureTest

# 完整后端和独立集成测试
./gradlew :backend:architectureTest
./gradlew :backend:test
./gradlew :backend:integrationTest
./gradlew :backend:bootJar

# Android SDK 与 RPC API
ANDROID_HOME=/path/to/android-sdk ./gradlew :sdk:testDebugUnitTest
./gradlew :rpc-api:test

# 前后端日常检查
../tools/dev-check.sh --quick
../tools/dev-check.sh --full
```

判断规则：任何 `FAILED`、`BLOCKED_EXTERNAL`、`NO-SOURCE` 或 `skipped` 都要按真实含义记录；不能用 mock、XML 解析或任务成功状态替代真实 MySQL、浏览器或外部供应商验收。

## 11. 关键交接文档入口

- 总体架构：`docs/delivery/04-architecture-and-channel-flows.md`
- 数据库与 ER：`docs/delivery/05-database-and-er.md`
- SDK 与 API：`docs/delivery/06-sdk-and-core-api.md`
- 导入：`docs/delivery/07-table-imports.md`
- 脱敏：`docs/delivery/09-masking-validation.md`
- AI：`docs/delivery/10-ai-design-and-evaluation.md`
- 权限与安全：`docs/delivery/11-quality-permission-security.md`
- 测试：`docs/delivery/12-testing.md`
- 对象存储切换：`docs/delivery/17-attachment-object-storage-cutover.md`
- 六域分层交付：`docs/delivery/19-auth-login-layering-refactor.md` 至 `24-sdk-chain-layering-refactor.md`
- 六域最终目录治理：`docs/delivery/27-six-domain-directory-governance.md`
- 当前一期易变状态：`docs/PROJECT_PHASE_ONE_MEMORY.md`

交接时应先阅读本文和 `27-six-domain-directory-governance.md`，再按具体模块进入专项文档；不要用归档需求覆盖当前代码事实。
