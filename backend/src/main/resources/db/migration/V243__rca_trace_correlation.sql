-- RCA 运行时的链路关联标识：一次任务执行一个 trace，一次工具调用一个 span。
--
-- 为什么需要这个迁移（先写清不成立的前提，再写成立的部分）：
--   本仓库**已经有**一套请求关联标识，但它只覆盖 HTTP：`config/ApiObservabilityFilter.kt`
--   把 `X-Request-Id` 放进 MDC 的 `requestId` 键，`common/ApiResponse.kt` 再把它回显到每个响应体，
--   `MyBatisObservabilityInterceptor` 也把它带进慢查询/失败日志。而 RCA 的执行侧是
--   **XXL-Job 触发 → Worker 领任务 → 状态图 → 工具调用**，全程不经过 `/api/`，
--   于是这条链路上的所有库写入与日志在关联上都是空白——`requestId()` 在那里恒为字面量
--   `"background"`。本次迁移补的就是这一段。
--
-- 设计边界（与本迁移的 CHECK 约束一一对应，不要放宽）：
--   1. **trace 属于任务，不属于某次领取**。一个任务崩溃三次、被领取三次，是**一条** trace：
--      否则「这个任务重试过几次、每次到哪一步死的」在数据上会散成三条互不相关的记录，
--      而它恰恰是排障时唯一要看的东西。写入点因此是 `claim` 语句里的
--      `IFNULL(trace_id, ...)`，与 `deadline_at` 同一个形状、同一个理由：
--      领取时才补、且只补一次。
--   2. **span 属于工具调用，且必须是确定性的**。`t_rca_tool_call` 上「同任务+同工具+同参数摘要+同轮次」
--      是**同一次逻辑调用**（这是既有的去重键 `idx_rca_tool_dedup` 的语义），
--      所以它的 span 也必须相同。若用随机数，崩溃重放后同一逻辑调用会得到两个 span，
--      「这条记录是不是同一次调用」就再也无法从数据上判断，去重键的意义被架空。
--   3. **两列都可空、且不回填历史行**。743 条夹具任务与它们之前的工具调用发生在
--      「链路标识存在」之前，给它们编一个 trace_id 等于伪造历史；NULL 的含义是
--      「这一行早于该标识的引入」，这是可解释的诚实取值。
--   4. **宽度照抄 W3C Trace Context**（trace-id 32 位十六进制、span-id 16 位十六进制），
--      不自定义格式：接入任何链路追踪后端时都不需要再做一次转换。
--   5. **不加在 `t_rca_agent_step` 上**。那张表今天**没有任何写入侧**（只有 DDL），
--      往里加列会得到一组「没有写入方、也没有读取方」的列——正是本项目已经吃过一次亏的
--      `RcaBudgetPolicy.policyVersion` 的形状。步骤级的 span 属于「给该表补写入侧」那件事，
--      不混进本迁移。
--
-- 今天谁读这两列：**排障时的人手 join**（按 trace_id 捞出一次任务执行的全部工具调用），
-- 以及 `:backend:rcaEntryTest` 的读回断言。程序侧暂无读取路径——
-- 要等接入链路追踪后端或日志聚合之后才会出现。这一条必须写在这里，
-- 免得下一个人把它当成「已经有了链路追踪」的证据。

-- ---------------------------------------------------------------------------
-- 1. 任务级 trace：一次任务执行的全部动作共用一个标识
-- ---------------------------------------------------------------------------
-- 可空而不是 NOT NULL DEFAULT：新行由 claim 语句补上（见第 2 节），
-- 而历史行保持 NULL。「有默认值」会让历史行看起来像是抽到过一个标识，那是假的。
ALTER TABLE t_rca_agent_task
  ADD COLUMN trace_id CHAR(32) DEFAULT NULL
    COMMENT '链路追踪标识；领取时补上且只补一次，同一任务跨多次重试保持同一个值' AFTER attempt_count,
  ADD CONSTRAINT chk_rca_agent_trace CHECK
    (trace_id IS NULL OR trace_id REGEXP '^[0-9a-f]{32}$');

-- 领取补标识的索引不是必需的（任务行本身按主键访问），这里刻意不加：
-- 多一个索引就多一份写入成本，而它的查询形态（按 id 取一行）已经由主键覆盖。

-- ---------------------------------------------------------------------------
-- 2. 工具调用级 trace + span：把一次调用钉在它所属的那次任务执行上
-- ---------------------------------------------------------------------------
-- trace_id 在 `t_rca_tool_call` 上是**冗余列**（任务行已经有）而不是外键派生：
-- 一张调用记录要能被单独交出去——贴一条行给上游、写进工单——接收方必须能从这一行
-- 直接知道它属于哪次执行，否则每一条记录都得连带一次任务表查询。冗余的是列，
-- 不是事实来源：两者的值由同一个流程写入，不存在分别维护的可能。
ALTER TABLE t_rca_tool_call
  ADD COLUMN trace_id CHAR(32) DEFAULT NULL
    COMMENT '冗余自 t_rca_agent_task.trace_id，使单行调用记录可以脱离任务表被引用' AFTER params_snapshot,
  ADD COLUMN span_id CHAR(16) DEFAULT NULL
    COMMENT '本次调用的 span；由 trace+工具+参数摘要+轮次确定性推导，同一次逻辑调用重放后不变'
    AFTER trace_id,
  ADD CONSTRAINT chk_rca_tool_trace CHECK
    (trace_id IS NULL OR trace_id REGEXP '^[0-9a-f]{32}$'),
  ADD CONSTRAINT chk_rca_tool_span CHECK
    (span_id IS NULL OR span_id REGEXP '^[0-9a-f]{16}$'),
  ADD CONSTRAINT chk_rca_tool_trace_span_pair CHECK
    ((trace_id IS NULL) = (span_id IS NULL)),
  ADD KEY idx_rca_tool_trace (trace_id, span_id);

-- 上面那条 pair 约束值得单独说明：只校验两列的格式，就允许出现「有 span 没 trace」
-- 这种无法解释的行——span 是从 trace 推出来的，没有 trace 就没有 span。
-- 两列必须同时有值或同时为空，这是一条真正的不变式，而不是格式问题。
