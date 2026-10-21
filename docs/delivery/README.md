# 项目交付物索引

本目录按需求第 14 节逐项交付，每项一个独立文档。文档描述以当前 Kotlin/Spring Boot、Vue 3、Android SDK 和 42 表实现为准；归档版 v5/v6 方案不能替代本目录。

> 当前迭代状态、进行中改动、已知陷阱和优化队列见 [`../PROJECT_MEMORY.md`](../PROJECT_MEMORY.md)。本目录回答“交付了什么”，项目记忆回答“现在做到哪里、下一步是什么”。

1. [完整项目源代码](01-source-code.md)
2. [README 与本地运行](02-local-run.md)
3. [产品需求与功能范围](03-product-requirements.md)
4. [系统架构与四渠道数据流](04-architecture-and-channel-flows.md)
5. [数据库设计与 ER 图](05-database-and-er.md)
6. [SDK 与核心 API](06-sdk-and-core-api.md)
7. [三类表格导入](07-table-imports.md)
8. [字段映射与数据对账](08-mapping-and-reconciliation.md)
9. [脱敏规则与验证报告](09-masking-validation.md)
10. [AI 设计与效果评估](10-ai-design-and-evaluation.md)
11. [数据质量、权限与安全](11-quality-permission-security.md)
12. [测试与自动化](12-testing.md)
13. [现场 Demo 手册](13-demo-runbook.md)
14. [一期智能反馈分析与运营平台近期改动契约](14-phase-one-simplification.md)
15. [问题簇分类优化契约](15-problem-cluster-classification-optimization.md)
16. [全项目 AI 工程治理与重构合同](16-ai-engineering-remediation-governance.md)
17. [MyBatis 经典分层重构 P0 状态](18-mybatis-classic-layering-p0.md)
18. [登录主链分层重构](19-auth-login-layering-refactor.md)
19. [权限链路分层重构](20-permission-chain-layering-refactor.md)
20. [附件链路分层重构](21-attachment-chain-layering-refactor.md)
21. [应用身份域完整分层重构](22-application-identity-layering-refactor.md)
22. [旧反馈平台 Station 链路分层重构](23-station-chain-layering-refactor.md)
23. [SDK 链路分层重构](24-sdk-chain-layering-refactor.md)
24. [六域技术层目录统一治理](27-six-domain-directory-governance.md)
25. [系统功能、完成度与遗留问题交接说明](28-system-handover-status.md)
26. [RCA Agent 运行时（V242）交付说明](29-rca-agent-runtime.md)

近期一期简化开发的阶段、阻塞和下一步统一维护在[一期简化项目记忆](../PROJECT_PHASE_ONE_MEMORY.md)，不要把变化状态复制进交付契约。

需求要求回答的七个架构问题汇总在[项目关键问题回答](project-questions.md)。

## 交付状态与验收入口

| 编号 | 交付物 | 状态 | 首要验收入口 |
|---:|---|---|---|
| 01 | 源代码 | 已交付 | Gradle、npm 构建和仓库秘密扫描 |
| 02 | 本地运行 | 已交付 | 健康检查、Flyway 校验、42 表核验 |
| 03 | 产品需求 | 已交付 | 功能边界与安全不变量评审 |
| 04 | 架构与四渠道数据流 | 已交付 | 本文 Mermaid 四分支数据流图 |
| 05 | 数据库与 ER | 已交付 | 核心 ER、幂等约束和数据生命周期 |
| 06 | SDK 与 API | 已交付 | 真机/模拟上报、HMAC、receipt 进度 |
| 07 | 三类表格导入 | 已交付 | 预览、映射、幂等、旧快照、重试与回滚 |
| 08 | 映射与对账 | 已交付 | 映射版本、行级处置和批次恒等式 |
| 09 | 脱敏验证 | 已交付 | PASS 门禁、隔离、零泄漏反向检索 |
| 10 | AI 设计评估 | 已交付 | 结构校验、评估阈值、熔断降级 |
| 11 | 质量权限安全 | 已交付 | 指标、数据范围、审计与保留期 |
| 12 | 测试 | 已交付 | 自动化命令与第 15/17 节验收脚本 |
| 13 | Demo | 已交付 | 现场演示和结果记录表 |
| 26 | RCA Agent 运行时（V242 + V243 链路列 + V244 事件表 + V245 租户列） | 代码与 DDL 已交付；接入/执行两个 XXL-Job handler 已接入并跑通终态，链路标识（V243）已补且 `gradle check` 全绿，**上游证据源未接，结论止于证据不足**；候选重排（reranker）已交付但**默认关闭、未在真库上开启跑过**；领域事件已落库（V244）并接入 RocketMQ 投递，通知/索引两条投递轨可在 XXL 轮询与 broker 之间切换（`app.mq.enabled`，默认仍轮询；**broker 单节点无鉴权、生产上还没有订阅方**）；租户维度（V245）已可判定并在 `FeedbackAccessGuard` 上真的失败关闭，接入侧写进分区键的租户为现算——**但判定只落在这一处，其余 7 处 `permits(...)` 路径与投影表的列表/问题分析读路径仍不看租户，给应用身份分配租户也没有入口**（见第 12.7 节未迁移清单） | 第 11 节「运行入口与能力边界」；第 12.5 节（链路标识与假绿复测）；第 12.6 节（投递轨切换与运维前提）；第 12.7 节（租户判定链与未迁移清单）；[代码流程说明 4.1](../reference/feedback-cluster-classification-code-flow.md)（候选重排）；三个离线 Gradle 任务 + `:backend:mqIntegrationTest` + `:backend:rcaEntryTest -DRCA_ENTRY_TEST_ENABLED=true` |

“已交付”表示文档、源码或脚本已经在仓库中，不代表未配置的外部供应商已经通过生产验收。真实 LLM、物理 Milvus、邮件和飞书等依赖必须在目标环境补验，不能以降级测试冒充供应商联调成功。
