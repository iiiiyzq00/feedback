# 交付物 12：测试说明与自动化测试

## 1. 测试目标

测试覆盖五渠道接入、脱敏门禁、标准化、查询、归簇、AI 降级、质量指标、看板报告、权限审计和高风险操作。所有数据使用模拟或已脱敏样例，测试不得以关闭安全检查换取通过。

## 2. 自动化层次

| 层次 | 位置 | 覆盖重点 |
|---|---|---|
| 后端单元/契约 | `backend/src/test/` | 服务逻辑、权限、迁移契约、脱敏和输出约束 |
| 后端集成 | `backend/src/integrationTest/` | SDK、导入、AI、归簇、告警、报告、恢复和数据库链路 |
| Android SDK | `sdk/src/test/`、`sdk/src/androidTest/` | 签名、过滤、离线队列、重试、分片、状态机和持久化 |
| Web 前端 | `frontend/src/**/*.spec.ts` | 组件、路由、状态、权限和页面契约 |
| 端到端/专项验收 | `tools/verify-*.sh` | 真实 API、MySQL、浏览器、报告、隐私和治理闭环 |
| 模拟数据 | `data/test-fixtures/full-system-20260805/` | 四渠道、失败行、敏感标记、预期数量和归簇样例 |

## 3. 核心测试矩阵

| 模块 | 必测场景 | 通过标准 |
|---|---|---|
| SDK | 正常/重复提交、弱网、重试、分片完成、签名越权、环境字段 | 幂等；无权限旁路；失败可恢复 |
| 旧版反馈 SDK | V1/V2 推送、签名、幂等冲突、事务回滚、Worker 和人工重试 | 只写 `STATION_FEEDBACK`；不得写通用 `SDK` 队列 |
| Jira 导入 | Issue Key、旧快照、相同文件、失败重试 | 不重复新增，旧数据不覆盖新状态 |
| 质量导入 | 既有组合键、表头变化、日期/枚举错误 | 来源 ID 规则不变；映射版本可追溯 |
| 红黑榜导入 | 红/黑类型、周期重复、来源口径 | 正负向可分可合，不直接混算不可比口径 |
| 脱敏 | 结构化、正文、日志、附件、预览、出站检查 | 非 PASS 不进入普通查询/AI/索引/导出 |
| 查询权限 | 渠道、字段、角色、导出和隐私工作台 | 默认拒绝越权；敏感操作留审计 |
| AI/归簇 | 结构校验、低置信复核、错误关联解除、服务失败 | 不覆盖来源字段；失败不阻断核心链路 |
| 质量/看板 | 指标分母、批次对账、新鲜度、原始/去重数 | 指标定义明确，数字可回溯到程序查询 |
| 数据库 | Flyway 校验与表数量 | schema为V178且保持44张物理业务表；Jira API状态表不得包含Token或明文地址 |

## 4. 执行命令

```bash
./gradlew :backend:test :sdk:testDebugUnitTest
tools/run-local-integration-test.sh
./gradlew :demo:assembleDebug :health-demo:assembleDebug :child-art-demo:assembleDebug
cd frontend && npm test && npm run build
git diff --check
```

专项脚本的环境依赖和预期输出写在脚本及 `../archive/acceptance/` 对应报告中。不要在缺少 MySQL、ES、Milvus、LLM、浏览器或通知供应商时伪造成功；应标记为环境未满足，并验证规定的降级路径。

本机开发不使用 Docker。`tools/run-local-integration-test.sh` 强制连接独立本机测试库 `feedback_platform_test` 和本机 Redis；禁止直接把 `feedback_platform`、`feedback_platform_local_deploy` 或其他业务库传给集成测试。CI 可以保留其隔离容器执行能力，但不得据此要求本机安装或启动 Docker。

## 5. 数据与结果判定

`data/test-fixtures/full-system-20260805/manifest.json` 描述输入，`expected-results.csv` 描述预期数量。每个导入批次必须满足对账恒等式，重复执行不得产生重复业务记录。隐私测试使用唯一敏感标记，并在应用日志、审计安全字段、搜索/向量索引、AI 请求和导出文件中反向检索该标记。

第 17 节数据门禁使用 `tools/verify-section17-full-dataset.sh`。默认只读校验 1180 条四渠道预期记录、5 份工作簿行数与 ZIP 完整性、16 个金标准簇、异常类型、登记隐私样例和文件哈希；设置 `SECTION17_LIVE=true` 并提供 `D10P_APP_SECRET`、`D10P_PRIVATE_KEY` 后，脚本真实导入 5 个表格批次、提交 360 条 SDK 加密反馈，自动跨限流窗口重试 429，并核对批次恒等式、SDK 信封/正式记录幂等性及金标准标签覆盖。格式逼真的敏感样例必须先登记于 `privacy-fixture-registry.json`，未登记的手机号、邮箱或证件号形态会使门禁失败。

测试报告至少记录：代码版本、迁移版本、规则/Prompt/映射版本、执行环境、命令、开始结束时间、通过/失败/跳过数量、失败证据和复测结果。

## 6. 发布门槛

- 核心单元测试、前端构建和 SDK 测试通过。
- 五渠道主链路、PASS 门禁、权限越权、幂等/旧快照和批次对账无阻断缺陷。
- 43 表契约通过；不得为测试临时增加表。
- 外部依赖故障时，接入、MySQL 查询和程序统计仍可使用。
- 未执行的外部集成测试必须列明原因、风险和现场补验步骤。

历史专项结论保存在 [`../archive/acceptance/`](../archive/acceptance/)，它们是证据，不替代当前版本的回归测试。

## 7. 第 15 节统一验收

`tools/verify-section15-acceptance.sh` 将 SDK 单条上报、三类表格导入、失败重试/隐私、四渠道展示分析和零泄漏检查编排为一次执行，并在 `output/acceptance/` 生成 Markdown 报告及分步骤日志。运行前必须提供 SDK 测试应用秘密和传输私钥：

```bash
export D10P_APP_SECRET='<test-app-secret>'
export D10P_PRIVATE_KEY='/absolute/path/to/test-private-key.pem'
export SECTION15_BASE_URL='http://127.0.0.1:18081'
tools/verify-section15-acceptance.sh
```

零泄漏检查也可单独执行：`tools/verify-section15-zero-leakage.sh`。如需扫描后端日志和导出目录，分别设置 `SECTION15_BACKEND_LOG` 与 `SECTION15_EXPORT_DIR`；未配置的 ES/Milvus 外部环境必须明确记为未配置并现场补验。

### 最近一次真实执行

2026-08-05 15:11（Asia/Shanghai）在本地 18080 完整环境执行，最终结论为 **PASS**。可提交的结果摘要见 [`../archive/acceptance/section15-unified-acceptance-20260805.md`](../archive/acceptance/section15-unified-acceptance-20260805.md)；本机原始报告位于 `output/acceptance/section15-acceptance.md`，零泄漏报告位于 `output/acceptance/section15-zero-leakage.md`。关键结果：

- SDK 单条提交：HTTP 200，PASS；
- Jira、质量平台、红黑榜：3 个批次、3 个来源、3 个渠道、3 条 PASS，核心导入与回滚查询隔离通过；
- 派生 ES/Milvus 同步：等待窗内 `ready=0/3`，明确记录为 `DERIVED_INDEX=DEGRADED`，没有伪造成功，且不影响接入、MySQL 查询、对账和回滚；
- 1201 行导入隐私回归：原始行销毁 1201、原手机号存储 0、掩码手机号 1201，越权上传和回滚均返回 403；
- 四渠道分析：每渠道 3 条、去重问题 3 个、跨渠道问题 3 个，列表与分析对账一致；
- 零泄漏：数据库正文/AI/审计/安全错误 0 命中，非 PASS 出站 0，Elasticsearch 精确原串 0 命中。

Milvus 本次通过 MySQL 权威同步状态验证非 PASS 未出站；物理 Milvus 集合反向扫描仍属于配置真实 Milvus 服务后的补充证据。
