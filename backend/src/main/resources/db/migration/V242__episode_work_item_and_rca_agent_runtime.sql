-- 学习机智能排障 Agent：问题对象化（Episode/WorkItem）、受限 Agentic RCA 运行时、
-- 证据账本、归因分级与引用支持校验。
--
-- 设计边界（与本迁移的 CHECK 约束一一对应，不要放宽）：
--   1. 原始信号永不删除：t_feedback_episode_member 只追加 membership_revision，
--      历史 revision 以 membership_status='SUPERSEDED' 保留，不做原地更新。
--   2. Episode 不是按设备 group by：分区键是租户+应用+标准化问题键，会话边界由可信身份
--      和事件时间决定；身份等级不同不能互相冒充，所以 identity_level 必须落库。
--   3. repeat_count、distinct_user_count、distinct_device_count 分开统计，禁止相加。
--   4. 范围性 claim 必须引用 cohort 证据：引用支持校验读 t_rca_evidence.coverage_type。
--   5. 旧 generation 的 Worker 写回必须被 fencing 拒绝：t_rca_agent_task 同时带
--      lease_owner + lease_generation + state_version 三个写回条件。
--   6. 模型不能直接改正式告警/Incident/权限：本迁移不新增任何可被 Agent 直接写的告警字段。

-- ---------------------------------------------------------------------------
-- 1. 问题对象（Episode）：单条异常流的可追踪实体，在接入层创建
-- ---------------------------------------------------------------------------
CREATE TABLE t_feedback_episode (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL,
  tenant_key VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '租户口径；当前单租户部署保留该维度',
  app_id VARCHAR(50) NOT NULL,
  problem_key VARCHAR(200) NOT NULL COMMENT '标准化问题键，分区用；与错误签名变化都可开启新 generation',
  error_signature VARCHAR(500) DEFAULT NULL COMMENT '归一后的稳定错误签名；为空表示尚未归一',
  generation INT NOT NULL DEFAULT 1 COMMENT '问题键或错误签名明显变化时递增，不静默混进旧会话',
  identity_level VARCHAR(20) NOT NULL COMMENT '可信身份等级，高等级不能与低等级合并',
  identity_fingerprint BINARY(32) NOT NULL COMMENT 'HMAC-SHA256，与 t_feedback_identity 同口径',
  session_state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  session_opened_at DATETIME(3) NOT NULL COMMENT '首个成员的事件时间',
  session_last_event_at DATETIME(3) NOT NULL COMMENT '末个成员的事件时间，inactivity 判定基准',
  session_closed_at DATETIME(3) DEFAULT NULL,
  repeat_count INT NOT NULL DEFAULT 0 COMMENT '重复上报次数，只增不与其他计数相加',
  distinct_user_count INT NOT NULL DEFAULT 0 COMMENT '仅按可信账号/联系人去重',
  distinct_device_count INT NOT NULL DEFAULT 0 COMMENT '仅按授权设备去重；同设备四次上报只计一',
  member_revision BIGINT NOT NULL DEFAULT 0 COMMENT '当前 membership revision，追加时递增',
  policy_version VARCHAR(20) NOT NULL COMMENT '会话化策略版本（inactivity gap、reopen 窗口随其保存）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_episode_partition (tenant_key, app_id, problem_key, identity_fingerprint, generation),
  KEY idx_episode_session (session_state, session_last_event_at, id),
  KEY idx_episode_signature (app_id, error_signature),
  CONSTRAINT chk_episode_generation CHECK (generation >= 1),
  CONSTRAINT chk_episode_identity_level CHECK
    (identity_level IN ('ACCOUNT','AUTHORIZED_DEVICE','CHANNEL_CONTACT','ANONYMOUS')),
  CONSTRAINT chk_episode_session_state CHECK (session_state IN ('OPEN','CLOSED','REOPENED')),
  CONSTRAINT chk_episode_counters CHECK
    (repeat_count >= 0 AND distinct_user_count >= 0 AND distinct_device_count >= 0 AND member_revision >= 0),
  CONSTRAINT chk_episode_time_order CHECK (session_last_event_at >= session_opened_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='问题对象：单条异常流的可追踪实体，原始信号不被删除';

-- ---------------------------------------------------------------------------
-- 2. 问题对象成员（Episode ↔ 原始信号）：append-only，迟到事件追加 revision
-- ---------------------------------------------------------------------------
CREATE TABLE t_feedback_episode_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  episode_id BIGINT NOT NULL,
  source_id BIGINT NOT NULL COMMENT '软关联 t_feedback_source.id；原始信号不删除',
  membership_revision BIGINT NOT NULL COMMENT '追加式；同一 source 可因重新归因出现多条 revision',
  membership_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  identity_level VARCHAR(20) NOT NULL COMMENT '该成员判定时实际可用的身份等级',
  join_reason VARCHAR(30) NOT NULL COMMENT '为何被并入：SESSION_GAP 内 / 问题键命中 / 人工归并 / 迟到追加',
  event_time DATETIME(3) NOT NULL COMMENT '成员自身事件时间，窗口重算基准，不用 as-of 时间替代',
  is_duplicate_endpoint TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为重复端点；重复仍保留原始信号',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_episode_member_revision (episode_id, source_id, membership_revision),
  KEY idx_episode_member_active (episode_id, membership_status, event_time),
  KEY idx_episode_member_source (source_id, membership_status),
  CONSTRAINT fk_episode_member_episode FOREIGN KEY (episode_id)
    REFERENCES t_feedback_episode(id),
  CONSTRAINT fk_episode_member_source FOREIGN KEY (source_id)
    REFERENCES t_feedback_source(id),
  CONSTRAINT chk_episode_member_revision CHECK (membership_revision >= 0),
  CONSTRAINT chk_episode_member_status CHECK (membership_status IN ('ACTIVE','SUPERSEDED')),
  CONSTRAINT chk_episode_member_identity CHECK
    (identity_level IN ('ACCOUNT','AUTHORIZED_DEVICE','CHANNEL_CONTACT','ANONYMOUS')),
  CONSTRAINT chk_episode_member_join_reason CHECK
    (join_reason IN ('SESSION_GAP','PROBLEM_KEY_HIT','MANUAL_MERGE','LATE_APPEND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='问题对象成员，append-only；迟到数据产生新 revision 不覆盖历史';

-- ---------------------------------------------------------------------------
-- 3. 可处理工作项（WorkItem）：每个可处理 Episode 立即创建，不依赖是否告警
-- ---------------------------------------------------------------------------
CREATE TABLE t_feedback_work_item (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL,
  episode_id BIGINT NOT NULL,
  work_item_status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  owner_engineer_id BIGINT DEFAULT NULL COMMENT '软关联 t_auth_user.id；认领后由 RCA 追加为负责人',
  incident_id BIGINT DEFAULT NULL COMMENT '软关联 t_incident.id；RCA 归因后回填，不在接入期创建',
  alert_instance_id BIGINT DEFAULT NULL COMMENT '软关联 t_alert_instance.id；告警不是创建 WorkItem 的前提',
  first_seen_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  reopen_count INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_work_item_episode (episode_id),
  KEY idx_work_item_owner (owner_engineer_id, work_item_status, last_seen_at),
  KEY idx_work_item_incident (incident_id, work_item_status),
  CONSTRAINT fk_work_item_episode FOREIGN KEY (episode_id) REFERENCES t_feedback_episode(id),
  CONSTRAINT fk_work_item_alert FOREIGN KEY (alert_instance_id) REFERENCES t_alert_instance(id),
  CONSTRAINT chk_work_item_status CHECK
    (work_item_status IN ('OPEN','INVESTIGATING','PENDING_REVIEW','RESOLVED','CLOSED','REOPENED')),
  CONSTRAINT chk_work_item_reopen CHECK (reopen_count >= 0),
  CONSTRAINT chk_work_item_time_order CHECK (last_seen_at >= first_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='可处理工作项；无告警的反馈也会立即创建并允许 RCA 先行';

-- ---------------------------------------------------------------------------
-- 4. RCA 父任务：租约 + generation + state_version 三重 fencing
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_agent_task (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  public_id BINARY(16) NOT NULL,
  task_key VARCHAR(160) NOT NULL COMMENT '业务幂等键，同一 Episode 同策略版本只允许一个活跃任务',
  episode_id BIGINT NOT NULL,
  cluster_id BIGINT DEFAULT NULL COMMENT '软关联 t_cluster.id；联合调查候选，不代表已确认同根因',
  task_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  current_node VARCHAR(30) NOT NULL DEFAULT 'CONTEXT_BUILD',
  state_version BIGINT NOT NULL DEFAULT 0 COMMENT '每次状态迁移递增；写回必须匹配，防旧快照覆盖新状态',
  lease_owner VARCHAR(100) DEFAULT NULL,
  lease_generation BIGINT NOT NULL DEFAULT 0 COMMENT '每次 claim 递增；旧 generation 的写回影响行数为 0',
  lease_until DATETIME(3) DEFAULT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  deadline_at DATETIME(3) DEFAULT NULL,
  budget_max_steps INT NOT NULL DEFAULT 12,
  budget_max_tokens INT NOT NULL DEFAULT 60000,
  budget_max_cost_usd DECIMAL(10,6) NOT NULL DEFAULT 0.500000,
  steps_used INT NOT NULL DEFAULT 0,
  tokens_used INT NOT NULL DEFAULT 0,
  cost_used_usd DECIMAL(10,6) NOT NULL DEFAULT 0,
  stop_reason VARCHAR(40) DEFAULT NULL COMMENT '结构化停止原因；模型只能提交建议，由编排器落定',
  state_snapshot JSON DEFAULT NULL COMMENT '当前 RCA 共享状态（六块），节点边界写入',
  snapshot_sha256 CHAR(64) DEFAULT NULL,
  model_version VARCHAR(100) DEFAULT NULL,
  prompt_version VARCHAR(20) DEFAULT NULL,
  error_code VARCHAR(100) DEFAULT NULL,
  error_detail VARCHAR(1000) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rca_agent_task_key (task_key),
  KEY idx_rca_agent_claim (task_status, lease_until, deadline_at, id),
  KEY idx_rca_agent_episode (episode_id, created_at),
  CONSTRAINT fk_rca_agent_task_episode FOREIGN KEY (episode_id) REFERENCES t_feedback_episode(id),
  CONSTRAINT chk_rca_agent_task_status CHECK
    (task_status IN ('PENDING','RUNNING','RETRY_PENDING','PARTIAL','NEEDS_HUMAN','COMPLETED','FAILED','CANCELLED')),
  CONSTRAINT chk_rca_agent_node CHECK
    (current_node IN ('CONTEXT_BUILD','BASELINE_EVIDENCE','AGENT_INVESTIGATE','ROOT_CAUSE_ARBITRATE','RCA_PERSIST','DONE')),
  CONSTRAINT chk_rca_agent_stop_reason CHECK
    (stop_reason IS NULL OR stop_reason IN
      ('EVIDENCE_SUFFICIENT','LOW_INFORMATION_GAIN','BUDGET_EXHAUSTED','DEADLINE_EXCEEDED','MAX_STEPS',
       'MANDATORY_TOOL_UNAVAILABLE','UNRESOLVED_REFUTATION','NO_VIABLE_TOOL','HUMAN_REQUIRED','CANCELLED')),
  CONSTRAINT chk_rca_agent_lease_generation CHECK (lease_generation >= 0),
  CONSTRAINT chk_rca_agent_state_version CHECK (state_version >= 0),
  CONSTRAINT chk_rca_agent_attempts CHECK (attempt_count >= 0 AND max_attempts >= 1 AND attempt_count <= max_attempts),
  CONSTRAINT chk_rca_agent_budget CHECK
    (steps_used >= 0 AND steps_used <= budget_max_steps AND
     tokens_used >= 0 AND tokens_used <= budget_max_tokens AND
     cost_used_usd >= 0 AND cost_used_usd <= budget_max_cost_usd),
  CONSTRAINT chk_rca_agent_digest CHECK
    (snapshot_sha256 IS NULL OR snapshot_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_rca_agent_terminal CHECK
    (task_status IN ('PENDING','RUNNING','RETRY_PENDING') OR stop_reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='RCA 父任务；写回必须同时匹配 lease_owner + lease_generation + state_version';

-- ---------------------------------------------------------------------------
-- 5. 五个节点步骤快照：崩溃恢复时判断复用 / 对账 / 重放
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_agent_step (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  node_code VARCHAR(30) NOT NULL,
  attempt_no INT NOT NULL DEFAULT 1 COMMENT '同一节点的第几次进入；回退边可以重复进入同一节点',
  node_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  entered_reason VARCHAR(120) DEFAULT NULL COMMENT '进入该节点的确定性依据，便于区分路由选错与证据不足',
  left_reason VARCHAR(120) DEFAULT NULL,
  input_snapshot JSON DEFAULT NULL,
  output_snapshot JSON DEFAULT NULL,
  output_sha256 CHAR(64) DEFAULT NULL COMMENT '恢复时按哈希判断该步骤是否已有完整产出',
  state_version_after BIGINT DEFAULT NULL,
  started_at DATETIME(3) DEFAULT NULL,
  completed_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rca_agent_step (task_id, node_code, attempt_no),
  KEY idx_rca_agent_step_status (task_id, node_status, node_code),
  CONSTRAINT fk_rca_agent_step_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT chk_rca_agent_step_node CHECK
    (node_code IN ('CONTEXT_BUILD','BASELINE_EVIDENCE','AGENT_INVESTIGATE','ROOT_CAUSE_ARBITRATE','RCA_PERSIST')),
  CONSTRAINT chk_rca_agent_step_status CHECK
    (node_status IN ('PENDING','RUNNING','COMPLETED','FAILED','SKIPPED','CANCELLED')),
  CONSTRAINT chk_rca_agent_step_attempt CHECK (attempt_no >= 1),
  CONSTRAINT chk_rca_agent_step_digest CHECK
    (output_sha256 IS NULL OR output_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='五个主节点的步骤快照与内容哈希；回退边以 attempt_no 递增保留全部轨迹';

-- ---------------------------------------------------------------------------
-- 6. 工具调用记录：错误语义、provider 对账、观察哈希与预算消耗
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_tool_call (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  step_id BIGINT DEFAULT NULL,
  decision_round INT NOT NULL COMMENT '第几轮决策；同轮可并行 fan-out',
  tool_name VARCHAR(40) NOT NULL,
  tool_version VARCHAR(20) NOT NULL,
  params_digest CHAR(64) NOT NULL COMMENT '归一化参数摘要；同工具+同摘要且有完整 Observation 时可复用',
  params_snapshot JSON DEFAULT NULL,
  result_status VARCHAR(30) NOT NULL COMMENT '工具语义码；不用自然语言代替，空结果与缺口必须分开',
  error_semantic VARCHAR(40) DEFAULT NULL,
  provider_request_id VARCHAR(100) DEFAULT NULL COMMENT '本地未写回时按它对账上游最终状态',
  local_commit_state VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMMITTED/UNKNOWN；三态对账结果',
  observation_digest CHAR(64) DEFAULT NULL,
  observation_snapshot JSON DEFAULT NULL,
  evidence_id VARCHAR(64) DEFAULT NULL COMMENT '软关联 t_rca_evidence.evidence_id',
  replayed_from_id BIGINT DEFAULT NULL COMMENT '复用结果时指向被复用的原始调用',
  latency_ms INT DEFAULT NULL,
  input_tokens INT DEFAULT NULL,
  output_tokens INT DEFAULT NULL,
  cost_usd DECIMAL(10,6) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_rca_tool_dedup (task_id, tool_name, params_digest, decision_round),
  KEY idx_rca_tool_reconcile (local_commit_state, provider_request_id),
  CONSTRAINT fk_rca_tool_call_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_rca_tool_call_step FOREIGN KEY (step_id) REFERENCES t_rca_agent_step(id) ON DELETE SET NULL,
  CONSTRAINT fk_rca_tool_call_replay FOREIGN KEY (replayed_from_id) REFERENCES t_rca_tool_call(id) ON DELETE SET NULL,
  CONSTRAINT chk_rca_tool_round CHECK (decision_round >= 1),
  CONSTRAINT chk_rca_tool_result_status CHECK
    (result_status IN ('SUCCESS_WITH_DATA','SUCCESS_EMPTY','NOT_FOUND','INVALID_LOG_ID','FORBIDDEN',
                       'UPSTREAM_TIMEOUT','SCHEMA_INVALID','PARTIAL','STALE','INSUFFICIENT_SAMPLE',
                       'NO_VERIFIED_CASE','RESOURCE_MISSING','SYMBOL_MISSING','UNKNOWN')),
  CONSTRAINT chk_rca_tool_commit_state CHECK (local_commit_state IN ('PENDING','COMMITTED','UNKNOWN')),
  CONSTRAINT chk_rca_tool_dedup_digest CHECK (params_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='工具调用；成功但本地未写回时按 provider_request_id 三态对账，不把未知当空结果';

-- ---------------------------------------------------------------------------
-- 7. 证据账本：coverage_type 是范围性 claim 校验的依据
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  evidence_id VARCHAR(64) NOT NULL COMMENT '对外稳定 ID，模型只引用它，不引用原文',
  evidence_type VARCHAR(30) NOT NULL,
  source_tool VARCHAR(40) NOT NULL,
  source_ref VARCHAR(300) NOT NULL COMMENT '回源地址或上游记录指针；原文不进上下文',
  coverage_type VARCHAR(20) NOT NULL COMMENT 'EPISODE=单点证据；COHORT=含分母与对照组；CLUSTER=簇级分布',
  coverage_scope VARCHAR(300) NOT NULL COMMENT '证据覆盖的对象范围，仲裁按它比对目标 Episode',
  numerator BIGINT DEFAULT NULL COMMENT '分子（错误数）；cohort 证据必填',
  denominator BIGINT DEFAULT NULL COMMENT '分母（总量）；范围性 claim 缺分母即不可用',
  control_group VARCHAR(200) DEFAULT NULL COMMENT '对照组，如对照版本 4.1.3',
  observed_from DATETIME(3) DEFAULT NULL,
  observed_to DATETIME(3) DEFAULT NULL,
  time_watermark DATETIME(3) DEFAULT NULL COMMENT '数据水位；迟到数据产生 correction 不覆盖历史',
  permission_scope VARCHAR(40) NOT NULL,
  content_digest CHAR(64) NOT NULL,
  summary VARCHAR(1000) NOT NULL COMMENT '结构化摘要；原文按需回源',
  raw_reference VARCHAR(300) DEFAULT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  superseded_by_id BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rca_evidence_id (task_id, evidence_id),
  KEY idx_rca_evidence_coverage (task_id, coverage_type, evidence_type),
  KEY idx_rca_evidence_scope (task_id, coverage_scope),
  CONSTRAINT fk_rca_evidence_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_rca_evidence_superseded FOREIGN KEY (superseded_by_id) REFERENCES t_rca_evidence(id) ON DELETE SET NULL,
  CONSTRAINT chk_rca_evidence_coverage CHECK (coverage_type IN ('EPISODE','COHORT','CLUSTER')),
  CONSTRAINT chk_rca_evidence_permission CHECK
    (permission_scope IN ('AGGREGATE','EPISODE','RAW_LOG','RAW_ATTACHMENT')),
  -- 范围性证据必须同时具备分母与对照组，否则不能用于集中度类结论。
  CONSTRAINT chk_rca_evidence_cohort_complete CHECK
    ((coverage_type <> 'COHORT') OR (denominator IS NOT NULL AND denominator > 0 AND control_group IS NOT NULL)),
  CONSTRAINT chk_rca_evidence_numerator CHECK
    ((numerator IS NULL OR numerator >= 0) AND (denominator IS NULL OR denominator >= 0)),
  CONSTRAINT chk_rca_evidence_digest CHECK (content_digest REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_rca_evidence_time_order CHECK
    (observed_from IS NULL OR observed_to IS NULL OR observed_to >= observed_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='证据账本；COHORT 证据强制携带分母与对照组';

-- ---------------------------------------------------------------------------
-- 8. 假设与支持/反证：反证未处理不得停止
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_hypothesis (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  hypothesis_code VARCHAR(40) NOT NULL,
  statement VARCHAR(500) NOT NULL,
  mechanism_note VARCHAR(1000) DEFAULT NULL,
  confidence_level VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
  confidence_score DECIMAL(7,6) DEFAULT NULL,
  hypothesis_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  supporting_evidence JSON DEFAULT NULL COMMENT 'evidence_id 数组',
  refuting_evidence JSON DEFAULT NULL COMMENT '强反证未处理时禁止进入正常停止',
  unresolved_refutation_count INT NOT NULL DEFAULT 0,
  merged_into_id BIGINT DEFAULT NULL COMMENT '假设合并：同一批证据且机制链不可区分时合并，保留原记录',
  revision BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rca_hypothesis_code (task_id, hypothesis_code, revision),
  KEY idx_rca_hypothesis_status (task_id, hypothesis_status, confidence_level),
  CONSTRAINT fk_rca_hypothesis_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_rca_hypothesis_merged FOREIGN KEY (merged_into_id) REFERENCES t_rca_hypothesis(id) ON DELETE SET NULL,
  CONSTRAINT chk_rca_hypothesis_confidence CHECK
    (confidence_level IN ('CANDIDATE','WEAK','MODERATE','STRONG','CONFIRMED','REFUTED')),
  CONSTRAINT chk_rca_hypothesis_status CHECK
    (hypothesis_status IN ('ACTIVE','SUPPORTED','REFUTED','MERGED','DROPPED')),
  CONSTRAINT chk_rca_hypothesis_refutation CHECK (unresolved_refutation_count >= 0),
  CONSTRAINT chk_rca_hypothesis_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='候选假设与支持/反证；历史案例不能单独提升置信等级';

-- ---------------------------------------------------------------------------
-- 9. 强制证据计划：Agent 不能绕过
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_mandatory_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  requirement_code VARCHAR(60) NOT NULL,
  requirement_type VARCHAR(30) NOT NULL,
  trigger_rule VARCHAR(300) NOT NULL COMMENT '确定性触发依据，如"存在合法 log ID"、"现象含 Crash/ANR"',
  required_reason VARCHAR(300) NOT NULL,
  completion_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  satisfied_by_evidence_id VARCHAR(64) DEFAULT NULL COMMENT '软关联 t_rca_evidence.evidence_id',
  failure_semantic VARCHAR(40) DEFAULT NULL COMMENT '失败时保留语义，不能当成"查过没问题"',
  waiver_reason VARCHAR(300) DEFAULT NULL COMMENT '仅仲裁节点可豁免，且必须留原因',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_rca_mandatory (task_id, requirement_code),
  KEY idx_rca_mandatory_status (task_id, completion_status),
  CONSTRAINT fk_rca_mandatory_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT chk_rca_mandatory_type CHECK
    (requirement_type IN ('LOG_TRACE','CRASH_STACK','ERROR_SIGNATURE_DISTRIBUTION','COHORT_COMPARISON')),
  CONSTRAINT chk_rca_mandatory_completion CHECK
    (completion_status IN ('PENDING','SATISFIED','FAILED_GAP','WAIVED')),
  CONSTRAINT chk_rca_mandatory_terminal CHECK
    (completion_status = 'PENDING' OR
     completion_status = 'WAIVED' OR
     satisfied_by_evidence_id IS NOT NULL OR
     failure_semantic IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='强制证据计划；有合法日志 ID 必查日志、Crash/ANR 必查堆栈、多成员簇先看签名分布';

-- ---------------------------------------------------------------------------
-- 10. Episode 归因分级：五档 + revision 追加
-- ---------------------------------------------------------------------------
CREATE TABLE t_feedback_episode_attribution (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  episode_id BIGINT NOT NULL,
  rca_task_id BIGINT NOT NULL,
  incident_id BIGINT DEFAULT NULL COMMENT '仅 DIRECT_CONFIRMED 与满足门槛的 SUPPORTED_INHERITED 可回填',
  root_cause_signature VARCHAR(300) NOT NULL COMMENT '稳定签名只含租户/依赖范围、组件、故障类型、错误签名、接口类型',
  attribution_level VARCHAR(25) NOT NULL,
  attribution_revision BIGINT NOT NULL DEFAULT 0 COMMENT '追加式；升级降级都保留历史行',
  basis_evidence JSON DEFAULT NULL COMMENT 'evidence_id 数组，必须可回源',
  coverage_note VARCHAR(500) DEFAULT NULL COMMENT '说明该证据为何覆盖不到其他 Episode',
  auto_linked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '仅前两档可为 1；其余档必须人工处理',
  decided_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  decided_by BIGINT DEFAULT NULL,
  superseded_by_id BIGINT DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_episode_attribution_rev (episode_id, rca_task_id, attribution_revision),
  KEY idx_episode_attribution_level (attribution_level, auto_linked),
  KEY idx_episode_attribution_incident (incident_id, attribution_level),
  CONSTRAINT fk_episode_attribution_episode FOREIGN KEY (episode_id) REFERENCES t_feedback_episode(id),
  CONSTRAINT fk_episode_attribution_task FOREIGN KEY (rca_task_id) REFERENCES t_rca_agent_task(id),
  CONSTRAINT fk_episode_attribution_superseded FOREIGN KEY (superseded_by_id)
    REFERENCES t_feedback_episode_attribution(id) ON DELETE SET NULL,
  CONSTRAINT chk_episode_attribution_level CHECK
    (attribution_level IN ('DIRECT_CONFIRMED','SUPPORTED_INHERITED','AMBIGUOUS','UNATTRIBUTED','REJECTED')),
  -- 代表日志只直接证明被查询的 Episode：只有直接确认与被继承可以自动关联 Incident。
  CONSTRAINT chk_episode_attribution_autolink CHECK
    ((auto_linked = 0) OR (attribution_level IN ('DIRECT_CONFIRMED','SUPPORTED_INHERITED'))),
  CONSTRAINT chk_episode_attribution_incident_link CHECK
    ((incident_id IS NULL) OR (attribution_level IN ('DIRECT_CONFIRMED','SUPPORTED_INHERITED'))),
  CONSTRAINT chk_episode_attribution_revision CHECK (attribution_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='Episode 归因分级；五档不删除记录，只追加 revision';

-- ---------------------------------------------------------------------------
-- 11. 引用支持校验：claim-evidence pair，按 claim 类型检查证据类型
-- ---------------------------------------------------------------------------
CREATE TABLE t_rca_claim_evidence (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  hypothesis_id BIGINT DEFAULT NULL,
  claim_code VARCHAR(60) NOT NULL,
  claim_type VARCHAR(20) NOT NULL COMMENT 'RANGE 类 claim 必须引用 COHORT 证据',
  claim_text VARCHAR(1000) NOT NULL,
  evidence_id VARCHAR(64) NOT NULL,
  evidence_coverage_type VARCHAR(20) NOT NULL COMMENT '引用时快照证据的覆盖类型，便于事后复核',
  required_coverage_type VARCHAR(20) NOT NULL COMMENT '按 claim_type 推导出的最低证据粒度要求',
  support_verdict VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  verdict_reason VARCHAR(500) DEFAULT NULL,
  review_status VARCHAR(20) NOT NULL DEFAULT 'UNREVIEWED',
  reviewer_a BIGINT DEFAULT NULL,
  reviewer_b BIGINT DEFAULT NULL,
  reviewed_at DATETIME(3) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_rca_claim_task (task_id, support_verdict),
  KEY idx_rca_claim_review (review_status, claim_type),
  CONSTRAINT fk_rca_claim_task FOREIGN KEY (task_id) REFERENCES t_rca_agent_task(id) ON DELETE CASCADE,
  CONSTRAINT fk_rca_claim_hypothesis FOREIGN KEY (hypothesis_id) REFERENCES t_rca_hypothesis(id) ON DELETE SET NULL,
  CONSTRAINT chk_rca_claim_type CHECK (claim_type IN ('EPISODIC','RANGE','CAUSAL','MECHANISM')),
  CONSTRAINT chk_rca_claim_coverage CHECK
    (evidence_coverage_type IN ('EPISODE','COHORT','CLUSTER') AND
     required_coverage_type IN ('EPISODE','COHORT','CLUSTER')),
  CONSTRAINT chk_rca_claim_verdict CHECK
    (support_verdict IN ('PENDING','SUPPORTED','UNSUPPORTED','PARTIAL_OVERSCOPED')),
  CONSTRAINT chk_rca_claim_review CHECK
    (review_status IN ('UNREVIEWED','AGREED','DISPUTED','ADJUDICATED')),
  -- 引用存在但支持关系不成立：RANGE claim 引用 EPISODE 级证据直接判为不成立。
  CONSTRAINT chk_rca_claim_range_needs_cohort CHECK
    ((claim_type <> 'RANGE') OR (required_coverage_type = 'COHORT')),
  CONSTRAINT chk_rca_claim_dual_review CHECK
    (review_status <> 'AGREED' OR (reviewer_a IS NOT NULL AND reviewer_b IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='claim-evidence 引用支持校验；RANGE claim 引单点证据判为不成立';

-- ---------------------------------------------------------------------------
-- 12. 给现有任务表补 generation 与 state_version（修掉 owner-only fencing 的 ABA 缺口）
-- ---------------------------------------------------------------------------
-- 现有 t_ai_task 的写回只匹配 lease_holder（AiTaskMapper.xml:37-51），
-- 而索引同步链路的 complete/fail 连 lease_holder 条件都没有（IndexAiTaskMapper.xml:23-27）。
-- 一旦 lease_holder 复用，或 A 领取→租约过期→B 重新领取→A 晚到写回，旧结果会被接受。
-- 新增两列后，写回条件可升级为 owner + generation + state_version 三重匹配。
ALTER TABLE t_ai_task
  ADD COLUMN lease_generation BIGINT NOT NULL DEFAULT 0
    COMMENT '每次 claim 递增；配合 lease_holder 构成 fencing token' AFTER lease_expires_at,
  ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0
    COMMENT '业务状态版本；写回必须匹配，防旧快照覆盖新状态' AFTER lease_generation;

ALTER TABLE t_ai_task
  ADD CONSTRAINT chk_ai_task_lease_generation CHECK (lease_generation >= 0),
  ADD CONSTRAINT chk_ai_task_state_version CHECK (state_version >= 0);
