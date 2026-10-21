-- C1b3 一期反馈列表投影 generation 构建、校验与切换控制基础设施。
-- 本迁移只增加控制元数据和单行 epoch；不创建 generation、不回填业务数据、不激活或回滚。

ALTER TABLE t_phase_one_feedback_list_projection_generation
  ADD COLUMN build_fence_source_id BIGINT DEFAULT NULL
    COMMENT '创建时初始化且仅在受控 catch-up 中单调扩展的当前验证 source 最大 ID' AFTER build_cursor_source_id,
  ADD COLUMN verified_mutation_epoch BIGINT DEFAULT NULL
    COMMENT '最近一次确定性全量验证开始时的全局变更 epoch' AFTER content_checksum,
  ADD COLUMN verified_member_checksum CHAR(64) DEFAULT NULL
    COMMENT 'MAIN 成员投影的确定性 SHA-256 校验值' AFTER verified_mutation_epoch,
  ADD COLUMN verified_at DATETIME(3) DEFAULT NULL AFTER verified_member_checksum,
  ADD COLUMN cutover_mutation_epoch BIGINT DEFAULT NULL
    COMMENT '激活或进入 ROLLBACK_READY 时的全局变更 epoch' AFTER verified_at,
  ADD COLUMN rollback_ready_slot TINYINT GENERATED ALWAYS AS
    (CASE WHEN generation_status='ROLLBACK_READY' THEN 1 ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_phase_one_list_projection_rollback_ready (rollback_ready_slot),
  ADD CONSTRAINT chk_phase_one_list_projection_generation_fence CHECK
    (build_fence_source_id IS NULL OR build_fence_source_id >= 0),
  ADD CONSTRAINT chk_phase_one_list_projection_generation_epochs CHECK
    ((verified_mutation_epoch IS NULL OR verified_mutation_epoch >= 0)
      AND (cutover_mutation_epoch IS NULL OR cutover_mutation_epoch >= 0));

CREATE TABLE t_phase_one_feedback_list_projection_control (
  control_id TINYINT NOT NULL PRIMARY KEY,
  mutation_epoch BIGINT NOT NULL DEFAULT 0 COMMENT '所有已接线权威写入单调递增的投影变更 epoch',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_phase_one_list_projection_control_singleton CHECK (control_id = 1),
  CONSTRAINT chk_phase_one_list_projection_control_epoch CHECK (mutation_epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
  COMMENT='一期反馈列表投影全局切换锁和变更 epoch；固定单行';

INSERT INTO t_phase_one_feedback_list_projection_control(control_id, mutation_epoch) VALUES (1, 0);

-- Governed physical business table count is now 62.
