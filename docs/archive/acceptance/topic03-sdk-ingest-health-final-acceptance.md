# 专题 03：SDK 接入健康最终验收

## 结论

**PASS（实习生项目最终汇报范围）**。

SDK 入口健康已形成“请求结果字典—RPS 时间口径—应用/IP 限流—汇总/明细对账—隔离回放”的完整闭环。本文结论不代表生产容量压测、生产 SLA 或正式上线批准。

## 闭环内容

### 1. 六类入口结果字典

契约版本为 `sdk-transport-v2`，由 `GET /api/v1/dashboard/sdk-health-contract` 返回：

| 结果类型 | HTTP 范围 | 含义 |
|---|---|---|
| SUCCESS | 2xx | 平台同步接收成功 |
| RATE_LIMITED | 429 | 应用或来源 IP 触发限流 |
| AUTH_FAILED | 401/403 | 凭证或签名鉴权失败 |
| BAD_REQUEST | 400/422 | 请求字段或协议不合法 |
| SERVER_ERROR | 5xx | 平台服务异常 |
| OTHER_ERROR | 其余非 2xx | 协议外或尚未标准化的入口异常 |

`SdkRequestTelemetryRecorderTest` 已逐一覆盖边界状态码。健康概览和明细筛选均显式展示 `OTHER_ERROR`，并提供稳定的责任方、客户端行为和处置说明。

### 2. 汇总与明细对账

健康汇总新增 `otherErrorCount` 和 `transportFailureReconciled`，固定公式为：

```text
transportFailureCount
= rateLimitedCount + authFailureCount + badRequestCount
  + serverErrorCount + otherErrorCount
```

失败总数仍独立按 `attemptCount - successCount` 计算，因此分类遗漏会直接令对账标志失败，而不会静默掩盖。

### 3. RPS 与分箱口径

| 窗口 | 窗口秒数 | 分箱 | 峰值含义 |
|---|---:|---:|---|
| 1h | 3,600 | 60 秒 | 每分钟分箱速率最大值 |
| 6h | 21,600 | 3,600 秒 | 每小时分箱速率最大值 |
| 24h | 86,400 | 3,600 秒 | 每小时分箱速率最大值 |
| 7d | 604,800 | 86,400 秒 | 每天分箱速率最大值 |

- 平均 RPS：窗口内全部入口请求数 ÷ 窗口秒数。
- 当前 RPS：最近 300 秒入口请求数 ÷ 300。
- 峰值 RPS：所选窗口分箱速率最大值，不宣称为单秒瞬时峰值。

API 直接返回 `windowSeconds`、`bucketSeconds`、`currentWindowSeconds`，页面明确显示“近5分钟平均”和“分箱峰值”。非法窗口失败关闭。

### 4. 应用/IP 双维限流

- 应用维度：运行时配置 `sdk.rate-limit`，默认 100 次/分钟。
- IP 维度：运行时配置 `sdk.ip-rate-limit`，默认 60 次/分钟。
- 两者采用独立 Redis 键和 60 秒固定窗口，任一超限即拒绝。
- V117 将 IP 配置注册到类型化配置目录，允许范围为 1–10,000；管理页可读取和修改。

`RateLimiterTest` 验证两个运行时阈值均被读取，并验证首个计数设置 60 秒过期时间。

## 隔离真实回放

可重复脚本：

```bash
tools/verify-topic03-sdk-health.sh
```

2026-08-04 在共享 MySQL 和 `127.0.0.1:18080` 真实 API 上执行结果：

```text
PASS topic03 isolated SDK health reconciliation
metricVersion=sdk-transport-v2
attemptCount delta=6
successCount delta=1
transportFailureCount delta=5
RATE_LIMITED/AUTH_FAILED/BAD_REQUEST/SERVER_ERROR/OTHER_ERROR delta=1/1/1/1/1
windowSeconds=3600, bucketSeconds=60, currentWindowSeconds=300
appLimitPerMinute=100, ipLimitPerMinute=60
```

脚本使用唯一用户和唯一 `app_id`，结束时精确删除对应六条遥测和临时用户。清理复核结果：临时遥测 0、临时用户 0。Flyway V117 状态为 success=1。

## 回归证据

- 后端全量：357 tests，0 failures，0 errors，13 skipped。
- 前端 ESLint：通过。
- 前端 Vitest：13 个文件、33 tests 全通过。
- 前端类型检查与 Vite build：通过。
- `git diff --check`：通过。

## 范围边界

本专题已达到课程/实习生项目最终汇报和演示验收程度。未在本结论中声称：生产流量容量、分布式限流强一致、跨地域压测、正式生产告警 SLA 或上线批准；这些属于上线前专项工作。
