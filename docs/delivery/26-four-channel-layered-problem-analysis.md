# 四渠道反馈分层问题分析（只读 shadow 方案）

## 1. 当前真实口径（2026-09-10）

本次使用项目现有 `applicationSymptomDryRun` 的窄 Spring 上下文读取本机数据库。该上下文不装配
Cluster/Link/generation 写仓储，只输出聚合计数；输入总数严格校验为 26,141。V4 dry-run、隔离 BUILD
和独立 VERIFY 已于 2026-09-10 完成，预览白名单只读指向 VERIFIED generation
`shadow-12-full-20260910-capability-v4-r2`，未 ACTIVATE。

| application 复核结果 | 数量 | 渠道事实 |
| --- | ---: | --- |
| 已确认 | 12,023 | Station 10,916；Jira 855；红黑榜 252；质量平台 0 |
| 未解析（零命中） | 13,929 | Jira 6,748；Station 4,273；红黑榜 2,809；质量平台 99 |
| 歧义（多命中） | 189 | 红黑榜 149；Jira 40 |

对照基线 generation `shadow-11-full-20260909-post-identity-r3` 是 VERIFIED，算法版本固定为
`cluster-classification-v3-shadow-11`：26,141 条输入中 4,937 条 ACTIONABLE、461 个候选簇、
335 个 provisional 成员，其余 21,204 条是不同原因的旧终态，不是一个“失败关闭”业务类别。

旧 ACTIONABLE 的边界覆盖为：application 4,937、failureMode 4,937、target 2,307、action 428、
errorSignature 0。它说明现有结果能稳定回答一部分“哪个应用、什么故障模式”，但对象、动作和错误签名
仍明显不足。

V4 真实结果为：10,558 条 ACTIONABLE、15,583 条终态、1,358 个候选簇、671 个 provisional 成员。
ACTIONABLE 中 5,404 条属于已确认 application，5,154 条进入应用待确认隔离域；能力域仅 404 条
（3.8%）为 `UNCLASSIFIED`。边界覆盖为 application 5,404、failureMode 10,558、target 3,336、
action 2,350、errorSignature 0。待确认域与已确认域、零命中与多命中均不互相合并。

## 2. identity 解析审计与顺序

安全正文读取 SQL 只允许 `t_feedback_content.masking_status='PASS' AND is_deleted=0`。正文兜底仅适用于
Jira、质量平台和红黑榜；`REVIEW_NEEDED` 不会进入 matcher。matcher 只在当前 canonical identity 的
displayName 上做 NFKC、固定 Locale、小写后的精确提及匹配，不从正文学习新应用名。

解析顺序固定为：

1. 唯一活动权威 alias 命中：使用已确认 application；
2. 已审核且完整的 RESOLVED primary fact：使用其审计快照；
3. 对三类文本渠道读取 PASS 正文，唯一 canonical 命中时解析该 application；
4. 正文多命中进入 `pending:ambiguous`；零命中进入 `pending:unresolved`；
5. alias 或历史 fact 的歧义不得提前截断第 3 步；正文仍零命中时保留原歧义。

本次已修复第 5 点的控制流缺口。Station 不增加正文兜底，继续只信任其渠道权威身份事实。

## 3. 分层归簇模型

展示域是四个互斥一级区：

| 一级区 | 后端隔离键 | 工程展示名 | 当前白名单结果 |
| --- | --- | --- | --- |
| 已确认应用问题簇 | `resolved:<application>` | `知识课程·设备网络连接异常` | 5,404 条，可下钻 |
| 应用待定问题簇 | `pending:unresolved:<capability>`、`pending:ambiguous:<capability>` | `题目作答与提交待确认·提交题目时失败` | 5,154 条，可下钻 |
| 硬件／系统／其他问题分类 | 封闭 capability domain + fault family | `设备与硬件待确认·硬件失效` | 已进入待确认问题簇；证据不足的输入仍单独隔离 |
| 确实不可用内容 | 无 cluster key | 噪声、历史脱敏丢失、有效信息不足 | 6,102 条，仅隔离统计 |

页面四区数量严格相加为 26,141。未形成问题簇的工程分类输入池为 9,481 条：`NON_FAULT_INPUT`
7,008、`USER_INTENT_INCOMPLETE` 1,195、`NOVEL_SYMPTOM` 421、`MULTI_PROBLEM_AMBIGUOUS` 857；
这些记录证据不足，不冒充已经完成的硬件或系统故障簇。

二级原子问题指纹建议固定为：`subjectKind + controlledSubject + target + action + failureMode +
errorSignature + canonicalSymptom`。已确认 application 额外以 canonical application 作为最外层硬边界；
任何字段冲突都不得靠语义相似度跨越。零命中与多命中永远使用不同分区。

QUALITY 的硬件字段只能映射到封闭的 hardware subject/type 词表（如设备、主板、屏幕、摄像头、
麦克风），不得写入 application。未知值只标记 `hardware subject 待确认`，不回显原始自由文本。

## 4. 多问题与非故障输入

多问题拆分只能生成匿名原子投影：一个 source 可以产生多个 projection ordinal，但 projection 不拥有、
不回写 source link，也不能创建多个 MAIN。只有子句各自同时具备独立对象/动作/故障模式且不存在指代
依赖时才拆分；否则继续留在多问题隔离区。

`NON_FAULT_INPUT` 后续应以封闭规则重新区分硬件故障、需求建议、内容质量、使用咨询、账号、网络、
设备和其他。噪声、纯模板、乱码及确实无信息内容不强行归簇。

## 5. 已完成的最小安全闭环

- 预览 API 新增四区精确分母及可用性；总数不等于 26,141 时失败关闭。
- 已有候选簇新增工程问题名 `application·canonical symptom`；application 不再作为页面独立列。
- 页面按“问题深度分析”展示具体问题、数量、渠道、状态、五边界覆盖和匿名安全详情。
- 只读白名单已从旧 V3 切换到 VERIFIED V4 r2；应用为空时通过 V4 的 unresolved/ambiguous 两种候选
  重建，再用 cluster/name/五字段哈希反证唯一可读结果。
- generation 7 的 input、正逆序 assignment 和 verification digest 均完整；严格检查 null/非-null
  application 以及五字段混簇数为 0。
- 未调用 `ClusterReclassificationService`，未修改 Cluster、Link、MAIN、ACTIVE。

## 6. 真实执行记录与后续边界

第一次 generation 6 正确构建出同一份 1,358 簇，但 VERIFY 时因本地增量编译产物缺少 lambda class
失败关闭，状态永久保留为 FAILED。完整编译恢复后，以新 key 重新 BUILD/VERIFY 的 generation 7 与
generation 6 的 assignment digest 完全一致，证明该失败不是数据或算法漂移。

当前仍未把 7,008 条 `NON_FAULT_INPUT` 强行升级为故障簇，多问题也未在证据不足时拆分。后续优化应
优先对 2,013 条最大簇做匿名盲审，并扩展 errorSignature、硬件 subject/type 和咨询/需求的封闭本体；
任何进一步 generation 都需重新 dry-run、BUILD、VERIFY，仍不得 ACTIVATE 或放宽架构门禁。
