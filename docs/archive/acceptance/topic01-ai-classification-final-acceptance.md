# 专题 1：AI 分类与待复核最终验收

> 验收日期：2026-08-04（Asia/Shanghai）  
> 验收口径：实习生项目最终汇报，不包含生产灰度、正式模型供应商和上线运维门禁  
> 结论：PASS

## 1. 闭环范围

1. 分类字典必须有稳定编码、中文名称、定义、正例和排除边界。
2. AI 生成结果必须记录分类字典版本、提示词版本和模型版本。
3. 旧版无字典版本的数据必须有明确兼容方式；未知新类别不得静默兜底。
4. 低置信度或 S0/S1 结果必须进入人工复核；复核前不得进入新归簇主关联。
5. 人工可采纳或修正分类/严重度，且留下审核记录。

## 2. 实现结论

- 唯一分类字典版本为 `ai-taxonomy-v1`，包含 BUG、HARDWARE、CONTENT_ERROR、DEMAND、CONSULT、PRAISE、COMPLAINT 七类和 S0～S4 五级严重度。
- 字典由后端 `AiClassificationTaxonomy` 统一维护，推理校验、人工修正和前端下拉框使用同一来源。
- `GET /api/v1/ai-reviews/taxonomy` 返回版本、兼容范围、未知类别策略和完整展示字段。
- 旧的无版本数据按 `legacy-unversioned → ai-taxonomy-v1` 解释；六个历史别名可映射到稳定编码。
- 无法识别的新类别采用失败关闭，不能再把 UNKNOWN 当作 CONSULT 自动写入。
- V116 为所有历史 AI 结果补写 `classification_taxonomy_version=ai-taxonomy-v1`，新结果自动记录该版本。
- 复核阈值继续使用运行时配置 `ai.review-confidence-threshold=v1/0.85`；S0/S1 无条件复核。
- 调度器和归簇引擎均限制只有 `NOT_REQUIRED/CONFIRMED/CORRECTED` 才能进入归簇。

## 3. 验收证据

| 检查项 | 结果 |
|---|---|
| 分类字典专项单测 | PASS：7 类唯一且字段完整，旧别名兼容，未知类别失败关闭 |
| AI 复核服务专项单测 | PASS：阈值、权限、脱敏、批量原子更新和审计 |
| 归簇门禁专项单测 | PASS：调度与引擎双重拦截 PENDING |
| 前端类型检查 | PASS：`npx vue-tsc --noEmit` |
| 前端回归 | PASS：13 个文件、33 项测试 |
| 共享数据库迁移 | PASS：V116 success；890/890 条存量记录均有字典版本 |
| 字典真实 API | PASS：version=ai-taxonomy-v1，categories=7，severities=5 |
| 全新隔离真实链路 | PASS：人工复核前 MAIN=0；复核后 3 渠道均完成 MAIN、ES、Milvus，同批数据随后回滚 |

容器化集成测试因本机 Docker 服务不可用未启动，已由共享 MySQL、真实后端、ES 和 Milvus 的隔离回放覆盖本专题核心链路，不作为项目汇报级阻断。

## 4. 历史数据边界

共享库存在门禁实现前产生的历史 `PENDING + MAIN` 测试数据。它们不代表当前版本仍可绕过门禁，也未在本次验收中宽泛删除。当前版本使用全新唯一前缀数据证明“复核前 MAIN=0”，避免用修改历史夹具制造通过结果。

## 5. 汇报结论

专题 1 已达到实习生项目最终汇报程度：分类含义可解释、版本可追溯、旧值可兼容、未知值安全失败、人工复核可操作、下游门禁可由自动化和真实链路重复证明。生产环境的模型校准、灰度比例和正式供应商 SLA 不属于本轮范围。
