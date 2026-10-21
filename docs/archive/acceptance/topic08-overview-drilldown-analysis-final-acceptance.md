# 专题 8：总览下钻与深度分析最终验收

## 验收结论

**PASS（实习生项目最终汇报范围）**。

总览聚合、深度分析与反馈明细现在统一排除验证环境数据，并共享业务时间、隐私 PASS、未删除、数据权限及筛选条件口径。深度分析下钻不再只传渠道和时间，而是完整保留模块、问题类型、严重度、处理状态、可重现性、隐私状态、AI 审核状态和省份筛选。

本结论不代表生产 BI 数据仓库、超大数据量查询性能或全浏览器兼容性已经完成上线验收。

## 本次整改

- 深度分析显式限制 `s.data_environment='PRODUCTION'`。
- 反馈明细显式限制 `s.data_environment='PRODUCTION'`，避免验证夹具进入客户下钻页面。
- 反馈查询契约新增 modules、problemTypes、reproducibilities、maskingStatus、aiReviewStatus、province。
- 深度分析的所有下钻动作统一携带完整筛选快照。
- 全部渠道反馈页恢复上述 URL 参数，并将单值模块、问题类型、可重现性同步回填到可见筛选控件。
- 保持现有 channel、业务发生时间、severity、status、package/version 等下钻参数兼容。

## 真实 MySQL/API 对账

脚本：`tools/verify-topic08-overview-drilldown-analysis.sh`

最终成功隔离前缀：`TOPIC08-178582626072592-`。共享 MySQL 中建立 12 条 PRODUCTION 反馈和 1 条同权限、同模块的 VERIFICATION 对照反馈；四渠道各 3 条生产数据，另有 3 个跨渠道 MAIN 问题簇。

对账结果：

| 对账项 | 预期与结果 |
|---|---:|
| 总览四渠道 | SDK/JIRA/QUALITY/REDBLACK 均为 3 |
| 总览有效反馈 | 12 |
| 唯一问题簇 | 3 |
| 跨渠道问题簇 | 3 |
| 30 天渠道趋势合计 | 12 |
| 问题类型分布 | BUG 6 / DEMAND 4 / UNKNOWN 2 |
| 模块排行 | 隔离模块 12 |
| 热点问题 | 4 / 4 / 2 |
| 深度分析与无筛选明细 | 12 = 12 |
| 完整九类筛选后的分析与明细 | 1 = 1 |
| 总览去重问题/分析汇总/去重列表 | 3 = 3 = 3 |
| 总览跨渠道问题/分析汇总/跨渠道列表 | 3 = 3 = 3 |
| VERIFICATION 对照记录 | 两侧均排除 |

真实输出：

```text
PASS topic08 overview drilldown deep-analysis reconciliation
{"productionFeedback":12,"verificationExcluded":1,"channels":{"SDK":3,"JIRA":3,"QUALITY":3,"REDBLACK":3},"uniqueProblems":3,"crossChannel":3,"unfilteredAnalysisAndList":12,"fullFilterSnapshotAnalysisAndList":1}
```

验收用户只有隔离 module 的读取权限；脚本同时验证了页面数据权限范围内的数字，不依赖全局生产数据。退出后 source、AI、cluster link、cluster、权限和临时用户均为 0。

## 页面级验收

通过本地实际前端页面完成登录和下钻恢复检查。使用包含以下参数的下钻 URL：

```text
channels=JIRA,QUALITY
modules=TOPIC08_SCOPE
problemTypes=BUG
severities=S1
statuses=OPEN
reproducibilities=ALWAYS
maskingStatus=PASS
aiReviewStatus=PENDING
province=310000
startTime/endTime
```

页面加载并自行规范化 URL 后，上述参数全部保留；渠道、时间、严重度、状态筛选可见，模块控件显示 `TOPIC08_SCOPE`，问题类型控件显示 `BUG`。页面验收使用的临时管理员随后精确清理为 0。浏览器技能在这里促成了单值模块/问题类型/可重现性回填到可见控件的调整。

### 问题簇指标补充闭环

首次验收后复核发现，“去重问题数”只携带 target 提示、没有全部问题簇列表；“跨渠道问题数”只有最多 50 行的表格，且总览为全量、分析默认近 30 天，不能正式对账。现已补齐：

- 深度分析接口升级为 `problem-analysis-v3`，返回精确的 `uniqueProblemCount` 和 `crossChannelProblemCount`。
- 返回全部去重问题簇和跨渠道问题簇的分页明细，总数不再受单页 LIMIT 影响。
- 页面顶部直接显示“当前口径去重问题数”和“当前口径跨渠道问题数”。
- 从总览两张指标卡进入时携带 `allTime=true`，与总览全量口径一致。
- 去重入口展示全部唯一 MAIN 问题簇；跨渠道入口展示渠道数大于 1 的问题簇。
- “跨渠道关联表”在所有深度分析入口始终保留；从去重指标进入时，新增的去重明细与原跨渠道模块并存，不互相替代。
- 跨渠道关联表将历史“同源概率”纠正为“平均归簇相似度”：只统计存在评分的当前有效 MAIN 关联，并展示评分样本数与完整率；该值不代表同一用户、设备、提交事件或真实根因。
- 左侧菜单、去重指标和跨渠道指标现在使用完全相同的页面结构，并统一默认“全部时间”；`target` 仅用于入口说明和目标卡片高亮，不再决定模块显隐。
- 实际页面复核显示全量生产数据中：总览去重问题 13、深度分析去重问题 13；总览跨渠道问题 5、深度分析跨渠道问题 5，并展示可点击的问题簇明细与总数。
- 同时修正深度分析 AI 审核筛选仍使用旧值 `REVIEWED/OVERRULED` 的兼容遗漏，改为现行 `CONFIRMED/CORRECTED`。

## 自动化回归

```text
backend: 385 tests, 0 failures, 0 errors, 13 skipped
frontend lint: PASS
frontend test: 13 files / 33 tests PASS
frontend build: PASS
git diff --check: PASS
```

新增契约回归覆盖生产环境门禁和深度筛选 SQL。真实脚本继续覆盖总览、六个分析接口、反馈明细和权限隔离的端到端一致性。

## 汇报边界

本专题证明了当前页面和 API 在隔离样本下可以逐项对账，且筛选下钻不会扩大结果集。未覆盖千万级数据查询、生产缓存一致性长稳、跨时区矩阵、移动端全部分辨率和全浏览器 UI 自动化，这些属于上线前工程验收范围。
