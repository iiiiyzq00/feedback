# 专题 6：主链状态一致性最终验收

## 验收结论

**PASS（实习生项目最终汇报范围）**。

正式接收、隐私处理、AI 分类和主归簇现在统一以 `t_feedback_source.id` 为去重主键，以 `t_feedback_source.ingest_time` 为时间边界，并在同一次查询中使用相同的数据权限快照。已完成 today、滚动 24h、滚动 7d 的共享 MySQL/API 隔离回放及大批量对账。

本结论只覆盖当前项目演示、自动化回归和最终汇报要求，不代表生产数据仓库长稳运行、容量上限或上线 SLA 已获得验证。

## 本次整改

- `GET /api/v1/dashboard/metric-reconciliation` 新增 `range=today|24h|7d`，非法窗口返回 HTTP 400。
- 对账查询接入 `DataPermissionService`，按 channel、module、region fail-closed；不再向普通用户泄露全局计数。
- 接收、隐私、AI、归簇均使用 `COUNT(DISTINCT s.id)`，避免 JOIN 扩张造成重复计数。
- 隐私阶段完整核对 `PASS/REVIEW_NEEDED/FAIL/PENDING`。
- AI 阶段完整核对 `NOT_REQUIRED/PENDING/CONFIRMED/CORRECTED`；响应字段继续保留 `aiReviewed/aiOverruled` 以兼容前端。
- 归簇阶段完整核对 `linkedExisting/newCluster`，且只统计 active MAIN 关系。
- 对账版本升级为 `metric-reconciliation-v3`；漏斗版本维持 `record-funnel-v7`，两个接口使用同一时间窗与权限口径。
- 新增单元测试，覆盖权限 SQL、当前 AI 状态字典、失配失败和非法窗口。

## 真实隔离回放证据

验收脚本：`tools/verify-topic06-main-chain-reconciliation.sh`

共享环境：

- API：`http://127.0.0.1:18080`
- MySQL：`feedback_platform`（`127.0.0.1:3306`）
- 隔离前缀：`TOPIC06-178582417368868-`（最终成功回放；脚本每次生成新前缀）

夹具共 1100 个不同 source：今天 250 个、跨日期但处于滚动 24h 内 250 个、3 天前 500 个、8 天前 100 个。前 1000 个进入 7d，最后 100 个必须排除。四种渠道轮转，AI 结果 792 行，active MAIN 关系 792 行。

| 窗口 | received | privacy PASS/复核/失败/待处理 | AI processed | AI 自动/待审/确认/纠正 | cluster | 已有簇/新簇 |
|---|---:|---:|---:|---:|---:|---:|
| today | 250 | 200/20/15/15 | 180 | 140/15/15/10 | 180 | 120/60 |
| 24h | 500 | 400/40/30/30 | 360 | 280/30/30/20 | 360 | 240/120 |
| 7d | 1000 | 800/80/60/60 | 720 | 560/60/60/40 | 720 | 480/240 |

脚本逐窗断言 `metric-reconciliation-v3` 与 `record-funnel-v7` 的上述 13 个计数完全相同，并获得：

```text
PASS topic06 main-chain state reconciliation
{"fixtureSources":1100,"within7d":1000,"excluded8d":100,"aiRows":792,"mainLinks":792,"windows":["today","24h","7d"],"jiraScoped7d":250,"failClosed7d":0,"invalidRangeHttp":400}
```

权限隔离结果：仅有 JIRA + 本专题 module 权限的审计用户在 7d 只看到 250 条；显式无 channel 权限用户看到 0 条，且六项对账仍为 PASSED。脚本使用唯一前缀，退出时按 task、link、AI、identity、content、source、cluster、权限、角色、用户顺序精确清理；最终成功回放后 source、cluster、临时用户复查均为 0。

## 自动化回归

```text
backend: 381 tests, 0 failures, 0 errors, 13 skipped
frontend lint: PASS
frontend test: 13 files / 33 tests PASS
frontend build: PASS
git diff --check: PASS
```

## 汇报边界

本专题已证明版本内主链的 ID、时间、状态分支和权限口径可重复对账，并覆盖 1100 条跨日期批量数据。未覆盖生产规模历史数据仓库、长时间并发压测、跨时区部署矩阵或上线监控 SLA；这些属于上线前工程验收，而非本次实习生项目最终汇报范围。
