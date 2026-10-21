# RCA Agent 运行时（V242）交付说明

> 日期：2026-09-28（Asia/Shanghai）
> 迁移：V242（本曲线之后唯一新增的迁移，前序为 V241）
> 代码位置：`backend/src/main/kotlin/com/feedback/agent/rca/`（54 个 Kotlin 文件，6 个子包）
> 状态：**接入与执行两侧的生产入口已接入（XXL-Job handler），并在本机真库上跑到终态落库；
> 五个上游证据源按契约降级，因此结论停在 `PARTIAL`，不构成「RCA 已上线」**（见第 11 节）。
> 另：三条投递轨可在 XXL 轮询与 RocketMQ 之间切换（默认仍轮询），
> RCA 领域事件已具备「落库 + 发到 MQ」的完整链路（见第 12.6 节）

## 1. 文档目的与结论

本文补记此前交付物索引里**没有覆盖**的一块：`agent/rca` 包与 V242 迁移实现的
问题对象会话化（Episode/WorkItem）与 Agentic 根因分析运行时。

在本文之前，`docs/` 全目录对 `rcaagent` 的引用为 **0**——这块代码已进仓库，但没有任何交付文档
描述它。本文是**纯补充**，不修改任何既有交付物的结论。

结论分三层，这三层必须分开陈述，不能合并：

1. **领域层已交付且自洽**。五节点状态图、14 档工具结果状态、七类只读工具、
   Episode/WorkItem 会话化、五档归因分级、六块共享状态与三档预算、租约 fencing、
   知识沉淀顺序控制，都有明确的代码与 DDL 落点，且都在离线回放里跑通。
2. **离线回放证据齐全**。三条离线入口（回放、故障注入、会话化）共 1,405 项断言全通过；
   聚类影子套件 74 项全绿。数字见第 12 节。
3. **生产入口已接入（2026-09-28 补做）**。接入侧与执行侧各有一个 XXL-Job handler，
   执行层（状态图 / Worker / 工具层）已在 Spring 容器内装配（`config/RcaRuntimeConfiguration`）。
   本机真库（3307）上已实测：接入 743 个来源 → 743 个待执行任务；执行侧领取 5 个任务 → 全部到达
   `PARTIAL` + `LOW_INFORMATION_GAIN` 终态、`state_version=3`、租约已释放。
   详见第 11 节与第 12 节。
4. **领域事件与投递轨（2026-10-09 补做）**。领域事件先落库（V244），再由中继发到 RocketMQ
   主题——**本进程不订阅该主题**，它是给进程外订阅方用的。通知与索引两条既有发件箱的投递
   也可以从 XXL 轮询切到 broker 消费者（`app.mq.enabled`，默认仍走轮询），
   切换后轮询入口空转。本机真 broker（单节点、无鉴权）上端到端实测 7 项全绿，
   回归 `test` / `architectureTest` / `rcaEntryTest` 全绿。详见第 12.6 节。
   **这仍不是「已上线」**：没有生产订阅方，broker 没有集群与鉴权，死信路径未跑过。
5. **租户维度可判定（2026-10-09 补做）**。V245 把 `tenant_key` 从一个没人判定的列
   变成有取值域（`t_auth_tenant` 注册表）、有权威锚点（`t_application_identity.tenant_key`）、
   有判定方法（`DataAccessScope.permitsTenant`）与一个真的按它失败关闭的边界
   （`FeedbackAccessGuard`）的维度；接入侧写进分区键的租户也是现算的。
   **但这不是「全仓已租户隔离」**：判定只落在那一处，另有 7 处 `permits(...)` 路径与
   列表/问题分析投影读路径仍不看租户，给应用身份分配租户也没有入口。详见第 12.7 节，
   那里的「未迁移清单」是这一段话的必要补充。

**仍然不能从本文得出「RCA Agent 已上线」或「已产出生产根因」的结论。** 理由是能力边界而非入口缺失：
五个上游证据源（LogTrace / VersionCohort / ServiceHealth / CrashAnr / ContentStatus）
在本仓库**没有数据落点**，只能按契约降级，因此真实运行下的结论只能是「证据不足」。
入口通与结论有用是两件事，第 11.4 节把这条边界写死。

## 2. 本文范围与明确排除项

**纳入**：`agent/rca/**`、V242 迁移新建的 11 张表、V243 加的两个链路列、V244 的事件表、
V245 的租户列与注册表（第 12.7 节，**连同它改到的 auth 侧文件**：
`FeedbackAccessGuard` / `DataPermissionService` / `AdminManagementService` 与两个 mapper）、
`backend/build.gradle.kts` 中的 `JavaExec` 任务（三个离线入口 + `flywayMigrateLocalDeploy`）、
`config/RcaEpisodeConfiguration.kt`、`com/feedback/messaging/**`（通道层），
以及为投递轨切换而改动的通知/索引两条既有链路（第 12.6 节）。

**排除**：

- 既有的**簇级 RCA 报告**（`controller/report/ReportController.kt` 的 `/cluster/{id}/rca`）。
  它是报告读模型，与本包的 Agent 运行时**没有调用关系**，不要混为一谈。
- 既有告警、通知、索引同步链路的**业务语义**。第 12.6 节只改了它们的**投递路径**
  （XXL 轮询 ↔ broker 消费者），落库、幂等、重试口径一个都没动；
  三条 handler 的幂等性依赖的唯一键仍是原来那些。
- **真实 LLM 供应商联调（但生产适配器已存在，只是只验了降级路径）**。
  本包的上游模型经 `InvestigationModel` 端口注入：离线回放用脚本化实现
  （`replay/ScriptedInvestigationModel.kt`），生产用 `model/LlmInvestigationModel`，
  后者复用既有的 `service/ai/OpenAiCompatibleLlmClient` 传输层（不回写、不重指既有四个方法）。
  **本文不含一次真实模型调用**：第 12.3 节的降级测试验的是
  `LLM_NOT_CONFIGURED` / 超时 / 结构不合规三条**失败**路径，
  全部经 MockWebServer 且断言了请求确实发出（或确实没发出）。
  即「模型接得上」有证据，「模型答得好」没有证据——这两件事不能互相代替。

## 3. 数据模型（V242 建表，V243 加链路列）

`backend/src/main/resources/db/migration/V242__episode_work_item_and_rca_agent_runtime.sql`
（441 行）新建 11 张表，并对既有 `t_ai_task` 做两处 `ALTER`（加 `lease_generation`、`state_version`）。

| 表 | 作用 |
|---|---|
| `t_feedback_episode` | 问题对象（会话）：会话化分区 + 策略版本 |
| `t_feedback_episode_member` | 会话成员，**append-only** |
| `t_feedback_work_item` | 工作项：可执行单元 |
| `t_rca_agent_task` | Agent 任务：状态快照 + 租约 + 预算 |
| `t_rca_agent_step` | 单步执行记录 |
| `t_rca_tool_call` | 工具调用记录 |
| `t_rca_evidence` | 证据账本 |
| `t_rca_hypothesis` | 假设集 |
| `t_rca_mandatory_evidence` | 强制证据清单 |
| `t_feedback_episode_attribution` | 归因结论，**revision 追加式** |
| `t_rca_claim_evidence` | 逐对 claim-evidence 判定 |

**分区键**：`t_feedback_episode` 的唯一键是
`uk_episode_partition (tenant_key, app_id, problem_key, identity_fingerprint, generation)`。
其中 `tenant_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT'`。
**2026-10-09 起租户不再只是预留位**：V245 补上了取值域与判定链（第 12.7 节），
接入侧写进来的是**现算**的租户（来源 → 主问题事实 → 应用身份，未解析时退到 `'DEFAULT'`），
不再是写死的常量。**但要说清强度**：判定目前只落在 `FeedbackAccessGuard` 一处，
其余读取路径仍不看租户，所以这是「租户维度可判定、且在一个失败关闭的边界上真的判了」，
不是「全仓已经租户隔离」——第 12.7 节的未迁移清单是这一节的必要补充。

**修订语义**：`t_feedback_episode_attribution` 的 `attribution_revision` 是追加式，
即归因改动不覆盖历史，每次修订留一行。`chk_episode_attribution_level` 约束合法等级。

**V243（2026-09-29 新增，链路标识）**：`V243__rca_trace_correlation.sql` 只加列，
**不动任何既有行的取值**。

| 表 | 加什么 |
|---|---|
| `t_rca_agent_task` | `trace_id CHAR(32) DEFAULT NULL`，约束 `chk_rca_agent_trace`（`^[0-9a-f]{32}$`） |
| `t_rca_tool_call` | `trace_id CHAR(32)` / `span_id CHAR(16)`，三个格式约束 + `chk_rca_tool_trace_span_pair` + `idx_rca_tool_trace (trace_id, span_id)` |

四条口径（第 5 节与第 13 节未决项 6 的展开，验证见 12.5）：

- **历史行不回填**。两列都 `DEFAULT NULL`、都不加 `NOT NULL`，迁移里没有任何 `UPDATE`。
  「这一行产生于加列之前」与「这一行的链路标识确实是 NULL」在库上分不开，
  但这两件事对读侧是同一件事：**没有可关联的链路**。编一个值出来才会把两者永久混同。
- **两列同进同出**。`chk_rca_tool_trace_span_pair` 是
  `(trace_id IS NULL) = (span_id IS NULL)`：只有 span 没有 trace 的行没有任何读法。
- **`t_rca_agent_step` 刻意不加**（未决项 4：它今天没有写入方）。往一张空表上加列，
  只会让「有链路」看起来比实际更完整。
- **这两列只写不读**。工具调用记录的程序侧读视图 `listByTask` **刻意不选它们**：
  它的产物是恢复判定的输入，而链路标识是排障用的调度信息；读进 `RcaSharedState`
  会改变快照的字节（回放与故障注入套件逐字节断言快照相等）。按链路查调用记录直接打表，
  走 `idx_rca_tool_trace`。

## 4. 五节点状态图

**契约**：`domain/RcaGraphContract.kt`。节点序是枚举常量，不是散落在代码里的字符串：

```kotlin
val FORWARD_ORDER: List<RcaNode> = listOf(
    CONTEXT_BUILD, BASELINE_EVIDENCE, AGENT_INVESTIGATE, ROOT_CAUSE_ARBITRATE, RCA_PERSIST
)
```

**编排**：`graph/RcaStateGraph.kt`。五个节点各自实现 `graph/RcaNodeHandler.kt`，
分别落在 `ContextBuildNode` / `BaselineEvidenceNode` / `AgentInvestigateNode` /
`RootCauseArbitrateNode` / `RcaPersistNode`。

**回退边**：`RcaFallbackEdge`（同契约文件）。仲裁失败时退回 `AGENT_INVESTIGATE` 重查，
上限 `MAX_ARBITRATE_REVISIONS = 2`；超过上限不再重试，降级为 `PARTIAL` / `NEEDS_HUMAN`。

**为什么只有中间一环交给模型**：前后四节点都是确定性的——上下文构建与基线证据来自库，
落库是纯写操作且顺序有硬约束（见第 10 节）。把模型限制在 `AGENT_INVESTIGATE` 一环，
是为了让「失败时能整次回退到规则结果」成为可能：模型环节之外的任何一步都不依赖它的输出。

## 5. 工具层

**注册表**：`tools/RcaToolRegistry.kt`。构造时校验 `RcaToolName` 的每个枚举成员都有实现，
漏注册在**启动时**就失败，而不是在第一次调用时。

**七类工具**（`domain/RcaToolContract.kt` 的 `RcaToolName`），全部继承 `tools/ReadOnlyRcaTool.kt`——
**只读是类型层面的约束，不是约定**：

| 工具 | 文件 |
|---|---|
| 日志检索 | `tools/LogTraceTool.kt` |
| 版本队列 | `tools/VersionCohortTool.kt` |
| 崩溃/ANR | `tools/CrashAnrTool.kt` |
| 历史案例 | `tools/HistoricalCaseTool.kt` |
| 发布变更 | `tools/ReleaseChangeTool.kt` |
| 服务健康 | `tools/ServiceHealthTool.kt` |
| 内容状态 | `tools/ContentStatusTool.kt` |

**工具结果状态共 14 档**（`ToolResultStatus`，`RcaToolContract.kt:13`）：
`SUCCESS_WITH_DATA`、`SUCCESS_EMPTY`、`NOT_FOUND`、`INVALID_LOG_ID`、`FORBIDDEN`、
`UPSTREAM_TIMEOUT`、`SCHEMA_INVALID`、`PARTIAL`、`STALE`、`INSUFFICIENT_SAMPLE`、
`NO_VERIFIED_CASE`、`RESOURCE_MISSING`、`SYMBOL_MISSING`、`UNKNOWN`。

粒度是刻意的：`SUCCESS_EMPTY`（上游正常但没有数据）与 `NOT_FOUND`（该对象不存在）
必须分开，否则「查了没有」和「查不到」会被下游当成同一件事。
同理 `INSUFFICIENT_SAMPLE` 让「样本不够，不能下结论」成为一个**可传递的状态**而不是异常。

**上游端口**：`tools/RcaUpstreamPorts.kt`。真实上游连接器**不在本仓库**；
本仓库只定义只读端口，离线回放用 `replay/ReplayUpstreamPorts.kt` 注入。

**调用记录与链路标识**（V243）：全部工具调用的唯一出口是
`tools/ToolInvocationGuard.kt`，写入点是**两阶段**——`begin` 在发起上游请求**之前**
落一条 `PENDING` 行，`commit` 在拿到结果后推到 `COMMITTED`（见未决项 2 的展开）。
行的两列链路标识由调用方从**任务行**原样透传下来（`RcaWorker` →
`RcaNodeContext.traceId` → `guard.invoke(request, state, traceId)`），
**没有任何一层就地生成**：

- `trace_id` 只在**领取**时补一次（`RcaTaskLeaseMapper.xml` 的 `claim`，
  `IFNULL(trace_id, <32 位小写十六进制>)`），与 `deadline_at` 同一个形状、**理由不同**：
  那个挡的是超时上限被重试反复续期，这个挡的是同一个任务跨多次重试变成几条互不相干的链路。
  领取时补而不是在 Worker 侧生成，是因为任务可能被别的 Worker 接管续跑——
  各自生成会把一次执行劈成两段。
- `span_id` 由**四要素确定性推导**（`ToolSpanIdFactory`：
  `sha256(traceId:toolName:paramsDigest:decisionRound)` 取前 16 位）。
  不用随机值：工具调用的去重键就是「任务 + 工具 + 参数摘要 + 轮次」，
  它定义了什么叫「同一次逻辑调用」；随机的 span 会让同一次逻辑调用在链路里出现两次。
  这条纪律与 `EvidenceIdFactory` 是同一条。
- 两列为空时**不许兜底**：`traceId == null` 时 `span_id` 也为 null，
  于是任务行与调用行上的 NULL 是同一件事。

## 6. Episode / WorkItem 会话化

**意图**：把散落在各渠道的反馈记录，按「同一个问题」聚成可跟踪的对象，
而不是让每条记录各自为政。

**装配**：`config/RcaEpisodeConfiguration.kt`（`@Configuration`）显式声明三个 Bean：

- `ContactFingerprintPort`：指纹口径**委托给既有的** `IdentityFingerprintService`。
  装配处注释写明了理由——同一个账号在会话化与在 `t_feedback_identity` 里必须是同一个指纹，
  共用实现是唯一能保证这件事的办法；**不在这里实现第二遍 HMAC**。
- `EpisodeSessionPolicy`：策略是版本化对象，落库到 `t_feedback_episode.policy_version`。
  显式在装配处给出，避免有人内联一个 `Duration.ofMinutes(30)`，
  于是「这行数据是哪个策略版本算出来的」再也答不上来。
- `EpisodeIdentityResolver`、`EpisodeSessionizer`：无状态纯计算对象。

`episode/EpisodeSessionAdapter.kt`（`@Component`）与 `episode/EpisodeIntakeService.kt`（`@Service`）
自带构造型注解，**不在配置里重复 `@Bean` 声明**——它们需要被 Spring 代理，
手工 `@Bean` 会绕开代理而让 `@Transactional` 静默失效。这条理由写在配置类的 KDoc 里。

**事务边界**：`EpisodeIntakeService.accept` 是整条接入的一个事务。
投递失败会连带回滚已经算好的影响面（见 `episode/EpisodeSessionPorts.kt:265` 的契约说明）。

**越权防护**：会话成员表是 append-only；`EpisodeSessionAdapter` 在事务内捕获唯一键冲突，
不污染事务。

## 7. 归因分级与 claim-evidence

**五档归因**（`domain/RcaAttribution.kt:19`）：

`DIRECT_CONFIRMED` / `SUPPORTED_INHERITED` / `AMBIGUOUS` / `UNATTRIBUTED` / `REJECTED`

`autoLinkPermitted` 只放行前两档——**只有可自动确认的归因才允许自动关联**，
`AMBIGUOUS` 及以下必须人工处置。

**逐对判定**：`t_rca_claim_evidence` 与 `ClaimEvidencePair.evaluate`（同文件）
实现「结论中的每一条 claim，逐条对上它引用的证据」，而不是只给整体一个分数。

**约束**：`chk_rca_claim_range_needs_cohort` 强制 RANGE 类 claim 必须引用 COHORT 证据。
即「某版本区间有问题」这类结论，不能拿单条证据支撑——**必须给出队列证据**。
这条约束是 schema 层面的，绕不过去。

## 8. 共享状态与预算

**六块记忆**（`domain/RcaSharedState.kt:21`）：
`problemProfile`、`mandatoryPlan`、`hypotheses`、`toolHistory`、`evidenceLedger`、`gapsAndBudget`
（另有 `arbitration`、`stopProposal` 等非记忆字段）。

**预算**：`GapsAndBudget` 含 `stepBudget` / `tokenBudget` / `costBudgetUsd`，
落库到 `t_rca_agent_task` 的 `budget_max_steps` / `budget_max_tokens` / `budget_max_cost_usd`。
`exhausted()` 是停止条件之一（另有 `graph/StopEvaluator.kt`）。

**一个与常见做法不同的选择**：本实现**不做上下文压缩**。
证据正文**根本不进上下文**——`evidenceLedger` 里只存 `evidenceId`，正文按需回源
（`RcaSharedState.kt:14` 的注释：「正文留在账本里按需回源，控制上下文体积」）。

这个选择必须在本文写清楚，因为其它文档若描述「累计 N 次调用触发压缩」或
「按依赖闭包增量合并」，那**与本实现不是同一条路线**。两者都能控制上下文体积，
但机制不同：本实现是**从源头不让正文进来**，不是**进来之后再压出去**。

## 9. 租约、fencing 与恢复

**租约令牌**：`runtime/RcaTaskLease.kt` 的 `RcaLeaseToken(taskId, owner, generation, stateVersion)`。

**写回是四重条件**（`resources/mybatis/mapper/rca/RcaTaskLeaseMapper.xml:126-127`）：

```sql
WHERE id=#{id} AND lease_owner=#{owner} AND lease_generation=#{generation}
  AND state_version=#{stateVersion}
```

影响行数为 0 意味着租约或状态已被接管，**这不是异常而是丢弃信号**：
`runtime/RcaWorker.kt` 会整份作废本次结果并留痕（`RcaDiscardReason.FENCED_OUT`），
不做部分写入——部分写入会让新持有者读到一份自相矛盾的状态。

**为什么 owner 的随机性不承担防重放责任**：`RcaWorker` 的 `workerId` 允许在一次运行内稳定复用
（例如「主机名 + 线程名」）。切换保护靠的是 `generation`，不是 owner 的唯一性。
把唯一性压在 owner 上，就等于把防重放交给一个会被复用的字符串——
进程重启后同名、容器同名都会让旧写回重新生效（ABA 问题）。

**领取**时 `lease_generation = lease_generation + 1`（同 XML `:62`），
这是该语句里唯一一处不幂等的写法，映射文件里写明了它必须如此的理由。

**领取同时补两个「只补一次」的列**：`deadline_at = IFNULL(deadline_at, NOW(3) + N 分钟)`
与 `trace_id = IFNULL(trace_id, <新生成的 32 位小写十六进制>)`（同一条语句，见未决项 3 与
第 5 节）。两处都是 `IFNULL` 而不是直接赋值，但理由不同：前者挡超时上限被重试续期，
后者挡同一个任务被重试成多条链路。`NOW(3)` 在整条语句里只出现一次，
租约与截止时间由同一个时钟读数推出。

**续租**的条件里**没有** `state_version`（`:93`）——续租只是「我还活着」的心跳，
不基于任何状态。若把 `state_version` 放进去，节点执行期间状态推进过的任务会续租失败。

**终态清空 `lease_owner`**（`:122`）：任务已收口，留着持有者会让「谁该负责」有一个过期答案。

**恢复判定**：`RcaWorker` 在进图之前先做恢复判定
（`runtime/RcaRecoveryResolver.kt`、`runtime/RcaToolRecovery.kt`）。
上一次执行可能留下「已发出但未落定」的调用，它既不能当没发生（上游可能已执行），
也不能当已完成。**判定不了就转人工**；判定为重放则交给节点去重放——
在 Worker 层补一次调用会让节点随后再发一次，两次调用一条证据，事后无法分辨。

## 10. 知识沉淀顺序

`graph/RcaPersistNode.kt` 对落库顺序有**硬约束**：知识候选必须最后落（`:67-72`）。
只有可执行归因才沉淀（`:46-51`、`:130`）。

**召回侧同样受限**：`tools/HistoricalCaseTool.kt:15-21` 只召回**经发布后验证有效**的案例；
未验证返回 `NO_VERIFIED_CASE`（`:60-68`），依据上游的 `verificationPassed` 字段
（`tools/RcaUpstreamPorts.kt`）。

即「沉淀」与「召回」两侧都有门：不是所有结论都能进知识库，也不是知识库里所有东西都能被召回
当作依据。

## 11. 运行入口与能力边界 ⚠️

**这一节是本文最重要的一节。**

> **本节已于 2026-09-28 改写。** 改写前的结论是「整个运行时没有生产入口」，
> 那一条当时属实；入口已在此后补做，本节记录的是**补做后的新边界**。
> 旧结论在 2026-09-28 之前提交的文档与汇报里出现过，凡引用旧结论的地方以本节为准。

### 11.1 两个入口都是 XXL-Job handler（两轨制）

按仓库既有约定，入口是**薄 handler + 厚 Scheduler**，业务逻辑不在 `job/` 下：

| 入口 | Handler（`job/rca/RcaJobHandlers.kt`） | 业务实现 | 总开关 |
|---|---|---|---|
| 接入 | `@JobHandler("feedbackRcaIntakeHandler")` | `agent/rca/intake/RcaIntakeScheduler` | `app.rca.intake-enabled` |
| 执行 | `@JobHandler("feedbackRcaWorkerHandler")` | `agent/rca/runtime/RcaWorkerScheduler` | `app.rca.worker-enabled` |

**没有做 Admin Controller**：触发面按仓库既有约定收敛到 XXL-Job 一处，
控制器不新增，因此权限角色边界与 `@PreAuthorize` 一个字节都没动。

两层开关是刻意的：`xxl.job.enabled`（默认 `false`）决定 handler 是否装配，
`app.rca.*-enabled`（默认 `false`）决定批次是否真的执行。
**默认状态下要真正跑起来，必须人工排一次调度并且显式打开开关**，
不存在「部署上去就自己开始跑」的路径。

### 11.2 执行层现在在 Spring 容器内

五个节点、七个工具、状态图、Worker **仍然刻意不带构造型注解**——它们要为离线回放
保持可替换端口（`replay/RcaReplayMain.kt` 用固定时钟、脚本化模型、内存持久化把它们 `new` 出来）。
生产装配集中在**一个**文件：`config/RcaRuntimeConfiguration`。依赖方向因此是显式的、可审的。

**哪些 Bean 不在这里声明**（重新声明会静默破坏行为）：
`RcaTaskLeaseAdapter`（`@Component` + `@Transactional`，重新声明成 `@Bean` 会绕过代理、
静默让事务失效）、七个 `@Repository` 适配器、两个 `@Component` Scheduler。
`RcaRuntimeConfiguration` 只**消费**它们的端口类型。

其中 `MyBatisRcaToolCallJournalRepository` 有一条额外约束：**它的两个写方法必须各自自动提交**。
`begin` 落下的 `PENDING` 行如果在事务里，崩溃时会随事务一起回滚，
两阶段写入于是看起来接上了、实际什么都没保住，而且不报错。所以这个类**不能加 `@Transactional`**，
也不能把它包进一个更大的事务里（`RcaRuntimeConfiguration` 的类注释里记了同一条）。

### 11.3 执行入口的两条易错约束（改这里的代码前先读）

1. **handler 绝不能先 claim 再交给 Worker。** `RcaWorker.runOnce()` 自己调
   `leases.claim`，先 claim 一次会让租约代数对不上，任务被自己的第一次 claim 挡在门外。
2. **`recovery` 必须接真实记录表。** 2026-09-29 之前它是 `RcaRecoveryResolver.NONE`，
   语义是「这一层没有信息」，**不是**「默认安全」；那在写入侧还不存在时是准确描述，
   写入侧接上之后就不准确了——再用 `NONE` 等于把「有一条调用无法判定」放行成
   「没有已知的在途调用」。现在注入的是
   `RcaRecoveryResolver(MyBatisRcaToolCallJournalRepository, RcaProviderStateUnavailableRepository)`，
   后者如实回答 `UNREACHABLE`，于是「已发出、带请求 ID、未落定」的调用会转人工而不是被自动重放。

### 11.4 能力边界（这一节决定了本文全部结论的强度）

**入口通了，不代表能产出根因。** 三件事必须分开陈述：

1. **接入侧与执行侧都真的跑完了**，且数据落到了 11 张表上（实测数字见第 12 节）。
2. **五个上游证据源在本仓库没有数据落点**，只能按契约降级：
   `repository/impl/rca/RcaUpstreamUnavailableRepository` 的每个 `fetch` 返回
   `UpstreamEnvelope.failed("UPSTREAM_UNCONFIGURED")`，经 `ReadOnlyRcaTool` 的映射落到
   `UNKNOWN`（`evidenceGap = true`）。**刻意不用 `RESOURCE_MISSING`**——
   那个状态在 `domain/RcaToolContract.kt` 里是 `evidenceGap = false`，
   会被下游读成「确认不存在」，即一条反证。
   后果：真实运行下证据永远不足，结论只能是 `PARTIAL`。
3. **四张落库目标表在真实运行路径上是 0 行**。`t_rca_evidence` / `t_rca_hypothesis` /
   `t_rca_mandatory_evidence` / `t_feedback_episode_attribution` 的写入发生在
   `RCA_PERSIST` 节点，而证据不足时图停在 `AGENT_INVESTIGATE`，**走不到那个节点**。
   这一条不是缺陷，是「证据不足不下结论」在真实数据上的表现；
   但也意味着**持久化层不能靠端到端来验证**，本包因此单独有一个持久化契约测试（第 12 节）。
   同理，`t_rca_tool_call` 在真实运行路径上也是 **0 行**——它由 `AGENT_INVESTIGATE` /
   `BASELINE_EVIDENCE` 里的工具调用产生，而降级路径上工具确实被调用了
   （`RcaWorkerEndToEndTest` 里基线节点照常执行强制证据计划），
   但**本机的端到端任务停在 `PARTIAL`**，所以本机库里的 0 行不能推出
   「工具调用记录写不进去」。第 5 节的写入侧因此验在持久化契约层。
   这条区别在 12.5 末尾再讲一次，因为它是「链路标识已实现」这句话的强度上限。
4. **`controlVersion` 在当前部署形态下结构性恒为 NULL**。它的唯一来源是版本发布治理表
   `t_app_version_release_fact`（V98 创建），而该表被 `V156__retire_legacy_shadow_tables.sql`
   连同另外 46 张表一起**有意退役**（该迁移的前提写在文件开头：生产读写切换、对账、
   全量回归与独立恢复快照都已完成）。所以「版本集中度」这一类结论在 V156 及之后的
   任何环境都取不到对照基准——**这不是本机现象，也不能靠补迁移解决**。
   `MyBatisRcaEpisodeFactsRepositoryImpl` 对此只降级 `42S02`（表不存在），
   其余失败原样抛出；**不许**改从 `t_feedback_source.app_version` 反推一个「对照版本」——
   版本对照必须来自独立的发布治理事实，拿同一份数据反推等于用被验证对象证明被验证对象。

**不能讲的话**：不能说「RCA 已上线」「已接入生产流程并产出根因」。
准确的陈述是：**入口已接、任务真的跑完并收口、结论是证据不足**。
`docs/reference/ai-development-lessons.md` §4 的「已实现 / 已部署 / 已联调 / 生产验收
必须分别陈述」在本节适用：当前处于**已联调**，未到生产验收。

### 11.5 核查方法（可复现）

| 核查项 | 命令 | 实测结果 |
|---|---|---|
| 门禁 `:backend:check` 全绿 | `:backend:check --rerun-tasks --no-build-cache` | 13 tasks executed / `BUILD SUCCESSFUL`；`architectureTest` 79 项失败 0、`test` 1,444 项失败 0、`integrationTest` 9 项失败 0（详见 12.5） |
| 门禁：`service` 直连 MyBatis mapper | `:backend:architectureTest` | `service-direct-mybatis-mapper` 不在失败列表里（详见 11.6） |
| 接入入口端到端 | `:backend:rcaEntryTest`（`RcaIntakeEndToEndTest`） | 743 → 743 个 `PENDING` 任务；重投 0 新增（详见 12.3） |
| 执行入口端到端 | `:backend:rcaEntryTest` | 5 个任务全部 `PARTIAL`+`LOW_INFORMATION_GAIN`（详见 12.3） |
| 链路标识写入与读回 | `:backend:rcaEntryTest` | 25 项；领取补一次且不换新、NULL 不兜底、库上约束真的生效（详见 12.5） |
| 投递轨切到 MQ | `:backend:mqIntegrationTest -DMQ_INTEGRATION_TEST_ENABLED=true` | 7 项 / 失败 0（通道层 3 + 三条轨端到端 4；详见 12.6） |
| 离线套件未被改名破坏 | `:backend:rcaReplay414` / `rcaRecoveryInjection` / `rcaEpisodeSessionization` | **30 / 1,301 / 74**，与改名前的断言数逐项一致（强制全量重编译后复测，详见 12.5） |

### 11.6 门禁修复：整树从 `service/rcaagent/**` 迁到 `agent/rca/**`

**为什么必须动**：`EpisodeSessionAdapter` 与 `RcaTaskLeaseAdapter` 位于 `service/**` 下
却 import MyBatis Mapper，命中 `ClassicLayeringGateTest` 的 `service-direct-mybatis-mapper`
（该规则 baseline 硬编码为 `emptySet()`，无法用白名单放行）。
`backend/build.gradle.kts` 的 `tasks.check` `dependsOn(architectureTest)`，
所以当时 `gradle check` 是红的，**影响整仓而不只是 RCA**。

**为什么是整树迁而不是只搬两个类**：迁到 `agent/rca/**` 后，这个包既不属于 `service/**`
也不属于 `repository/**`，四层门禁一条都不扫它——于是**这个运行时以后新加的任何适配器
都不可能再踩这条规则**。只搬违规的两个类会留下「同一运行时拆在两棵树、
适配器与它实现的端口不在同一包」的长期结构债。

**结果**：`architectureTest` 的失败数 **6 → 4**，消失的正是 `service-direct-mybatis-mapper`
（2 条）。**baseline 与门禁文件一个字节都没改。** 剩下 4 条是既有失败，与本次改动无关：
`security/attachment/FeedbackAttachmentAccessAdapter.kt` 与改动前的仓库快照**逐字节相同**
（md5 `e7dca710f3e4ae5901b19b3c43b0426f`，mtime 为原始检出时间），
且 `docs/delivery/27-six-domain-directory-governance.md` 第 84 行早已记录过其中一条
（`ApplicationSymptomDryRunService.summarize -> IllegalStateException`）。
这 4 条**未修、未掩盖**，如实留在那里。

## 12. 验证证据

以下均为本机（Linux，JDK 17）实测，完整记录在 `/home/iisc/fp-evidence/2026-09-28-本机实测证据.md`；
2026-09-29 的复测记录在 `/home/iisc/fp-evidence/2026-09-29-trace/`。

> **12.1 与 12.2 是 2026-09-28 的历史记录，其中的数字请连同 12.5 一起读。**
> 12.1 那一组「逐项复现」当时是在**增量编译带上来的旧字节码**上跑出来的
> （构建陷阱，见 12.5 起因），本轮在强制全量重编译下重跑后**数字完全相同**，
> 结论侥幸成立而当时的证据无效；12.2 表里那 6 项失败**已于 2026-09-29 全部修掉**。
> 两节正文按「历史记录不加修辞」保留原样。

### 12.1 离线套件（2026-09-28 改名后逐项复跑，与改名前的断言数完全一致）

| 项 | 命令 | 结果 |
|---|---|---|
| RCA 回放 | `:backend:rcaReplay414` | 断言 30 项通过（`RC=0`） |
| 故障注入 | `:backend:rcaRecoveryInjection` | 断言 1,301 项通过（`RC=0`）；**80 次注入 / 76 次自动恢复 / 4 次转人工**；恢复动作 `REPLAY_READ_ONLY 36` / `REQUIRE_HUMAN 4`；恢复后「已提交 532 条，可直接复用 532 条，需重放 0 条」 |
| 会话化 | `:backend:rcaEpisodeSessionization` | 断言 74 项通过（`RC=0`） |
| 聚类影子套件 | `:backend:clusterShadowTest` | 74 项 / 执行 62 / 跳过 12 / 失败 0 |

**这三个数字是「改名是行为中性的」这条结论的全部依据**：断言数一个不少、一个不多。
只跑通、不看断言数，是证明不了这一点的——改名把类搬走了，编译器能发现漏改的引用，
发现不了「某个分支不再被走到」。

### 12.2 门禁（`service-direct-mybatis-mapper` 消失，未新增失败）

| 项 | 命令 | 结果 |
|---|---|---|
| 架构门禁 | `:backend:architectureTest` | **79 项 / 失败 4 项**；失败列表里**没有** `service-direct-mybatis-mapper` |
| 后端回归 | `:backend:test` | **1,321 项 / 失败 2 项 / 跳过 46 项**；两项均已证明为既有失败（见 12.4） |

改名前的 6 项失败里，消失的正是 `service-direct-mybatis-mapper` 那 2 条；剩下的 4 条
（`AttachmentChainArchitectureTest` / `ExceptionBoundaryArchitectureTest` /
`MigratedServicePackagePurityArchitectureTest` / `PermissionChainArchitectureTest`）
与本次改动无关，**未修、未掩盖**，理由见 11.6。

**2026-09-29 进展**：上面那 4 条与下面 12.4 那 2 条**已全部修掉**，
`:backend:check` 由红转绿。当前数字与实测方式见 12.5。

### 12.3 入口端到端与持久化契约（2026-09-28 补做，本机真库 3307）

入口三项共用一个门控任务 `:backend:rcaEntryTest`（`backend/build.gradle.kts:362`），
三个测试类同在一个源集 `backend/src/rcaEntryTest/`；任务启动前**断言 `DB_URL` 指向 3307**，
指错端口宁可失败也不退回 `application.yml` 的 3306 默认值。

```bash
./gradlew -I /home/iisc/.gradle/init-datasource.gradle.kts \
  -DRCA_ENTRY_TEST_ENABLED=true :backend:rcaEntryTest
```

| 测试类 | 结果 |
|---|---|
| `RcaIntakeEndToEndTest` | 743 个来源 → 743 个 `t_rca_agent_task`（`PENDING`）；**重复投递同一批 0 新增**（验的是唯一键，不是代码） |
| `RcaWorkerEndToEndTest` | 领取 5 个任务（`worker-max-tasks-per-run`），**全部收口**到 `PARTIAL` + `LOW_INFORMATION_GAIN`，`state_version=3`，终态行 `lease_owner` 已清空 |
| `RcaPersistenceContractTest` | 6 个写方法全部对真 schema 验证：确定性键幂等（重放不新增行、同键不同内容更新同一行）、归因 revision 追加式（两条都留着）、`linkIncident` 独立再判等级、`knowledge_status='DRAFT'` |
| `LlmInvestigationModelDegradationTest` | 在 `src/test`（不碰库，随每次 `:backend:test` 跑）。4 项通过；未配置 / 超时 / 结构不合规 → 均产出 `HUMAN_REQUIRED` 且不抛异常；超时实测 `elapsedMs=1197`（服务端延迟 5s、超时 1s） |

**三处必须如实说明的地方**：

1. **执行侧的实际结论是 `PARTIAL`，不是 `HUMAN_REQUIRED`。** 计划阶段预期的是后者，实测是前者：
   `graph/StopEvaluator.kt` 的检查顺序里，「可用证据与受支持假设均为空」这一条
   （检查 4）先于「提案给出的原因」被求值，于是停在 `PARTIAL` / `LOW_INFORMATION_GAIN`。
   这是「AI 提假设、后端做门禁」的设计在真实数据上的表现，**是分歧而不是失败**。
2. **`test` 源集的降级测试不打桩传输层。** 它装配**真实的** `OpenAiCompatibleLlmClient`、
   只把 base-url 指向 MockWebServer——打桩 `LlmClient` 只能验证 catch 分支，
   验证不了「异常到底长什么样」：未配置是 `requireConfigured()` 抛的 `AiTransientException`、
   超时是 OkHttp 的 `SocketTimeoutException` 被包过一层、结构不合规**根本不抛异常**（它是一条 200 响应）。
3. **持久化契约测试是 `@Transactional` 的，每条用例回滚。** 因此它**不需要任何 DELETE**，
   自然满足 `docs/delivery/13-demo-runbook.md:70,72` 对无批次前缀删除的禁止；
   跑完后 `t_rca_evidence` / `t_rca_hypothesis` / `t_rca_mandatory_evidence` /
   `t_rca_claim_evidence` / `t_feedback_episode_attribution` / `t_incident` / `t_ai_knowledge`
   均为 0 行（已实测核验）。

### 12.4 两项既有失败（证明为既有，与本次改动无关）

| 测试 | 现象 | 既有证明 |
|---|---|---|
| `ClusterMutationGovernanceTest` | `ClusterShadowPreviewEvidenceMapper.xml ==> expected: <false> but was: <true>`（该 mapper 命中被禁的 `JOIN|EXISTS` 正则） | 文件 mtime 为原始检出时间 2026-09-10 17:16，md5 `a6a0f27854190ae623c4950d4364895d` 与 `/home/iisc/fp-snapshot-20260927` **逐字节相同** |
| `WebhookEmailSenderTest` | `expected: <DNS_FAILURE> but was: <NETWORK_FAILURE>` | 文件 mtime 同为 2026-09-10 17:16，md5 `8caf20be8b6bbda1d050b1c55fff8f67` 与快照**逐字节相同**；失败内容是环境相关的 DNS 分类 |

即 **`:backend:test` 在本仓库当前基线就不是绿的**，与 RCA 工作无关。
**2026-09-29 进展**：这两项已修，修的是**测试**而不是被测对象——
`ClusterShadowPreviewEvidenceMapper.xml` 至今仍与 2026-09-27 快照**逐字节相同**
（md5 `a6a0f27854190ae623c4950d4364895d` 未变），改的是门禁测试：
把它从「目录级正则」换成**用可检验属性换来的豁免**（登记进 `READ_ONLY_JOIN_MAPPERS`
的文件必须零写语句、且读的表与登记集合完全相等，多读一张也红）；
`WebhookEmailSenderTest` 则是把 DNS 注入进去，不再依赖宿主机的解析行为。
两处都已不再与快照逐字节相同（这是它们当时身份的证明，不是现在的）。
`check` 的复测数字见 12.5。
`XxlJobArchitectureTest` 曾在本次工作中失败过（新加的 2 个 handler 没登记进
`config/xxl-job-tasks.conf`，且测试里 `assertEquals(43, ...)` 是硬编码），
这**不是单纯测试变红**：没有 manifest 条目，那两个 handler 永远不会被调度。
已修复（manifest 45 条 + 断言 45），复跑由 3 失败降为 2 失败。
其它三处数字引用（`docs/deployment/xxl-job.md:27,70`、
`docs/delivery/28-system-handover-status.md:241`、`docs/delivery/README.md:55`）已同步为 45。

故障注入那一组数字是**本包最硬的证据**：它验证的是第 9 节那套恢复判定与 fencing
在人为崩溃下确实收敛（76/80 自动恢复，剩下的 4 次判不出来、按设计转人工）。

**这些证据能说明什么、不能说明什么**：

- **能**：领域逻辑自洽，离线回放可重复跑通，恢复与 fencing 在故障注入下按设计收敛。
- **不能**：不能说明生产可用性、并发下的真实表现、真实模型调用质量、
  或真实上游数据上的效果。本机 5,000 条与 100/500 条回放是在
  **仓库自带的合成夹具**（`data/test-fixtures/full-system-20260805/`，
  `synthetic_data_only: true`）上跑的，**不是生产历史数据**。
- 离线回放用的是 `ScriptedInvestigationModel`，**不是真实 LLM**。

### 12.5 链路标识（V243）、门禁转绿，与一次**假绿**（2026-09-29 实测）

本节把两件事记在一起，因为前者只有在后者被排除之后才算数。

原始日志 `/home/iisc/fp-evidence/2026-09-29-trace/`（`check-forced.log` /
`offline-suites.log` / `test-and-integration.log` / `rca-entry.log`）。

#### 起因：一次真实的编译错误被构建缓存藏住了

`:backend:rcaReplay414` 报
`NoSuchMethodError: RcaStateGraph.run$default(RcaStateGraph, RcaSharedState, RcaNode, Function2, int, Object)`。
第一反应是「测试没重编译」，于是删 `build/classes` 重跑——**依然失败**。两条原因同时成立：

1. **真实缺陷**：`replay/RcaRecoveryInjectionMain.kt:688` 的调用点是**尾随 lambda**：
   `graph().run(resumed, start) { node, state -> ... }`。上一轮给 `RcaStateGraph.run`
   末尾追加了带默认值的 `traceId: String? = null`，于是 lambda 去绑 `traceId`、
   `onNodeBoundary` 拿默认值——这是编译错误。修法是显式命名：`onNodeBoundary = { ... }`。
   **给 Kotlin 函数的末尾加带默认值的参数，会静默改掉所有尾随 lambda 调用点的含义**，
   编译器只在增量编译真正执行时才报错。
2. **构建缓存把错误藏住了**：`org.gradle.caching=true` 加上 Kotlin 增量编译产物
   （`build/kotlin/`）会恢复上一次的 class 文件。只删 `build/classes` 不够——
   下一次编译从 `build/kotlin` 把**旧字节码**放回来，编译「成功」，跑的是旧代码。
   **必须同时删 `build/kotlin` 与 `build/classes` 并加 `--no-build-cache`。**

**代价要说清楚**：12.1 那组「改名后断言数逐项复现 30/1301/74」是在**旧字节码**上跑出来的，
当时没有验证任何东西。本轮强制全量重编译后重跑，数字**完全相同**，
所以那个结论侥幸成立、而当时的证据无效。本节以下所有数字均取自
`:backend:check --rerun-tasks --no-build-cache`（13 个任务全部 `executed`，无 `UP-TO-DATE`）。

#### 门禁：`check` 由红转绿

| 项 | 命令 | 2026-09-28（12.2/12.4） | 2026-09-29 |
|---|---|---|---|
| 架构门禁 | `:backend:architectureTest` | 13 类 / 79 项 / **失败 4** | 13 类 / 79 项 / **失败 0** |
| 后端回归 | `:backend:test` | **1,321 项 / 失败 2 / 跳过 46** | **1,444 项 / 失败 0 / 跳过 45** |
| 集成测试 | `:backend:integrationTest` | 4 类 / 9 项 / 失败 0 / 跳过 1 | 4 类 / 9 项 / 失败 0 / 跳过 1 |
| 整体 | `:backend:check` | 红 | **`BUILD SUCCESSFUL`（13 tasks executed）** |

`test` 的 +123 项里，13 项是本次为链路标识新写的用例（`ToolSpanIdFactoryTest` 4、
`RcaTraceCorrelationMigrationTest` 5、`RcaWorkerDeadlineTest` +2、`ToolInvocationGuardTest` +2），
其余来自修复 12.2/12.4 那 6 项失败时的测试改动。断言数与跳过数逐套取自
`build/test-results/*/*.xml` 的汇总，不是控制台读数。

#### 三个离线套件（重编译后，与 12.1 的断言数完全相同）

| 项 | 结果 |
|---|---|
| `:backend:rcaReplay414` | 30 项通过 |
| `:backend:rcaRecoveryInjection` | 1,301 项通过；80 次注入 / 76 次自动恢复 / 4 次转人工；`REPLAY_READ_ONLY 36` / `REQUIRE_HUMAN 4`；已提交 532 / 可直接复用 532 / 需重放 0 |
| `:backend:rcaEpisodeSessionization` | 74 项通过 |

各套件的结果 XML 里均无
`TEST-Gradle#20Test#20Executor#*.xml` 这类截断产物（断言数被写成 0 的常见来源）。

#### V243 被应用到本机真库

新增受限入口 `:backend:flywayMigrateLocalDeploy`（`rcaEntryTest` 源集的 `JavaExec`）。
它必须存在，是因为 `rcaEntryTest` 固定 `spring.flyway.enabled=false`，
而迁移是**全局副作用**，不该在一次测试运行里隐式发生。三重闸门：
必须显式 `-PconfirmFlywayMigrate=feedback_platform_local_deploy`；
`DB_URL` 必须含 `:3307`；库名必须**精确等于** `feedback_platform_local_deploy`。
凭据从 `:backend:rcaEntryTest` 那一份 environment 抄过来
（**不在 argv 里**，见 `docs/reference/` 的密钥约束）。
实测 `applied=1 versions=[243] targetSchemaVersion=243`。

#### 入口侧新增：`rcaEntryTest` 由 18 项增至 25 项

| 测试类 | 新增覆盖 |
|---|---|
| `RcaTaskLeaseContractTest`（10 项） | 领取补上链路标识的形状（`^[0-9a-f]{32}$`）、**再次领取不换新的**、库里 NULL 时读回 NULL 而不兜底、`chk_rca_agent_trace` 真的在库上生效 |
| `RcaToolCallJournalContractTest`（10 项） | 链路标识与 span 落进真库的列、两列要么都有要么都没有、只有 span 没有 trace 的行被 `chk_rca_tool_trace_span_pair` 拒掉 |

#### 这一节**不能**说明什么

`t_rca_tool_call` 在本机真库里仍是 **0 行**。原因是第 11.4 节那条：
五个上游证据源在本仓库没有数据落点，执行侧走到工具调用之前就降级收口了，
**生产路径上没有任何一次真实的工具调用**。因此链路标识的写入→落库这一段，
验的是**持久化契约层**（守卫的调用序列 + mapper + 真库约束），
**不是**「生产上真的按 trace 串出过一次调用链」。
同理，本节的 30/1301/74 全部跑在仓库自带的合成夹具与
`ScriptedInvestigationModel` 上，不是真实上游、不是真实模型。

### 12.6 投递轨：从 XXL 轮询切到 MQ（2026-10-09 实测）

原始日志 `/home/iisc/fp-evidence/2026-10-09-mq-integration/`
（`mqIntegrationTest-full-run-retry-*.log`、`regression-retry-*.log`、`rcaEntryTest-regression-*.log`）。

#### 「切轨」切的是**投递**，不是**中继**

三条轨的中继仍然由 XXL 驱动（一次调用发一批就返回），换掉的是**投递**：
消息到达由 broker 推给订阅方，而不是由时钟去库里取件。
**默认仍走轮询**，`app.mq.enabled=true` 才会切；启动时 `messaging/MqTrackReporter`
打一行说明这次走的是哪条轨（判据是 `app.mq.enabled` 与 `xxl.job.enabled` 的组合，
单看配置文件回答不了）。切到 MQ 时，两个轮询取件入口直接返回——
**空转如果只是「反正没人调度它」，一旦有人误开了调度，两条轨会同时认领同一批行，
那不是重复投递而是漏投**（认领方不投递、投递方认领不到），所以这一条有测试盯着。

| 轨 | 中继（XXL） | 主题 | 消费侧 | 线格式 |
|---|---|---|---|---|
| RCA 领域事件 | `RcaDomainEventRelay` | `FEEDBACK_RCA_DOMAIN_EVENT` | **本进程不订阅**（给进程外订阅方） | 事件载荷（不含正文/联系方式/指纹/日志/错误详情） |
| 通知投递 | `NotificationMqTrack.relayPending` | `FEEDBACK_ALERT_NOTIFICATION` | `NotificationMqTrack.handle`（本进程） | `{"id": <通知行号>}`——**正文不出库** |
| 索引同步 | `IndexMqTrack.relayPending` | `FEEDBACK_INDEX_OUTBOX` | `IndexMqTrack.handle`（本进程） | 发件箱信封（含 `inputSnapshot`） |

通道层是 `com.feedback.messaging`（9 个文件）：`MessagePublisher`（开关关闭时是
`REFUSING`，任何不该发生的投递在第一次调用就抛 `MQ_DISABLED`，而不是让 Bean 缺席——
缺席会让「配错了」表现成启动失败或更糟的静默跳过）、`RocketMqMessagePublisher`、
`RocketMqMessageConsumerLifecycle`（`SmartLifecycle`，一个主题一个消费者组、
批大小固定为 1、处理失败回 `RECONSUME_LATER`）、`MqMessageHandler`、`MqProperties`、
`MqTrackReporter`。三个新 handler 已登记进 `config/xxl-job-tasks.conf`（共 48 个）。

#### 通道层与业务层分开验（`mqIntegrationTest`，7 项 0 失败）

命令：
`./gradlew -I ~/.gradle/init-datasource.gradle.kts :backend:mqIntegrationTest -DMQ_INTEGRATION_TEST_ENABLED=true`
（新源集 + 门控任务，与 `rcaEntryTest` 同构，凭据同样从 600 权限文件读、不进 argv）。

| 类 | 项 | 验的是什么 |
|---|---|---|
| `RocketMqBrokerRoundTripTest`（无 Spring、无库） | 3 | 中英混排正文往返后逐字节相同、tag/key/user property 原样到达、两个「发送之前就该失败」的入口（未 `start` 就发、正文超过 `MAX_BODY_BYTES`）、broker 连不上时收敛成 `MqPublishException(MQ_SEND_FAILED)` |
| `MqTrackEndToEndTest`（Spring + 真 broker + 真库 3307） | 4 | 装配：发布口就是 `RocketMqMessagePublisher`、`lifecycle.isRunning()`、两条消费轨订阅到主题、**RCA 主题不在本进程订阅列表里**；领域事件被探针读回**之后**该行才被标记已投递（第二轮中继不改动 `published_at`、`publish_attempts` 仍为 0、不再出现在未投递列表）；通知由 broker 驱动（探针收到的正文**精确等于** `{"id":n}`，同时轮询入口一行都不碰，行最终到 `DELIVERED` 且 `last_provider_code='IN_APP'`）；索引任务由 broker 驱动执行完，写回带的是中继那次认领的栅栏令牌 |

**为什么通道层要单独一层**：端到端用例断言的是「某张表的某一行最后变成了什么状态」，
通道写错了也可能通过——正文里的多字节字符被改坏、user property 根本没随消息走，
只要消费侧解析出的 id 还对，业务断言照样绿。所以线格式必须有一处**只看字节**的验证，
而它不该经过任何业务代码。同理，索引轨的夹具用「桶里不存在的实体」，
让断言只关于**通道**而不关于 ES 是否健康。

**两处刻意的测试技巧**（都写进了类文档）：探针用一次性主题与一次性消费者组
（复用组名会从上次的位点继续，用例会变成「有时通过」）；
Spring 侧用 `CONSUME_FROM_FIRST_OFFSET=true` 消除「消费者还没完成 rebalance、
消息已经发出去」这个时序依赖，重放是安全的——三条 handler 都落在带唯一键的表上。

**框架侧**：`RocketMqBrokerRoundTripTest` 的「连不上」那条必须自己改一次系统属性
`rocketmq.client.name`。`MQClientInstance` 是按 `clientId = IP@instanceName` 缓存的
**进程级单例**，同进程里两个生产者的 instanceName 都取默认值时，第二个会复用第一个的
连接（连同它的 namesrvAddr），用例测的就不是「连不上」了。

#### 回归

| 套件 | 结果 |
|---|---|
| `:backend:test` | 1,506 项 / 失败 0 / 跳过 45 |
| `:backend:architectureTest` | 79 项 / 失败 0（门禁仍绿） |
| `:backend:rcaEntryTest` | 45 项 / 失败 0 |
| 离线套件 `rcaReplay414` / `rcaRecoveryInjection` / `rcaEpisodeSessionization` | 30 / 1,301 / 74（取自 2026-09-29 的强制全量重编译运行） |

断言数逐套取自 `build/test-results/*/*.xml`，且各目录下无
`TEST-Gradle#20Test#20Executor#*.xml` 这类截断产物。

#### 部署前提与失败模式（这一节必须连同配置一起交给运维）

1. **主题必须预建**。消费侧在容器 refresh 时就订阅，主题不存在则拿不到路由信息。
   本机是用 `mqadmin updateTopic -n <namesrv> -c DefaultCluster -t <topic>` 预建的三条。
2. **失败即启动失败**：`app.mq.enabled=true` 时 broker 不可达或主题缺失会让
   `SmartLifecycle.start()` 抛异常，**整个 Spring 上下文 refresh 失败**。这是刻意的——
   静默降级会让「投递已经切到 broker」变成一句假话，而库里那些行会停在中间状态。
   代价要说清：**MQ 开关因此变成启动依赖项**，部署顺序必须先起 broker 再起应用。
3. **broker 侧无鉴权**（本平台当前的 broker 不对外暴露）。配置里没有任何凭据，
   安全边界靠网络边界；生产部署必须显式覆盖 `app.mq.namesrv-addr`。

#### 这一节**不能**说明什么

- 跑的是**单节点本机 broker**：没有集群、没有 HA、没有鉴权、没有压测，
  也没有与 XXL 轨长时间并行的对照。
- **死信路径没有被跑过**：重投次数用尽的消息进 `%DLQ%<组名>`，那里没有消费者，
  只留一条 ERROR 日志。这是已知缺口，不是已验证的行为。
- 「领域事件已经离开本进程」是**测试里的探针**（进程外订阅方的替身）证明的，
  生产上还没有任何订阅方。按 11.4 的口径，本节属于**已联调**，不是生产验收。
- 通知/索引两条轨的**业务**语义没有变化（它们本来就落库、本来就幂等），
  本节验的是**投递路径换了之后状态仍然能推进到终态**。

#### 复现须知（本机环境，非代码问题）

- 一次运行出现过 `NoClassDefFoundError:
  org/aspectj/weaver/internal/tools/PointcutExpressionImpl$HasPossibleDynamacContentVisitor`
  ——注意类名里多了一个字母（`Dynamac`），jar 本身 `zipfile.testzip()` 干净，
  **同一条命令原样重跑通过**。与本机既有的「JVM/读路径不稳」记录一致，不要去改代码。
- Spring 侧用 `spring.main.lazy-initialization=true`，与仓库里其余每一个全上下文用例一致。
  实测 `false` 在本仓库起不来，两个独立原因：饿汉实例化会去读只有部署环境才有的占位符
  （`${IMPORT_RAW_STORAGE_SECRET}` 等，仓库不含任何 test 资源文件提供它们），
  另一次还撞上 `RedisConfig.stringRedisTemplate` 与 `RedisAutoConfiguration` 的
  同名 bean 定义冲突（既有的三个 MySQL 全上下文用例对此用的是
  `spring.main.allow-bean-definition-overriding=true`）。两件事都与本节目标无关，未改动。
  懒初始化**不影响**本节的结论：`SmartLifecycle` 由 `DefaultLifecycleProcessor`
  在 refresh 时强制实例化并 `start()`，所以 `assertTrue(lifecycle.isRunning())`
  仍然是真的断言（`isRunning()` 读的就是 `start()` 里置上的那个标志）。

### 12.7 多租户隔离（V245）（2026-10-09 实测）

第 13 节未决项 8 原文是「单租户。`tenant_key` 为占位」。当时的实情有两个：
`t_feedback_episode.tenant_key` 由产线代码写死常量 `'DEFAULT'`，
`t_auth_external_identity.tenant_key` 从不进入任何过滤条件。也就是说租户维度
**没有判定、没有写入方、也没有取值域**，只是一个存在的列。

`V245__tenant_isolation_foundation.sql` 是纯前向迁移（既有列的取值一个不动）：

1. `t_auth_tenant` 租户注册表，`tenant_key` 从自由字符串变成**封闭可枚举值**，
   种入 `'DEFAULT'` 一行。
2. `t_auth_user.tenant_key`（`NOT NULL DEFAULT 'DEFAULT'` + FK + 索引）——
   **会话主体的租户来源**。
3. `t_application_identity.tenant_key`（同形）——**数据归属的权威锚点**。
4. `chk_data_perm_scope` 放开加入 `'TENANT'`（该约束在 V164 被收窄过）。
   租户因此复用既有的行级 ALLOW/DENY + 失败关闭机制，而不是另造一套授权模型。

#### `t_feedback_source` 刻意**不加**租户列

一条反馈属于哪个租户是**派生事实**（它经由哪个应用身份进来），不是独立事实。
把派生事实冗余成列就是缓存，而这个缓存的两个后果都不能接受：
它唯一的回填者是影子批次（所以必然滞后），且应用身份改租户后存量行会
永久留在旧租户、**不会自愈**。于是租户改由 SQL 现算：
`t_feedback_source.id → t_feedback_primary_problem_fact.application_identity_id
→ t_application_identity.tenant_key`，两跳都是主键查找，而这两条查询本来就是
按 id 单行读、本来就 join 着内容表，代价可忽略。理由写在 V245 第 4 节与两个
mapper 的注释里，不要只改一处。

#### 判定：租户**不并进** `permits(...)`

`DataAccessScope.permitsTenant(tenant)` 是一个独立方法，**刻意不做成 `permits` 的一个
带默认值的参数**。并进去的后果是：全部现存调用点会在没人察觉的情况下变成
「不检查租户」——那正是这次改造要避免的静默失败。拆开之后，每个调用点要么显式
调用它，要么在评审里看得见它没被调用。缺租户与空白按**拒绝**处理：
`null` 不是「无租户」，是「租户未知」。

默认口径五条（都有断言或注释钉住）：

- **没有任何 TENANT 授权行 → 只看自己租户**，不是拒绝全部。照抄渠道维度的
  「无行即拒绝」会让 V245 上线当天全部存量用户立刻看不到任何数据——那不是收紧，
  是事故。跨租户读取必须由显式 ALLOW 授予。
- DENY 始终优先（`*` DENY → 拒绝全部）。
- 通配 ALLOW（`*`）且**没有** DENY = 不受限；**带**具体 DENY 时无法用有限集合安全
  表达 → 整体失败关闭，与 MODULE/REGION 对通配的处理同一口径。这条如果退化
  成「忽略 DENY」，就是一个静默的跨租户全开。
- 主体自身租户为空 → 拒绝，否则「空串对空串」会被放行。
- **ADMIN 是唯一不显式授权即可跨租户的路径**（`scopeFor` 里 `tenants = null`）。
  理由是平台运维本来就要跨租户排障；要收掉它，改动点就是那一行。

**跨租户报「不存在」而不是「拒绝」**：报拒绝就等于给出一个可以探测
「这个 ID 在别的租户里存在」的信道（`FEEDBACK_NOT_FOUND`，与记录真的不存在完全
同一个响应）。守卫的判定顺序是**租户 → 渠道/模块/地域**，租户在前不只因为它是外层
归属，也因为失败方向相反时它更早失败。

#### 写入侧：本项唯一真的新增了写入方的地方

`UserCreateInput.tenantKey` →（事务内）`TenantRegistryRepository.activeKeys()` 校验
→ `AuthUserMapper.insertLocal`。三个决定：

- **在业务校验处失败，不靠外键报错**。外键失败会被上层统一映射成持久化异常，
  调用方就分不清「租户填错了」和「库出问题了」，而这两件事的处置方式完全不同。
- `insertLocal` 是 `t_auth_user.tenant_key` 的**唯一写入方**，且刻意**不做 COALESCE 兜底**：
  再兜一次会让「谁决定租户」出现两个地方，两处口径一旦分叉就是静默的跨租户归属。
- 注册表**只有读 API**（`AuthTenantMapper.findActiveKeys`，SQL 里过滤 `SUSPENDED`、
  无进程内缓存）。租户的建立与停用是部署期治理动作，本仓库没有对应入口；
  提供一个「能建租户但没有任何地方用到」的 API 只会增加出错面。读取存在的意义
  只是让「给账号分配租户」这一个写动作能在落库**之前**校验目标租户存在。

#### 实测

**门控端到端**：`com.feedback.security.TenantIsolationEndToEndTest`（`rcaEntryTest`，
真库 3307，`-DRCA_ENTRY_TEST_ENABLED=true`）1 项 0 失败。单个用例六个阶段，
每段带编号断言，失败信息直接读出阶段：

1. 默认收紧：自己租户能读；跨租户报 `FEEDBACK_NOT_FOUND`，且范围里确实没有那个租户
   （单独断言中间层，防的是「守卫自己写死了拒绝」被误读成租户生效）。
2. 显式 ALLOW 放开跨租户，且不影响自己租户。
3. 同一行改成 DENY 后重新收紧。
4. 通配 ALLOW + 具体 DENY：**连自己租户也拒**（失败关闭）。
5. 接入侧现算的租户与守卫看到的是同一个值（防两处取租户的方式分叉，
   租户是 `uk_episode_partition` 的组成部分）：实测
   `candidateTenants=[(1510, FPTEN085822364A), (1511, FPTEN085822364B)]`。
6. 未注册租户建号在校验处被拒，且**既没留下账号也没留下审计行**。

夹具按批次前缀 `fpten<nanotime>` 造、按同一前缀清理；跑完 `t_feedback_source`
回到 753 行，`tenant/identity/user/permission` 四张表的 `fpten%` 残留均为 0。

**单元**：`FeedbackAccessGuardTest` 4 → 5 项，新增「跨租户与记录不存在不可区分」——
把守卫 KDoc 上的这条约定钉在快速单元层，不必等门控用例去发现。
`FeedbackAccessRepositoryTest` 改用**非默认**租户值断言：用 `'DEFAULT'` 分不清
「真的读到了这一列」与「恰好等于兜底值」。

**回归**：`:backend:test` 1507 项 0 失败 45 跳过；`:backend:architectureTest`
13 类 79 项 0 失败；`:backend:rcaEntryTest` **46 项**（45 → 46）0 失败；
三个离线套件 **30 / 1301 / 74** 一项不少；`:backend:integrationTest` 9 项 1 跳过
0 失败（首轮 1 项失败见下）；各套件结果 XML 均无
`TEST-Gradle#20Test#20Executor#*.xml` 截断产物。
证据 `/home/iisc/fp-evidence/2026-10-09-tenant-isolation/`。

**本机环境噪声（与本次改动无关）**：`:backend:integrationTest` 首轮
`AttachmentMigrationCryptoIntegrationTest` 报
`array contents differ at index [5012575], expected: <105> but was: <97>`
——105 与 97 只差一个比特（`0x08`），同一命令原样重跑通过。
与本机既有的「大数组会翻一位」记录同形。

#### 未迁移清单（**不要读到「多租户已做」就收工**）

- 租户判定目前**只落在 `FeedbackAccessGuard` 一处**（全仓唯一的 `permitsTenant` 调用点）。
  以下 7 处以 `DataAccessScope.permits(...)` 判定的读取/写入路径**仍然不看租户**：
  `repository/ai/AiReviewRepository.kt:54,64`、
  `repository/feedback/detail/FeedbackNavigationResolver.kt:53`、
  `service/alert/EntityClosureService.kt:11`、
  `service/alert/AlertQueryService.kt:109,111`、
  `service/query/ClusterReviewService.kt:160`、
  `service/query/ProductFeedbackRequestService.kt:108`、
  `manager/cluster/ClusterMutationAccessGuard.kt:34,73`（**写路径**）。
- 列表读路径与问题分析读路径走的是**投影表，而那些表没有任何租户列**。
  单库多租户部署下，这些按渠道/模块/地域过滤的查询会把多个租户的行混在一起。
  本机只跑过单租户部署，**这一点没有实测过**。
- MQ 载荷不带租户（第 12.6 节的载荷本就不含正文/联系方式/指纹/日志/错误详情，
  但也没有租户）。进程外订阅方若要按租户分流，现在没有依据。
- **给应用身份分配租户没有入口**。应用身份由影子批次自动建立
  （`ApplicationIdentityShadowCommitWorker.commit`），不继承任何租户，所以
  `t_application_identity.tenant_key` 在代码里**没有任何生产写入方**。
  门控用例扮演的正是「把它划归某租户」的那个运维角色——它写的是真数据不是替身
  （守卫与接入都从这一列现算），但「生产上谁来写」这个问题在代码里没有答案。
- `t_auth_tenant` 同样**没有建立/停用入口**，只有读。
- `t_auth_data_permission` 上**除 `uk_auth_data_scope`（含 `effect`）之外还有更严的
  `uk_subject_scope`（不含 `effect`）**，因此同一个
  `(subject_type, subject_id, scope_type, scope_value, access_level)` 只允许一行
  ——**ALLOW 与 DENY 在库层面无法并存**。「放开后收紧」是改这一行的 `effect`，
  不能靠再插一行。这是实测撞出来的（`Duplicate entry ... uk_subject_scope`），
  只看 DDL 很难一眼看出。
- `t_system_audit_log` 是**只追加**的（`trg_audit_log_no_delete` 抛
  `AUDIT_LOG_APPEND_ONLY`）。本机部署库里因此**永久留下 1 行**测试残留
  （`user_id=990000001`、`AUTH_USER_CREATED`、`target_id=1`，来自早期一版
  经管理服务建夹具账号的写法），删不掉。门控用例现在刻意改走仓储层建夹具账号，
  就是为了不再产生更多这种不可撤销的行。

#### 交付文档口径：哪一份更新了、哪一份是故意不动的

V245 只有一个维度变化，但**权限口径散在四份文档里**，逐份交代，免得下一个人
以为「11 号文档写了 TENANT」就等于全套文档一致：

| 文档 | 处理 | 原因 |
|---|---|---|
| `11-quality-permission-security.md` | **已更新**：行级范围加 `TENANT`，并补一节写清三处与其余维度不同的语义 | 它是权限模型的规范文本，且没有基线日期，读起来就是「当前」 |
| `05-database-and-er.md` | **只加基线声明，不改维度表** | 该文清点基线是 V178（44 张表），根本不知道 V242–V245 存在；只把 `TENANT` 塞进一张「44 表世界」的表里，等于用一个局部正确的补丁让人误以为全文是当前口径 |
| `reference/project-deep-dive-and-production-defense.md` | **不动** | 第 3 行已明写「适用基线：2026-08-14，Flyway V178」；改它反而破坏它自己的基线声明 |
| `16-ai-engineering-remediation-governance.md` | **不动** | 交付契约。仓库明令「不要把变化状态复制进交付契约」（`README.md:34`） |
| `28-system-handover-status.md` | **不动** | 2026-09-10 的一次性交接快照，改写历史交接记录没有意义 |

结论：**规范口径以 11 号文档 + 本节为准；05 号文档的 44 表与
`CHANNEL/MODULE/REGION/FIELD` 是 V178 快照，不是本项的遗漏。**

#### 复现须知（夹具与外键，别踩第二遍）

- 清理必须按外键顺序，且 `t_feedback_workflow_state` 那一行**不是测试写的**，
  是数据库触发器 `trg_feedback_content_workflow_default` 在插入内容行时建的——
  漏删它会在删来源那一步撞 `fk_feedback_workflow_source`（1451）。
- 比较 `varchar` 列与 `CAST(id AS CHAR)` 必须显式 `COLLATE utf8mb4_0900_as_cs`：
  库与表是 `utf8mb4_0900_as_cs`、连接是 `utf8mb4_0900_ai_ci`，两个 IMPLICIT
  排序规则相遇是**报错**而不是退化成一条规则（1267）。绑定参数不受影响——
  参数可强制性更高，会直接被列的排序规则采纳。
- `t_feedback_primary_problem_fact` 在 `application_resolution_status='RESOLVED'`
  时有四个非空要求（`chk_primary_fact_resolution`）：身份 id、公开 id 快照、
  展示名快照、映射版本必须同时给出。

## 13. 开关、回滚与未决项

**开关**（三层，全部默认关闭）：

| 层 | 键 | 默认 | 作用 |
|---|---|---|---|
| 调度层 | `xxl.job.enabled` | `false` | 决定两个 handler 是否装配 |
| 业务层 | `app.rca.intake-enabled` / `app.rca.worker-enabled` | `false` | 决定批次是否真的执行 |
| 配置层 | `app.rca.worker-id` | 空 | 空且开关打开时**必须**报 readiness 错误（`RcaWorker.init` 会 `require` 非空） |
| 通道层 | `app.mq.enabled` | `false` | 决定投递走 XXL 轮询还是 broker 消费者（第 12.6 节）。**打开它是启动依赖**：broker 不可达或主题未预建会让上下文 refresh 失败；切到 MQ 时轮询 handler 空转 |

前三层都开、且人工在 Admin 排一次调度，才会真的跑。**不存在「部署上去就自己开始跑」的路径。**
三个离线入口仍是独立的 Gradle 任务，不受这些开关影响。
`config/RcaEpisodeConfiguration` 与 `config/RcaRuntimeConfiguration` 注册的 Bean
会随应用启动实例化，但不被调用，无副作用。通道层是例外：它一旦打开，
生产者的连接与消费者的订阅就在 refresh 时真的建立（见 12.6 的失败模式）。

**业务层的开关不只是「决定跑不跑」，也决定 Bean 装不装配**（2026-10-09 修）。
`RcaWorkerScheduler` 依赖的 `RcaWorker` 现在带
`@ConditionalOnProperty(app.rca.worker-enabled=true)`，因为 `RcaWorker.init` 会
`require(workerId.isNotBlank())`（那是 fencing 的安全不变量，不是形式校验）：
无条件装配时，`app.rca.worker-id` 为空会让**两个开关全关的部署也起不来**——
本机实测生产启动方式直接 `Failed to instantiate [RcaWorker]: Worker 标识不能为空`，
而全仓测试仍绿，因为重上下文用例都带 `spring.main.lazy-initialization=true`，
惰性装配下没人去拿那个 Bean（生产是预先实例化单例）。修法只让那一个 Bean 缺席：
`RcaWorkerScheduler` 改用 `ObjectProvider<RcaWorker>`、开关关着时空转而不是缺席，
保住上面「handler 照常装配、误触发只是空转」的既有约定。
**边界**：`worker-enabled=true` 而 `worker-id` 仍空时依旧在这里快速失败，
只是 `RcaProperties` 会先打一行 `ready=false, errors=[RCA_WORKER_ID_MISSING]`
（两条实测日志见 `/home/iisc/fp-evidence/2026-10-09-rca-worker-conditional/`）。

**回滚**：两个 handler 的回滚是关开关（或把 manifest 两行去掉）；
`agent/rca/**` 的领域改动只影响离线任务与这条新链路，不影响既有业务链路。
V242 的 11 张新表是纯新增，不修改既有表结构（除 `t_ai_task` 加两列，
两列均有默认值且既有链路不读）。**运行态数据无需回滚。**
投递轨的回滚是把 `app.mq.enabled` 关回 `false`：轮询 handler 重新取件，
未投递的领域事件（`published_at IS NULL`）本来就还在库里，不会因为关轨而丢。

### 未决项（按优先级重排；第 1～3、6～9 项已解决，保留在此说明它们不是遗留问题）

1. ~~**生产入口缺失**~~ **已于 2026-09-28 解决**（第 11 节）。
   第 4、5、10～12 项为当前未决项；第 8 项已解决但留下了「未迁移清单」，
   那部分仍算未决，见第 8 项与第 12.7 节。
2. ~~**`t_rca_tool_call` 只读不写**~~ **已于 2026-09-29 解决**。写入侧落在
   `ToolInvocationGuard`（全部工具调用的唯一出口）上，形状是**两阶段写入**：
   `begin` 在发起上游请求**之前**落一条 `PENDING` 行，`commit` 在拿到结果后把它推到
   `COMMITTED`，中间那段就是崩溃窗口。三条性质从记录形状推出来，不依赖调用方自觉：
   崩在 `begin` 之前库里没有行（等价于「没调用过」，正确）；崩在两次写之间留下
   `PENDING` 且上游请求 ID 为空 → 恢复判定落到可重放（只读工具重放安全，
   且首次调用从未提交，不产生重复证据与重复扣费）；崩在 `commit` 之后按账本复用。
   复用与预算拒绝时**不写**，与「复用不产生新证据」同一条规则。
   `RcaRecoveryResolver.NONE` 已随之换成真实实现（见 11.3）。
   **实测**：`RcaToolCallJournalContractTest` 在真库上验了三态判定表里最容易错的三行
   （未落定→可重放、半提交→重放、账本齐→复用）、幂等（同轮次重复发起复用同一行）、
   参数快照可反推摘要、以及「库上说 `UNKNOWN` 不许被读成可重放」。
   离线侧新增 `ToolInvocationGuardTest` 验调用序列（`begin`→`invoke`→`commit`）与两条不写的路径。
   **仍属未决**：第 12 节那组 80 / 76 / 4 仍只由离线注入套件提供——
   新接上的是「记录能被写入并被读回并驱动判定」，不是「生产上真的发生过一次半提交恢复」。
3. ~~**`deadline_at` 没有写入口**~~ **已于 2026-09-29 解决**。写入点放在**领取语句本身**
   （`RcaTaskLeaseMapper.xml` 的 `claim`），形状是
   `deadline_at = IFNULL(deadline_at, NOW(3) + N 分钟)`，N 取 `app.rca.task-timeout-minutes`。
   四个决定与它们的理由：

   - **起算点是领取时刻，不是入库/到达时刻**。原未决项里写的那条禁令仍然成立：从到达时刻起算
     会让「入库超过 30 分钟后才被领取」的任务一创建就已超期。同一条配置现在有了两个消费方
     （库上的 `deadline_at` 与图内的 `RcaBudgetPolicy.production`），两者必须同源，
     否则现象是「库里 30 分钟就超期、图里按另一个数降级」，两个数字都对却不是一回事。
   - **`IFNULL` 而不是直接赋值**。这个上限是**整个任务**（跨多次领取、跨多次重试）的墙钟配额，
     不是每次领取的配额。直接赋值会让一个反复崩溃的任务每被领一次就续出新的 30 分钟，
     于是「任务超时」的实际上限取决于它崩溃了多少次。
   - **`NOW(3)` 在整条语句里只出现一次**，租约与截止时间由同一个时钟读数推出。
     两次读数会让「租约到期了但截止时间还没到」这类判断依赖执行顺序。
   - **截止时间不是候选条件**。原先的候选里有 `deadline_at IS NULL OR deadline_at > NOW(3)`，
     直接写上 `deadline_at` 会把超期任务变成「既不被执行、也不被收口」——因为它既不是终态、
     也不出现在任何待处理看板上。收口只有持租约的人做得了（`abandon` 的条件是 owner + generation），
     所以超期任务**仍然被领**，由 Worker 领取后立刻收口：有快照 → `PARTIAL`（保留证据，
     `abandon` 不碰 `state_snapshot`），没有快照 → `NEEDS_HUMAN`（`PARTIAL` 会谎称「有可用证据」）。
     `error_code = RCA_DEADLINE_EXCEEDED` 与图内超期（同一个 `stop_reason`）区分开。
   - 读回的 `deadline_remaining_ms` **保留符号**（超期为负），与夹到 0 的 `lease_remaining_ms`
     刻意不对称；`NULL`（从未设过）与负数（已超期）是两件事。

   **实测**：`RcaTaskLeaseContractTest` 在真库 3307 上验了「首次领取补上且与租约同一次读数推出」
   （断言两个剩余量之差精确等于 `N*60-180` 秒）、「再次领取不会续一个新的截止时间」、
   以及带符号读回的三种取值；`RcaWorkerEndToEndTest` 在生产路径上断言领取到的任务
   `deadline_at` 就是配置里那个值（配置取 17 分钟，与默认 30 和一个测试用的 11 都不同）；
   `RcaWorkerDeadlineTest`（6 项，无 Spring 无库）验超期不进图、两种收口分支、
   边界取「刚好到期也算」、以及**超期判定排在恢复判定之前**（对账口将来是一次网络调用）。
   **仍属未决**：`RcaBudgetPolicy.policyVersion` 无消费方，见第 12 项。
4. **`t_rca_agent_step` 与 `t_rca_claim_evidence` 的读侧仍无写入方**。
   `t_rca_claim_evidence` 的**写侧已有**（`RcaPersistenceContractTest` 已覆盖），
   缺的是「单步执行记录」在生产上不落库；不影响终态与归因落库。
5. **五个上游证据源无数据落点**（第 11.4 节）。这是「结论只能是证据不足」的直接原因，
   也是当前**最有价值**的下一步：接上一个真实上游，结论强度就会有实质变化。
6. ~~**无链路追踪**~~ **已于 2026-09-29 解决**。V243 在 `t_rca_agent_task`
   与 `t_rca_tool_call` 上加了链路列（形状与四条口径见第 3 节，传播路径见第 5 节）。
   三个决定与它们的理由：**`trace_id` 在领取时补且只补一次**（`claim` 里的 `IFNULL`），
   因为任务可能被另一个 Worker 接管续跑，各处自行生成会把一次执行劈成两条链路；
   **`span_id` 由四要素确定性推导而不是随机**，因为工具调用的去重键
   （任务 + 工具 + 参数摘要 + 轮次）本身就定义了「同一次逻辑调用」；
   **两列为空时不兜底**，否则「这次执行有没有链路标识」将永远无法回答。
   `t_rca_agent_step` **刻意不加**（它没有写入方，见第 4 项）。
   链路列**只写不读**：`listByTask` 的产物是恢复判定的输入，链路标识是排障信息，
   读进共享状态会改变快照字节。
   **实测**：`rcaEntryTest` 18 → 25 项（形状、只补一次、NULL 不兜底、库上约束真的生效、
   两列同进同出），离线新增 13 项（推导确定性、守卫调用序列、Worker→节点上下文透传）。
   **仍属未决**：真库里 `t_rca_tool_call` 仍是 0 行（第 11.4 节），
   所以验的是持久化契约层，不是「生产上真的按 trace 串出过一次调用链」。
7. ~~**无 reranker**~~ **已于 2026-09-29 解决**。**本项不属于本包**（它说的是集群检索侧的
   `HybridCandidateRetriever`），列在这里只是因为同一份未决清单里有它。实现形状：
   `Reranker` 端口插在 RRF 融合之后、`.take(topK)` 截断**之前**（放在截断之后的话，
   重排拿不到它唯一能提供的东西——把一条本该被截掉的候选提上来）；`GuardedReranker`
   由 `HybridCandidateRetriever` **在构造时**套上，保证重排只能换顺序、不能改集合
   （集合承载着「返回条数 == 请求条数 ⇒ 召回被截断」这条判断）；生产实现
   `LlmCandidateReranker` 复用 `LlmClient.decideStructured`，只重排头部窗口，
   **有候选拿不到可外发正文时整次跳过**（拿不到正文多半意味着脱敏未 PASS，
   在信息不全的集合上排序，模型给出的顺序会被当成可信的）；开关
   `app.ai.rerank.enabled` **默认关闭**，因为开启会让每次归簇召回多一次**同步**模型调用、
   且发生在 `ClusteringEngine` 的事务持有期间。**仍未做**：没有在真库上跑过一次开启状态的重排，
   也没有在真实候选集上评估过重排质量——验的是端口、守卫、降级与装配，不是效果。
   实现与实测的数字记在 `docs/reference/feedback-cluster-classification-code-flow.md`
   的「重排」一节，原始日志在 `/home/iisc/fp-evidence/2026-09-29-rerank/`。
8. ~~**单租户**。`tenant_key` 为占位，无 `tenant_id`~~ **已于 2026-10-09 解决**
   （第 12.7 节）。V245 前向迁移把租户从「一个没人判定的列」变成可判定的维度：
   租户注册表 `t_auth_tenant`、账号侧的 `t_auth_user.tenant_key`（会话主体的租户来源）、
   数据侧的 `t_application_identity.tenant_key`（权威锚点）、
   `chk_data_perm_scope` 放开加入 `TENANT`。四个决定与它们的理由：
   **`t_feedback_source` 不加租户列**，租户是派生事实（来源 → 主问题事实 → 应用身份，
   两跳主键查找），冗余成列就有一个必然滞后的缓存，且应用身份改租户后存量行不会自愈；
   **`permitsTenant` 与 `permits` 分开**而不是给 `permits` 加带默认值的参数，
   否则全部现存调用点会静默变成「不检查租户」；**没有 TENANT 授权行时只看自己租户**
   而不是拒绝全部，照抄渠道的默认会让 V245 上线当天全部存量用户看不到任何数据；
   **跨租户报 `FEEDBACK_NOT_FOUND`** 而不是拒绝，否则报错本身就是
   「这个 ID 在别的租户里存在」的探测器。写入侧是唯一真的新增了写入方的地方
   （`UserCreateInput.tenantKey` → 事务内注册表校验 → `AuthUserMapper.insertLocal`，
   该列唯一写入方且刻意不 COALESCE 兜底）。
   **实测**：门控用例 `TenantIsolationEndToEndTest`（真库 3307）1 项 0 失败，
   六阶段覆盖默认收紧 / 显式放开 / 改判 DENY / 通配 ALLOW + DENY 失败关闭 /
   接入侧与守卫同源 / 未注册租户被拒且不留痕；`rcaEntryTest` 45 → **46 项**、
   `:backend:test` 1507 项、`:backend:architectureTest` 79 项全绿、离线 30/1301/74 不变。
   **仍属未决（第 12.7 节「未迁移清单」）**：租户判定只落在 `FeedbackAccessGuard` 一处，
   另有 7 处 `permits(...)` 路径（含集群写路径 `ClusterMutationAccessGuard`）不看租户；
   列表与问题分析读路径的投影表**没有租户列**，单库多租户下会跨租户混行（未实测）；
   MQ 载荷不带租户；**给应用身份分配租户、以及租户本身的建立/停用都没有入口**
   （门控用例扮演的正是那个运维角色）；`uk_subject_scope` 使同一 scope 值的
   ALLOW/DENY 在库层面无法并存；审计表只追加，本机部署库里有一行删不掉的测试残留。
9. ~~**事件溯源：只有落库、没有投递**~~ **已于 2026-10-09 全部解决**
   （落库在前半，MQ 投递在后半）。

   **落库（2026-10-09 前半）**：`V244__rca_domain_event_journal.sql` 建 `t_rca_domain_event`；
   `event_id` 是**确定性**的（`sha256(聚合|版本|类型|载荷摘要)` 前 32 位，
   与 `EvidenceIdFactory` 同源），所以崩溃恢复重入同一节点时唯一键原样命中、
   `INSERT IGNORE` 静默跳过，不会把「重放」记成「又发生了一次」；
   `aggregate_version` 取任务的 `state_version`（**不是**「一事件一版本」——
   改成那样会同时改掉 `RcaBoundaryWriter` 的令牌推进与 `RcaClaimedTask` 的读回口径），
   代价写明：同一版本可以有多条事件，版本号也不再能用来判断事件有没有丢，
   聚合内顺序由 `(aggregate_version, id)` 决定。事件在**与状态迁移同一个事务**里写入，
   写入点两处：`RcaTaskLeaseAdapter` 的四个写方法（本次给 `writeBack` / `releaseForRetry` /
   `abandon` 补上了 `@Transactional`，此前只有 `claim` 有）与
   `MyBatisRcaPersistenceRepositoryImpl.saveArbitration`；被 fencing 拒绝的写回**不发事件**
   （那段迁移在世界上没有发生）。六种事件类型：`TASK_CLAIMED` / `TASK_RELEASED` /
   `TASK_STATE_TRANSITIONED` / `TASK_SETTLED` / `TASK_ABANDONED` / `ATTRIBUTION_RECORDED`；
   其中 `TASK_RELEASED` 是 `TASK_CLAIMED` 的必要对偶——没有它，
   「领了三次都没说结果」在崩溃与正常转交之间无法区分。
   `occurred_at` / `published_at` 一律由 SQL 的 `NOW(3)` 写下（本包的时间惯例），
   载荷**不含正文、联系方式、指纹、日志内容与错误详情**（`errorDetail` 不进载荷），
   因为它会被中继原样发到 MQ。
   **实测**：`rcaEntryTest` 新增 16 项（真实 3307 上的写入/读回、跨事务重放只留一行、
   与迁移同版本、回滚同生共死、无外层事务时适配器自开事务、两条 CHECK 真的在库上生效、
   待投递扫描与幂等标记），单元新增 29 项（迁移文本 8 + 适配器发不发/发什么 10 + 写入侧 11）；
   `:backend:test` 1506 项、`:backend:architectureTest` 79 项全绿；
   三个离线套件 30 / 1301 / 74 一个不少。

   **MQ 投递（2026-10-09 后半）**：`t_rca_domain_event.published_at` 从此有了写入者。
   中继是 `agent/rca/runtime/RcaDomainEventRelay`，由 XXL handler
   `feedbackRcaEventRelayHandler` 驱动，总开关 `app.mq.enabled`（默认关闭）。
   它与另外两条既有发件箱（通知、索引）共用 `com.feedback.messaging` 这一层通道，
   但三条轨各自读自己的表、各自用自己的批量，**不共享中继**——通知与索引的投递单位是
   「一条待投递的投递记录」，领域事件的投递单位是「一条已落库的事实」，
   混在一起会让「谁在重试、重试到第几次」变成一笔糊涂账。
   三个决定：**按自增 id 升序逐条发、失败即停本轮**——继续发下一条会让订阅方先看到
   后发生的事件，而顺序错乱从那一刻起不可修复（订阅方无从知道要等多久）；
   代价是队首阻塞，所以 `app.mq.relay-batch-size` 必须明显大于正常情况下一次能发完的量。
   **发送与状态迁移不同事务**——在写事件的那一刻顺手发 MQ 会丢掉发件箱模式的全部价值：
   事务回滚时已发出的消息收不回来，订阅方会收到一条「对应的迁移从未发生」的事件。
   **本进程不订阅这个主题**——它是给进程外订阅方用的；自己发自己收会让
   「事件已经离开本进程」这件事变得无法证明，`MqTrackEndToEndTest` 有一条断言专门盯着它。
   载荷**不含正文、联系方式、指纹、日志内容与错误详情**（`errorDetail` 不进载荷），
   因为它会原样离开本进程。三条轨的切换口径、broker 侧的运维前提与实测数字见**第 12.6 节**。

   **仍属未决**：本机端到端里那个「进程外订阅方」是测试里的探针，生产上还没有任何订阅方；
   broker 是单节点、无鉴权、无死信消费者（重投次数用尽的消息进 `%DLQ%<组名>` 后无人处理，
   只在日志里留 ERROR）。
10. **成本预算未与真实计费对账**。`costBudgetUsd` 是预算字段，离线回放无真实计费来源。
11. **`HistoricalCasePort` 的 `verificationPassed` 是合成语义**
    （`PUBLISHED` + `reviewed_at`），不是真的「发布后验证」列。
    这是当前唯一「接了真数据」的上游端口，因此这条合成口径必须写明，避免被当成真实字段。
12. **`RcaBudgetPolicy.policyVersion` 没有消费方**（做第 3 项时顺带发现的）。
    该字段的 KDoc 写着「落库到任务快照」，但这句话**今天并不成立**：
    `t_rca_agent_task` 没有对应的列，任务快照里也没有它，全仓读它的地方为零。
    它现在是一条**只存在于内存对象里**的常量。这不是本次改动引入的，改动只是让它的
    KDoc 不再宣称一件没做的事；写进未决项是因为「结论由哪一版预算策略算出」
    在复盘时是个真问题——同一份证据、两个不同的 `policyVersion`，结论不可比。
    修法二选一：要么在任务快照里真的落上它（需要一次迁移），要么删掉这个字段。
