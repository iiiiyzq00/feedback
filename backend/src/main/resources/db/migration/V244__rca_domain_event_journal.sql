-- RCA 领域事件日志：给运行时的每一次状态迁移一个稳定标识与一个在聚合内的位置。
--
-- 为什么需要这个迁移（先说清今天缺的是什么）：
--   RCA 运行时今天是**只存最终状态**的。`t_rca_agent_task` 上留下的是「现在是什么状态、
--   停在哪个节点、快照是什么」，而「它是怎么走到这里的」只剩下两处间接痕迹：
--   `state_version` 这个计数，以及各子表里带 `created_at` 的行。前者只说「迁移过 7 次」，
--   说不出第 3 次迁移是哪个节点、哪一轮；后者散落在四五张互不引用的表里。
--   离线回放套件能重放，是因为它把步骤留在**内存**里（`RcaReplayMain` 打印的
--   `v3→v4 12ms` 那类行），生产路径上这些步骤**一行都不落库**。
--   后果具体到排障就是：一个 `NEEDS_HUMAN` 的任务，事后无法回答「它试过哪些节点、
--   在哪个节点上把预算烧完的」——只能靠日志，而日志会轮转。
--
-- 设计边界（与本迁移的约束一一对应，不要放宽）：
--
--   1. **事件属于任务，`aggregate_version` 就是任务的 `state_version`，不是另造的序号。**
--      教科书式事件溯源要求「一个事件 = 版本 +1」，本表**刻意不这么做**：
--      `state_version` 的语义是「状态被持久化迁移过几次」（`writeBack`/`abandon` 各 +1，
--      `claim`/`releaseForRetry` 不动它），它是快照恢复机制的一部分，
--      改成「一事件一版本」会同时改掉 `RcaBoundaryWriter` 的令牌推进与
--      `RcaClaimedTask` 的读回口径——那是一场与「记录历史」无关的高风险改动。
--      代价必须写明：**同一版本号上可以出现多条事件，版本号也不再能用来判断事件有没有丢**。
--      聚合内的顺序由 `(aggregate_version, id)` 决定，其中 `id` 是自增的全局写入顺序。
--
--   2. **`event_id` 是确定性的，不是随机的。** 由
--      `sha256(aggregate_type|aggregate_id|aggregate_version|event_type|payload 摘要)` 取前 32 位。
--      理由与 `EvidenceIdFactory`、`ToolSpanIdFactory` 同源：**同一次逻辑事件必须得到同一个
--      标识**。重放（崩溃恢复会重新进入同一个节点）之后若标识变了，「这条记录是不是同一次
--      迁移」就再也无法从数据上判断，去重与对账全部失去依据。随机 UUID 会让每次重放
--      都长出一条新事件，把「历史」变成「日志」。
--      确定性标识同时让写入可以幂等：唯一键冲突即「这件事件已经记过了」，直接忽略。
--
--   3. **只在状态真的落库成功之后写。** 事件不是「打算做什么」，是「已经发生了什么」。
--      写在与状态迁移**同一个事务**里，写入点是两处、都是本迁移引入 `@Transactional` 的地方：
--      `RcaTaskLeaseAdapter` 的四个写方法（claim / writeBack / releaseForRetry / abandon），
--      以及 `MyBatisRcaPersistenceRepositoryImpl.saveArbitration`（归因那条路径）。
--      因此不存在「状态变了但没有事件」或反过来的窗口——这正是后面接 MQ 时
--      「先落库再投递」能成立的前提（本地事务是唯一事实源，消息只是通知）。
--      被 fencing 拒绝的写回（影响行数为 0）**不产生事件**：那个状态迁移在世界上没有发生。
--      `saveArbitration` 是六个写方法里唯一被包上事务的一个，理由见该类的类文档：
--      「归因已写库、任务行未置终态」这个崩溃窗口**不受影响**，被包起来的是方法内部的三条语句。
--
--   4. **不发事件到 `t_rca_agent_step`。** 那张表今天仍然没有写入侧（只有 DDL），
--      本次也不给它补——领域事件与步骤记录不是一回事：步骤是执行细节，
--      事件是聚合的状态迁移。把两者合并会让「一次迁移」与「一次节点执行」的
--      多对多关系（一个节点边界可能对应零次或多次迁移）被压成一个错误的一对一。
--
--   5. **`published_at` 是投递标记，属于传输，不属于领域。** 它由 MQ 中继独占写入；
--      领域侧永远不读它、也不写它。这一列留在这里而不是另开一张表，
--      是因为「哪条事件还没发出去」必须与事件本身在同一个事务里可判定——
--      分成两张表就必然出现「事件已提交、发送标记未提交」的窗口，
--      而那正是发件箱模式要消灭的东西。
--
-- 今天谁读这张表：**排障与对账的人手查询**（按 `task_id` 捞一次任务的全部迁移），
-- 以及 `:backend:rcaEntryTest` 的读回断言；MQ 开启时另有一个中继按
-- `idx_rca_domain_event_publish` 扫描未投递行。程序侧暂无「回放事件重建状态」的读取路径——
-- 状态重建仍然走 `state_snapshot`，这一点必须写清楚，免得把它当成
-- 「已经可以用事件重放整条链路」的证据。

-- ---------------------------------------------------------------------------
-- 1. 事件日志本体
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_domain_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id CHAR(32) NOT NULL
    COMMENT '确定性事件标识，sha256(聚合|版本|类型|载荷摘要) 前 32 位；重放后不变，可作 MQ 幂等键',
  aggregate_type VARCHAR(32) NOT NULL
    COMMENT '聚合类型；今天只有 RCA_AGENT_TASK，留作将来接入其它聚合的扩展点',
  aggregate_id BIGINT NOT NULL COMMENT '聚合标识；今天等于 task_id',
  aggregate_version BIGINT NOT NULL
    COMMENT '事件发生时聚合的版本。取任务的 state_version，因此同一版本可有多条事件、也不可据它判断事件是否丢失',
  event_type VARCHAR(48) NOT NULL
    COMMENT 'TASK_CLAIMED/TASK_RELEASED/TASK_STATE_TRANSITIONED/TASK_SETTLED/TASK_ABANDONED/ATTRIBUTION_RECORDED；取值与 RcaEventType.storageCode 一一对应，属对外契约',
  task_id BIGINT DEFAULT NULL
    COMMENT '冗余自 aggregate_id，使单行事件可以脱离聚合标识被直接引用与级联',
  episode_id BIGINT DEFAULT NULL COMMENT '冗余自任务行，便于按会话捞事件而不必先查任务表',
  trace_id CHAR(32) DEFAULT NULL
    COMMENT '冗余自 t_rca_agent_task.trace_id；任务行上为 NULL 的历史行在这里同样是 NULL，不编造',
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    COMMENT '事件落库时刻，由 SQL 的 NOW(3) 写下（与任务行的 updated_at 同一个时钟，不是同一个读数：两者可比，同毫秒内无法定序）',
  payload JSON DEFAULT NULL
    COMMENT '事件细节（节点、状态、停止原因、归因等级等）。**不含正文与用户标识**：这一列会被中继原样发到 MQ',
  published_at DATETIME(3) DEFAULT NULL COMMENT 'MQ 中继写入；NULL 表示尚未投递。领域侧不读不写',
  publish_attempts INT NOT NULL DEFAULT 0 COMMENT 'MQ 投递尝试次数；只由中继递增',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rca_domain_event_id (event_id),
  KEY idx_rca_domain_event_aggregate (aggregate_type, aggregate_id, aggregate_version, id),
  KEY idx_rca_domain_event_publish (published_at, id),
  KEY idx_rca_domain_event_task (task_id, id),
  CONSTRAINT fk_rca_domain_event_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT chk_rca_domain_event_id CHECK (event_id REGEXP '^[0-9a-f]{32}$'),
  CONSTRAINT chk_rca_domain_event_version CHECK (aggregate_version >= 0),
  CONSTRAINT chk_rca_domain_event_trace CHECK (trace_id IS NULL OR trace_id REGEXP '^[0-9a-f]{32}$'),
  CONSTRAINT chk_rca_domain_event_publish_attempts CHECK (publish_attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='RCA 领域事件；与状态迁移同事务写入，确定性 event_id 使重放不产生新事件';

-- 唯一键只加在 event_id 上，**刻意不加** (aggregate_type, aggregate_id, aggregate_version)：
-- 第 1 条边界说了同一版本可以有多条事件，再加一个 (聚合, 版本) 唯一键就等于
-- 用一个数据库约束去否定那条设计——它会在第一个「同一版本两条事件」的场景上
-- 以「插入失败」的形式炸掉，而那个场景是正常的（领取与首个节点边界同版本）。
-- 幂等靠 event_id：它是由内容确定性推导出来的，同一次逻辑事件必然同值。

-- 没有 `is_deleted` 之类的软删列：事件日志一旦写入就不该被改写或抹掉。
-- 真要清理只有随任务级联删除一条路（外键 ON DELETE CASCADE），
-- 与 `t_rca_tool_call` 同一条约定。
