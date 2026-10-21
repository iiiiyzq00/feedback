# 项目快照（默认低 Token 入口）

> 最后同步：2026-09-03（Asia/Shanghai）。本文件是 `PROJECT_MEMORY.md` 的短派生视图，用于新任务启动和路由；发生冲突时，以当前代码、Flyway、可复现验证和 `PROJECT_MEMORY.md` 为准。

> 一期“渠道数据查看 + 问题深度分析”简化项目另有范围内唯一执行事实源 `PROJECT_PHASE_ONE_MEMORY.md`；需求以 `docs/delivery/14-phase-one-simplification.md` 为准。

## 启动步骤

1. 当前应用工作目录为 `/Users/yang/Feedback_platform/feedback-platform`，上下文脚本位于应用目录的 `tools/`。本候选没有 `.git` 元数据；保留现有文件，不执行清理或假报提交。
2. 长期不变的背景、架构、工程、安全与测试约束直接复用 `docs/reference/project-static-context.md`；根据下方任务路由只读一个当前交付或专题文档，再检查相关代码。
3. 仅在短快照不足以判断当前状态、阻塞或已知陷阱时，定向搜索 `PROJECT_MEMORY.md`；不要默认通读全文。
4. 不读取 `archive/`、验证证据、生成输出和缓存，除非任务明确要求历史证据。

## 固定本机部署命令

每次本机部署默认严格使用以下前后端命令，不再切换为 LaunchAgent、Nginx 或其他启动方式；若端口无监听，执行 `kill` 前先用 `lsof` 确认，避免空 PID。

```bash
cd /Users/yang/Feedback_platform/feedback-platform

kill "$(lsof -tiTCP:18080 -sTCP:LISTEN)"

SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/feedback_platform_local_deploy?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=false' \
AI_FEEDBACK_CLASSIFY_ENABLED=false \
CLUSTER_AI_ASSISTED_DECISION_ENABLED=false \
tools/manage-backend-local-secure.sh start

tools/manage-backend-local-secure.sh status
cd /Users/yang/Feedback_platform/feedback-platform/frontend

VITE_API_PROXY_TARGET=http://localhost:18080 \
npm run dev -- --host 0.0.0.0 --port 8088 --strictPort
```

## 当前事实

- 应用根目录：`../../../../feedback-platform.backup-20260905/`；后端为 Kotlin/Spring Boot，前端为 Vue 3，另含 Android SDK 与 Demo。
- Git：在 `codex/*` 等任务分支开发并通过 PR 合并；工作区有大量未提交跨栈改动，禁止覆盖或清理无关修改。
- 一期学习机反馈产品入口现统一聚合后台 `SDK` 与 `STATION_FEEDBACK` 两个独立来源码；接入、存储、认证、任务和审计仍各自独立，问题分析按一个逻辑学习机渠道计算跨渠道。V225 新增的一期角色 SDK 只读范围已随 V228 在本机部署库完成迁移核验。
- 数据：MySQL 为权威事实源；专用测试库实测 V234 为70张业务表，纠正旧迁移注释中的63张错误累计；Elasticsearch、Milvus 为派生索引。
- 迁移：代码连续到 V237，专用测试库已应用 V237/73张业务表；部署库与生产未应用。已应用迁移禁止编辑，只能新增普通 Git 文本的前向迁移。
- 渠道：SDK、旧反馈平台、Jira、质量平台、红黑榜保持语义和字段差异；外部分析渠道不回写来源系统。
- 隐私：一期删除人工隐私复核；正文经规则脱敏成功后自动进入平台并参加归簇，历史 `REVIEW_NEEDED` 经规则重处理成功后补入。技术脱敏失败必须失败关闭、审计和重试。关键信息与分析证据不再要求填写用途：技术角色看原文，产品角色看局部隐藏原文；仍保留实时鉴权、安全门禁、审计、`no-store` 和关闭清内存。
- AI：统计数字来自权限受控的程序查询；LLM 只分类或组织文字；人工结论优先。静态评测完成不等于部署库 Prompt 已发布。
- 验证：提交前运行 `tools/dev-check.sh --static`；普通代码变更运行 `--quick`，跨栈合并前运行 `--full`。本机通过不得表述为生产集成成功。

## 当前进度与未关闭项

- 一期页面治理与附件对象存储切换代码已进入本地候选：渠道列表关键信息由当前页批量读取并按角色直显，前端无逐行 N+1；详情去除重复附件/诊断请求，截图、图片、视频与日志逐项懒加载，RAW 原始日志下载使用独立用途令牌。安全放行后的新附件发布到私有 MinIO/S3，`AVAILABLE` 读取失败禁止回退本地。本机 `127.0.0.1:19000/feedback-private` 已完成 32,730 个附件、128,331,368,229 字节（119.52 GiB）的真实历史迁移与逐项旧文件清理；167 批后活跃迁移候选、清理候选、永久缺源和未闭合删除意图均为 0，两个显式历史根的 `.fbs` 均为 0，MinIO 数据约 120 GiB、磁盘可用约 138 GiB。流程按小批上传、对象长度/SHA-256 完整读回、5 秒单行 CAS、数据库 `AVAILABLE` 与白名单路径复核后，才用 `Files.delete` 精确删除一个旧密文并 CAS 清路径；禁止目录/递归/通配符删除及删除 MinIO 数据。新增附件清理开关已在本机安全启动脚本启用，由独立 worker 在发布 CAS 提交后复核清理暂存副本。大文件首字节超时根因是 JDK AES-GCM `CipherInputStream` 认证前不出流，现限制单文件最多 128 MiB、内存认证解密并及时覆零；此前完整后端退出源于无关回填/索引任务占用约 28 GiB，迁移已改为最小非 Web 上下文。主代码及集成测试源码编译通过，附件迁移定向集成测试 4 项通过；普通测试源码仍受既有 Feishu/Jira/SDK API 漂移阻塞。最终后端 PID 97995 在 18080 健康 `UP`。服务器/生产 MinIO 与 Git 仍为 `BLOCKED_EXTERNAL`，本机结果不代表生产部署。

- P3-01-R1待用户复审：三项P1已修复——渠道扩展缺行整批失败、锁后requestId当前读保证并发重放、迁移测试SQL收口到测试MyBatis XML。V236已从本地执行历史恢复为专用库应用原文，checksum固定为`1559985492`且新增本地防漂移断言；未执行repair。V236/V237专用MySQL全链、双事务并发、定向/完整后端、static/quick及前端211项/lint/build/包体均通过。P3-02未授权；未部署、未执行影子批次、不改活动Cluster/MAIN或页面读模型。
- 一期先完成 P1-P4，再恢复 C0-C5。P1-A 最新整改候选待用户复审：`NoSuchKey`/合法 `NoSuchVersion` 仅在 `HeadBucket` 成功后转为缺失，缺桶、403、网络及歧义错误失败关闭；read 流关闭、具名 source/SDK owner、ETag 不透明标签合同保持。六组29项、全新本机MySQL V236、真实MinIO、static/quick均通过；未部署、未关闭且不得进入P1-B/P2。
- `C0-P1-REV-01` 本地候选完成后暂停：Router/AppLayout 绑定源进入有界 C0 扫描且不扩张闭包；四个 MySQL gate 绑定独立 suite/case 并拒绝重放。治理/攻击测试 72/72、四组 MySQL 门禁和 static 通过；未生成 baseline、未提交/部署。现有候选和验证事实必须保留，待一期 P4 关闭后继续全仓 C0-C5。
- run 8 已在本机权威 MySQL 修复：非法簇 68183 归档，最终 `COMPLETED`、25,981/25,981、无错误；覆盖、唯一性、名称和统计门禁全通过。原 678 条重建为 14 个真实问题簇（677 条卡顿、1 条干扰），低信息残留和目标名称差值均为 0，正式审计完整。代码禁止再生成占位簇名、补齐“卡顿/干扰”信号并提供受状态与所有权约束的定向修复接口。仅限本机非浏览器修复，不代表生产发布。

- 一期权限新口径已部署到 macOS 本机：技术/产品为两个独占飞书角色，关键信息和证据直接查看，技术看原文、产品看局部隐藏原文，日志只查看不下载；V228 已成功应用。后端 PID 77393 在 18080 health `UP`，Vite PID 76935 在 8088 返回 HTTP 200，未认证诊断接口返回 401；未做浏览器目视验收，不等于生产发布。本机目标环境仍仅保留杨紫琦为 1 个有效管理员；后续用户首次飞书登录绑定后，管理员在“用户与权限”中按姓名分配技术或产品角色，无需手填稳定 ID 或组织同步。
- 95 条历史 `feedback_classify` 任务保持 `CANCELLED/PIPELINE_DISABLED`，epoch0 原审计不改并追加有效 epoch2 补证。碎簇强候选只允许真实认证操作者逐组 merge 小批验收，禁止约束绕行/V220 重跑。本机 PID 17265 在 18080 health `UP`，连接部署库、Flyway V222、两 AI 开关 false，invocation 零增量/pending=0。结论仅限 macOS 本机真实 MySQL 非浏览器验收，不代表生产发布。
- 本机已回补三来源受控身份与附件证据；不安全或不支持的日志包失败关闭。run 8 已完成 25,981/25,981；以上仅为本机 MySQL/Jira/ClamAV 非浏览器证据，不代表生产。
- 隐私人工复核已从本机路径移除：规则脱敏成功自动放行，技术失败继续失败关闭和重试；归簇不依赖 AI 分类/复核状态。
- 一期问题簇已明确不设置“近期反馈量”，不得引入固定7天等第二时间口径；规模与活跃性使用当前区间反馈记录数、首次发生时间和最近发生时间表达。
- 大日志改为5 MB有界预览并支持tar/tgz/嵌套tgz文本提取；截图/视频点击立即反馈、关闭取消且不受30秒总时限影响。问题分析全历史聚合经V229由约2.57秒降至0.45秒。quick/static通过；本机后端PID13330、前端PID48167，未做浏览器验收。
- SDK `trigger_type` 经V230由20扩至64字符，专用库与本机部署库通过。旧25条接入/24条来源及磁盘附件已删除；当前仅有两Demo各1条合成反馈，均`COMPLETED/STORED/PASS`，四类身份密文、完整诊断、日志/截图安全门禁及MAIN归簇通过，孤儿/统计漂移0。仅限本机，不代表生产。
- 归簇 AI 代码链已闭合且由程序校验边界；真实 AIHub 请求仍返回无明细 HTTP 400，AI worker 未启动，P3-01 不依赖该能力。
- 飞书同源回调、超时回滚和登录页恢复已完成本机代码验证；真实目视体验仍待复测。

- 站内通知已按事件语义区分告警、反馈业务和系统三类：只有告警触发/升级/RCA进入Incident，分发/转派/接收提醒/处理催办独立展示；定向6项测试和TypeScript检查通过，尚未部署。
- 渠道列表、详情、受控诊断入口及一期页面删减已部署到本机；登录后真实数据页面目视验收仍未完成。
- Jira 证据链路现同时支持增量和全历史分窗回补；批量认领使用 `SKIP LOCKED`、`READ COMMITTED` 与瞬时死锁短重试，批量失败逐工单隔离。附件流经完整性、ClamAV、内容安全检查与加密落盘；大文件扫描使用按大小计算的有限超时，不支持或不安全解包时确定性拒绝，不进入人工队列。质量平台当前无来源附件，不伪造证据。
- 详情返回列表已修复并通过定向测试和 TypeScript 检查，尚未部署。
- 分发后的人工站内催办已完成代码实现：支持待接收提醒、处理中催办、团队范围/原分发人权限、幂等、30分钟频控、独立已读审计和经办时间轴；本机编译、单元与前端定向测试通过，容器集成测试因本机Docker不可用待核验，尚未部署。
- 本机开发实例由安全管理脚本托管后端（PID 77393、18080、health UP），Vite 监听 8088 并代理到后端（PID 76935），Flyway V228；此前 LaunchAgent 与隔离 Nginx 已停。该运行态仅用于本机验收。
- Jira 正式内容二次降级仍有工作台展示/重拉闭环需要用部署库核验；不得代替授权人员批量判定或放行隐私任务。
- 真实 LLM、物理 Milvus、邮件、飞书/短信/电话供应商、生产密钥托管和故障演练仍需目标环境证据。

## 任务路由

| 任务 | 首选入口 |
|---|---|
| 工程治理 | `docs/delivery/16-ai-engineering-remediation-governance.md` |
| 一期简化：渠道页面、深度分析、直接归簇与飞书权限 | `docs/delivery/14-phase-one-simplification.md`；阶段状态见 `docs/PROJECT_PHASE_ONE_MEMORY.md` |
| 反馈问题簇归类现有代码流程：逐步骤输入输出、清洗、相似度、参数、低信息与 application_id 行为 | `docs/reference/feedback-cluster-classification-code-flow.md`（该流程唯一汇总审阅入口） |
| 问题簇归类优化：边界、原子问题、AI权限、簇名演化与小样本验收 | `docs/delivery/15-problem-cluster-classification-optimization.md` |
| 产品、页面、五渠道字段与处置流程 | `docs/delivery/03-product-requirements.md` |
| SDK、核心 API、诊断信息 | `docs/delivery/06-sdk-and-core-api.md` |
| Jira/质量/红黑榜导入 | `docs/delivery/07-table-imports.md` |
| 系统指标与监控口径 | `docs/reference/system-metrics-guide.md` |
| AI Skill/Prompt 发布治理 | `../../docs/reference/governance/ai-skill-prompt-production-runbook.md` |
| 上下文压缩与 Token 成本 | `docs/reference/context-efficiency.md` |
| AI 开发经验与高频踩坑 | `docs/reference/ai-development-lessons.md` |
| 状态、阻塞或踩坑仍无法确定 | 在 `docs/PROJECT_MEMORY.md` 中定向搜索关键词 |

## 下一步上限

每个任务只维护最多三个下一步。完成一个阶段后，用上下文压缩模板生成新的执行摘要，不携带完整日志、失败方案或已经关闭的历史。
