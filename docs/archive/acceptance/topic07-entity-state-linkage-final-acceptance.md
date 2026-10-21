# 专题 7：反馈、问题簇、RCA、知识、Incident 状态联动最终验收

## 验收结论

**PASS（实习生项目最终汇报范围）**。

五类实体不再只是分别拥有状态字段：系统新增 `entity-closure-v1` 跨实体闭环快照和 Incident 关闭门禁。有关联问题簇/RCA 的 Incident 只有在问题簇已解决、RCA 已审核、每个 RCA 均有已发布知识且知识已在该 Incident 中验证有效后才允许关闭；关闭与关联反馈转为 `CLOSED` 位于同一事务中。

本结论不代表生产工作流引擎、跨服务分布式事务或长时间并发稳定性已经完成上线验收。

## 状态联动规则

| 实体 | 闭环状态/证据 |
|---|---|
| 反馈 | Incident 成功关闭时，同事务更新关联 active MAIN 反馈为 `CLOSED` |
| 问题簇 | 所有关联 active 问题簇必须为 `RESOLVED` |
| RCA | 所有关联 RCA 必须为 `CONFIRMED` 或 `CORRECTED` |
| 知识 | 每个 RCA 至少存在一条 `PUBLISHED` 知识，且该 Incident 至少记录一次 `EFFECTIVE` |
| Incident | 按原状态机 `OPEN → INVESTIGATING → RESOLVED → CLOSED`，继续要求版本号、原因和解决/关闭证据 |

无 active `t_cluster_root_cause` 关系的普通 Incident 保持原有关闭行为，避免破坏告警类 Incident 的既有流程。有关联实体时，关闭门禁才生效。

## 接口与权限

- 新增 `GET /api/v1/alerts/incidents/{id}/entity-closure`。
- 返回 `BLOCKED / READY / PASSED`、六项检查、各实体计数和版本 `entity-closure-v1`。
- 快照读取并验证所有关联反馈均处于当前用户 channel/module/region 数据权限范围；任一反馈越权即 fail-closed。
- Incident 关闭沿用既有 POST transition 接口、角色权限、乐观锁和不可变生命周期事件。
- 成功联动写入 `ENTITY_CHAIN_CLOSE` 系统审计记录。

## 真实共享 MySQL/API 回放

脚本：`tools/verify-topic07-entity-state-closure.sh`

最终隔离标记：`TOPIC07-178582451469859`。夹具使用 `VERIFICATION` 环境，包含一条反馈、一个 MAIN 问题簇、一个待审 RCA、一个 Incident 和隔离管理员。

执行顺序：

1. 初始快照为 `BLOCKED`。
2. 问题簇按 `ACTIVE → MITIGATING → RESOLVED` 迁移。
3. RCA 审核为 `CONFIRMED`，同时生成 `PUBLISHED` 知识。
4. 知识先记录 `ADOPTED`。
5. Incident 按 `OPEN → INVESTIGATING → RESOLVED` 迁移。
6. 未记录 `EFFECTIVE` 时尝试关闭，HTTP 400；事务回滚后仍为 `RESOLVED:version=2`，反馈仍为 `OPEN`。
7. 记录知识 `EFFECTIVE` 后快照为 `READY`。
8. 再次关闭成功，反馈同事务变为 `CLOSED`，最终快照为 `PASSED`。

真实输出：

```text
PASS topic07 feedback-cluster-rca-knowledge-incident closure
{"blockedBeforeEvidence":true,"prematureCloseHttp":400,"atomicRollback":true,"readyBeforeClose":true,"final":"CLOSED:RESOLVED:CONFIRMED:PUBLISHED:CLOSED:1","metricVersion":"entity-closure-v1"}
```

最终状态串依次表示：反馈 `CLOSED`、问题簇 `RESOLVED`、RCA `CONFIRMED`、知识 `PUBLISHED`、Incident `CLOSED`、知识有效次数 `1`。

脚本退出后反馈、问题簇、RCA、知识、Incident、生命周期事件和临时用户均精确清理为 0。三条系统审计记录依据追加型审计设计保留，不作为业务夹具残留。

## 自动化回归

```text
backend: 384 tests, 0 failures, 0 errors, 13 skipped
frontend lint: PASS
frontend test: 13 files / 33 tests PASS
frontend build: PASS
git diff --check: PASS
```

新增单元测试覆盖：证据齐全时 READY、缺少知识有效性时阻断且不更新反馈、门禁通过后更新反馈并写审计。

## 汇报边界

本专题证明了单库事务范围内五实体状态和证据可以形成可重复闭环。未覆盖跨微服务 Saga、消息最终一致性、生产级高并发冲突率、人工撤销闭环或批量关闭容量，这些属于上线前工程验收范围。
