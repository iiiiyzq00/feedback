# 一期问题簇归类准确性优化方案（已批准 P3-01）

> 所属阶段：一期页面与产品治理 P3（归簇可解释性和准确性）
> 状态：用户于 2026-09-01 批准 P3-01，并在首次验收发现三项 P1 后批准 P3-01-R1 修订；P3-02 至 P3-04 仍需逐批重新审批
> 形成日期：2026-09-01（Asia/Shanghai）
> 事实边界：基于当前工作树、`docs/reference/feedback-cluster-classification-code-flow.md` 和定向代码审查；不代表目标环境或生产效果
> 执行约束：本方案是总设计。用户批准后只实施 P3-01；每个批次完成自审、验证、独立审核和用户验收后，下一批仍需提交更新后的批次方案并重新获批。

> 新增不可变要求：用户明确要求后续代码修改严格执行全项目代码规范，不得再次出现直接 SQL、多表业务查询、弱类型合同、职责混乱、权限分散、注释缺失、压缩式业务逻辑或测试只复制实现等既有代码类问题。

## 1. 用户问题、当前行为和成功标准

### 1.1 用户问题

当前反馈归簇效果差，主要不是单一阈值问题，而是问题身份和落库语义存在结构性缺陷：

1. `application_id` 没有进入问题身份、问题指纹、候选硬过滤和簇唯一约束；不同应用中的相同症状可能被静默合并。
2. 确定性决策即使返回 `NEW_CLUSTER`，落库仍可能因规范簇名相同复用旧簇，并改写成 `AUTO_HIGH/1.0`。
3. 当前画像可能把不同子句中的目标、动作和故障模式拼成原文不存在的组合；多个独立问题被任意压成最早故障。
4. 缺失字段被当作无冲突；低信息内容继续形成活动业务 `MAIN`，污染问题排行和统计。
5. 在线逐条决策只比较单个规范症状，处理顺序、首条样本和全局同名约束会同时造成误合并与碎簇。
6. AI 权限模型和真实 AIHub 当前状态都不能弥补上述确定性边界缺陷；MySQL 仍必须是权威事实源。

### 1.2 当前调用链和状态

```text
安全反馈筛选
  -> 加载来源/正文/渠道扩展/可选 AI 分类
  -> 解析 productScope 和上下文
  -> 从全文提取一个主问题画像
  -> Elasticsearch/Milvus/MySQL 召回候选
  -> 对象/动作/故障/错误签名冲突与相似度决策
  -> 可选 AI 确认或降级
  -> 按候选或全局规范簇名关联/创建 Cluster
  -> 写唯一活动 MAIN、统计和审计
  -> 一期 ACTIVE generation 投影供列表、详情和统计读取
```

当前关键状态：

- 可见归簇事实：`t_cluster`、`t_cluster_link`。
- 运行与回滚事实：`t_cluster_reclassification_run`、`t_cluster_reclassification_cluster_snapshot` 以及 link 上的 run/superseded 字段。
- 一期读模型使用独立 generation 投影；读侧只接受一个完整 ACTIVE generation。
- V224 已建立全局活动 `cluster_name_key` 唯一约束。V224 禁止修改，本方案只能用新的前向迁移替换其错误约束。
- 当前一期逻辑学习机渠道由 `SDK` 与 `STATION_FEEDBACK` 组成；两个来源可以在解析为同一应用身份后合并，但不同应用默认必须分簇。

### 1.3 成功标准

1. 不同应用的相同描述默认不能进入同一活动问题簇；只有经过审计的应用别名映射可以证明 SDK 与站端属于同一应用。
2. `NEW_CLUSTER`、应用身份未知、低信息和多问题边界不清均不得被名称复用或 AI 静默升级为已有簇。
3. 问题画像中的对象、动作、现象和错误签名必须来自同一原子子句；无法可靠选择唯一主问题时失败关闭。
4. 低信息和无法确定的问题形成可统计、可重试、可审计的分类终态，但不形成活动业务 `MAIN`。
5. 簇名称包含“应用 + 功能/对象 + 症状”，名称是展示结果，不再充当问题身份。
6. 迁移期间旧读模型保持可用；只有影子 generation 全部门禁通过后才允许原子切换。
7. 小样本 P0 误合并为 0、跨应用污染为 0、低信息业务 MAIN 为 0、处理顺序改变不改变期望归属。

## 2. 范围和明确排除

### 2.1 本方案范围

- 一期 `SDK`、`STATION_FEEDBACK` 的应用身份解析和跨渠道同应用映射。
- 主问题原子子句提取、问题指纹 v2、三态硬边界、候选召回、确定性决策、AI 权限和簇名演化。
- 低信息、应用身份未知、多问题边界不清的持久化终态。
- `t_cluster`、`t_cluster_link` 和重分类 generation 的前向迁移、影子回填、切换与回滚。
- 一期问题分析列表、簇详情、簇内反馈和反馈详情的应用身份与决策解释字段。
- Elasticsearch/Milvus 派生索引在切换后的重建，不将其用于迁移正确性判断。

### 2.2 不在本方案范围

- 不恢复人工隐私复核，不把 AI 分类状态重新设为归簇资格门禁。
- 不在本批次为 Jira、质量平台和红黑榜猜测应用身份；未来必须有单独批准的来源映射方案。它们现有数据不得被一期迁移误写。
- 不把一条反馈自动拆成多个活动业务 `MAIN`。一期先保持“一条反馈最多一个主问题”；若存在多个独立且无法确定主次的问题，记录 `MULTI_PROBLEM_AMBIGUOUS` 终态，不任意选第一个。未来如需一对多，必须另行审批 API、统计和下游生命周期变化。
- 不自动把旧簇上的 RCA、Incident、告警、知识库结论复制到拆分后的任意子簇。
- 不编辑任何已应用 Flyway，不启动全量重分类，不部署生产，不用浏览器验收。
- 不在 P3 顺手清理全仓剩余 C0-C5 债务；但 P3 新代码不得新增直接 SQL、JOIN、弱类型 API 或权限重复实现。

### 2.3 P3-01 代码质量硬门禁

本批虽然属于一期 P3，也必须按企业级代码治理标准实现，不允许把历史债务当作先例：

1. 所有新增或本批触达的数据访问只能使用 MyBatis Mapper/XML + Repository；Service、Controller、任务、测试和普通脚本不得出现 SQL 字符串、JDBC、JPA native query 或动态 SQL 拼装。
2. 每个 Mapper 只访问一张业务表；禁止 JOIN、跨表相关子查询、N+1、无限 `IN` 和全量内存分页。主表稳定分页后，按键有界批量加载并用强类型对象在代码中组装。
3. Controller 只做协议适配和校验；Application Service 编排用例和事务；Domain/Assembler 表达规则；Repository 协调单表 Mapper。不得形成新的巨型类或混合职责方法。
4. API 和跨层合同必须使用具名 Request/Response/Query/Command/DTO/PO；不得暴露 Entity、PO、数据库行或 `Map<String, Any?>`。公开字段必须有中文业务描述、校验、枚举、示例、权限、脱敏和来源语义。
5. 认证、授权、数据范围、字段策略和审计上下文必须复用公共组件；业务代码不得解析 Token、猜测角色或复制权限分支。
6. 注释必须解释职责、不变量、状态转换、失败关闭、幂等、事务、并发、安全和阈值依据；禁止复述语法、压缩单行业务逻辑、模糊命名和静默兜底。
7. 先冻结合同和反例测试，再写实现。不得降低旧断言、用 Mock 冒充真实集成，或为通过检查扩大债务基线。
8. 每个提交候选必须依次完成作者自审、定向自动验证、`dev-check --static`、普通代码 `--quick`、独立审核、发现修复和重新验证；任一新增治理违规都阻止交付。
9. 若现有触达代码无法在批准范围内合规改造，立即停止并提交范围变化说明，不能保留违规路径或绕过门禁继续实现。

## 3. 目标不变量和类型合同

### 3.1 应用身份

新增领域类型 `ApplicationIdentity`：

| 字段 | 类型 | 语义 |
|---|---|---|
| `id` | `Long` | MySQL 内部主键，不对外作为业务标识 |
| `publicId` | `UUID` | 稳定公开标识 |
| `canonicalKey` | `String` | 规范应用键，唯一、不可由展示名替代 |
| `applicationCode` | `String` | 产品确认的应用代码 |
| `applicationName` | `String` | 中文展示名 |
| `status` | enum | `ACTIVE/INACTIVE` |
| `mappingVersion` | `Long` | 别名映射乐观锁版本 |

应用别名以 `(sourceChannel, aliasType, normalizedAlias)` 唯一映射到一个应用身份：

- SDK 候选别名：`app_id`、`package_name`；必须至少有一个非空。同一来源记录同时提供的两个别名先归入同一个临时身份；若后续发现同一别名指向多个身份，则整体转为歧义，不能按先到记录覆盖。
- STATION_FEEDBACK 候选别名：`source_app_id`，`source_app_name` 只能辅助展示，不能单独证明同一应用。
- SDK 与站端的跨渠道等价关系必须由明确映射证明；名称相同、包名包含关系和模型推测均不能自动建映射。
- 无映射或命中多个身份时结果分别为 `APPLICATION_UNRESOLVED`、`APPLICATION_AMBIGUOUS`，不得归簇。

### 3.2 主问题事实

新增 `PrimaryProblemFact`，每条反馈最多一条：

| 字段 | 语义 |
|---|---|
| `sourceId` | 反馈来源 ID，唯一 |
| `applicationIdentityId` | 已解析应用身份 |
| `classificationStatus` | `ACTIONABLE/INSUFFICIENT_INFORMATION/APPLICATION_UNRESOLVED/APPLICATION_AMBIGUOUS/MULTI_PROBLEM_AMBIGUOUS/RETRYABLE_FAILURE` |
| `primaryClause` | 从安全正文选择的完整原子子句，不超过约定上限 |
| `targetObject` | 与主故障同子句的对象 |
| `scenarioOrAction` | 与主故障同子句的动作 |
| `failureMode` | 唯一主故障模式 |
| `errorSignature` | 同子句或明确关联堆栈中的稳定签名 |
| `canonicalSymptom` | 自然问题描述，不含应用名 |
| `problemFingerprintV2` | 包含应用身份的稳定 SHA-256 |
| `extractorVersion/inputHash` | 可复现版本与安全输入摘要 |
| `reasonCode/reasonDetail` | 终态或失败原因，禁止写原始敏感正文 |

指纹 v2 材料固定为：

```text
fingerprint_version
application_canonical_key
capability
scenario_or_action
failure_mode
stable_signature_type
stable_error_signature
information_quality
normalized_symptom_fallback
```

`functionModule` 和 `issueCategory` 是辅助边界及召回字段，不得替代应用身份；是否进入指纹必须通过标注样本验证后固定版本，不能运行时随意变化。

### 3.3 决策语义

- 硬边界使用 `MATCH/CONFLICT/UNKNOWN` 三态。`UNKNOWN` 不是兼容证据。
- `NEW_CLUSTER` 在持久化层仍必须新建；任何名称碰撞都只能生成应用限定名称或稳定后缀，不能改成复用。
- 只有 `LINK_EXISTING` 才能关联已有簇，且必须再次校验应用身份、指纹版本和硬边界。
- 当同一应用问题身份已经存在时，确定性结果只能是 `LINK_EXISTING` 或 `DEFERRED`；`NEW_CLUSTER` 仅适用于不存在该身份的情况。并发插入命中新唯一约束时必须重读并重新校验，不能直接把冲突行当成相似度 1.0。
- 名称唯一性不再表达问题唯一性。活动唯一约束改为 `applicationIdentity + problemFingerprintV2` 的稳定问题身份键。
- AI 只能在同一应用、无硬冲突的候选白名单中确认确定性候选或建议降级；不能把确定性新建、未知边界或终态升级为关联。
- 低信息、身份未知和多问题歧义没有 Cluster/MAIN；调度器识别其终态，不重复消费。

## 4. 目标流程：逐步输入、输出、状态和失败路径

| 步骤 | 输入 | 输出 | 失败关闭行为 |
|---|---|---|---|
| 1. 资格读取 | `sourceId`、安全正文状态、删除状态 | 最多 100 条稳定排序来源 ID | 非 PASS、已删除跳过；查询失败整批不写 |
| 2. 单表批量加载 | source/content/SDK/station/AI 各表事实 | `FeedbackClassificationInput` | 缺权威来源或正文记可重试失败，不猜值 |
| 3. 应用解析 | 渠道别名、别名映射、身份主表 | `ResolvedApplicationIdentity` 或终态 | 未解析/歧义写终态，不进入召回 |
| 4. 子句与原子问题 | 安全标题、正文、允许的 AI 摘要建议 | 主子句候选及证据位置 | 多个独立问题无法判主次时写 `MULTI_PROBLEM_AMBIGUOUS` |
| 5. 画像与指纹 | 单一主子句、同句上下文、应用身份 | `PrimaryProblemFact(ACTIONABLE)` | 低信息写终态；超长只取完整句，不截半句 |
| 6. 候选召回 | 应用身份、指纹 v2、结构画像、全文/向量 | 有界候选和召回诊断 | ES/Milvus 失败只关闭对应弱召回；MySQL 强召回失败整条不决策 |
| 7. 硬边界 | 当前画像、候选簇画像 | 三态边界证据 | 应用不同直接冲突；UNKNOWN 不自动关联 |
| 8. 决策与 AI | 无冲突候选、语义分、排序分、可选 AI | `LINK_EXISTING/NEW_CLUSTER/DEFERRED` | AI 超时/低置信保留确定性结论；不扩大权限 |
| 9. 持久化 | 决策、事实版本、run/generation | Cluster、MAIN、决策审计或终态 | 锁顺序固定；并发 MAIN 胜出后重读；任何门禁失败整事务回滚 |
| 10. 读模型 | 已完成归簇 generation | 一期 ACTIVE generation 投影 | 投影不完整不激活；旧 generation 继续服务 |

## 5. 分批实施计划

### P3-01：应用身份和影子数据基础

目标：建立可审计的应用身份，不改变当前活动归簇和页面读结果。

实施内容：

1. 新增应用身份、别名映射和主问题事实表的前向迁移；版本号在实施时按仓库真实最新迁移分配。
2. 新增 MyBatis 单表 Mapper、Repository 和强类型对象；每批最多 200 个 ID/别名。
3. 为 SDK 与 STATION_FEEDBACK 建立影子解析器和诊断报告，只写身份/终态事实，不写 Cluster/MAIN。
4. 自动建档只能做到“一条来源别名一个保守身份”；跨渠道 alias 合并必须由产品确认并通过管理员命令写入。
5. 增加具名、幂等、审计的管理员映射 API；不新增页面。

本批停止点：身份覆盖、歧义、跨渠道映射和权限测试交付用户验收；不得继续修改归簇决策。

#### P3-01 精确执行合同（2026-09-01 表收敛修订，已批准）

本节只细化 P3-01 已批准目标，不授权 P3-02 的指纹、归簇决策、活动 `Cluster/MAIN` 或读模型变更。当前实现前检查已发现工作树存在普通 Git 文本迁移 V236，因此本批预留下一可用版本 V237；若并行 P1 在本批落盘前占用该版本，必须先更新本节并重新确认迁移文件名，禁止复用、覆盖或编辑既有迁移。

当前行为与本批输入输出：

1. 当前 SDK `app_id/package_name` 与站端 `source_app_id/source_app_name` 尚未形成统一、可审计的应用身份事实，现有活动归簇也不以统一应用身份作为硬边界。
2. 影子输入仅为 `t_feedback_source` 中 `SDK/STATION_FEEDBACK` 来源及其各自单表渠道事实；SDK 至少一个 `app_id/package_name` 非空，站端只把 `source_app_id` 作为身份别名，`source_app_name` 仅作展示证据。
3. 单条输出为应用身份、别名映射和 `PrimaryProblemFact` 影子事实。`application_resolution_status` 只允许 `RESOLVED/UNRESOLVED/AMBIGUOUS`；P3-01 的 `classification_status` 固定为 `PENDING`，不得冒充 P3-02 才定义的 `ACTIONABLE` 或其他问题分类终态。
4. 未见别名可按“一条来源别名一个保守身份”自动建档；同一 SDK 记录同时携带的 `app_id/package_name` 绑定到同一保守身份。若别名已指向不同身份则写 `AMBIGUOUS` 并失败关闭，禁止覆盖既有映射。
5. SDK 与站端即使展示名相同也不得自动合并。跨渠道关系只能由管理员别名重绑命令建立，并保留请求、操作者、原因、前后版本和审计证据。

表收敛结论：仓库在 V156 已将历史影子/过程表大批物理退役，但 2026-09-01 对专用 `feedback_platform_test` 的只读对账证明，V234 且无失败迁移时实际已有 70 张业务表，并非 V231～V234 注释和此前方案沿用的 63 张。差异来自 V195 以后新增表未被后续表数注释完整累计；已应用迁移禁止为修正文案而编辑。因而本批禁止为单个命令、批次、诊断或审计结果分别建表。新表必须同时满足独立业务身份、独立生命周期、独立基数和数据库唯一性约束四项条件；不满足时合并到所属领域事实。按此门禁，本批仍仅建议三张领域表，若获差异批准，迁移完成后的专用库业务表数应从 70 精确变为 73：

- `t_application_identity`：稳定应用身份、展示名、生命周期状态和 `mapping_version`。
- `t_application_identity_alias`：应用别名的版本化绑定事实。活动版本以 `(source_channel, alias_type, normalized_alias)` 唯一；每次人工重绑追加不可变新版本并失效旧版本，保存稳定 alias ID、identity、版本、前序版本、`request_id`、命令指纹、操作者、原因和结果。该业务事实同时完成重绑历史、CAS 与请求幂等，不再单独建命令账本表；原始敏感值不进入日志。
- `t_feedback_primary_problem_fact`：以 `feedback_source_id` 唯一，保存身份解析状态、身份快照、解析规则版本、失败原因和固定 `PENDING` 分类状态；不保存或修改活动 Cluster/MAIN。

不复用现有 `t_alert_occurrence` 作为应用身份命令账本：它是告警领域表，当前承载 Cluster 命令属于既有治理债务；继续把新领域命令写入该表会扩大语义污染。也不把应用身份塞入 `t_feedback_identity`，后者是手机号、邮箱、设备 SN 等隐私身份线索，生命周期和权限边界不同。

管理员 API 冻结为：

- `GET /api/v1/admin/application-identities/diagnostics`：返回影子覆盖、未解析、歧义及 alias 类型计数。
- `GET /api/v1/admin/application-identities`：稳定游标分页返回应用身份。
- `GET /api/v1/admin/application-identities/{applicationId}/aliases`：返回指定身份的 alias 及版本。
- `POST /api/v1/admin/application-identities/shadow-batches`：显式执行最多 100 条的影子小批；请求含稳定 `afterSourceId`、`limit` 和解析规则版本，依靠 source fact 与活动 alias 唯一键实现结果幂等，不为批次另建运行表，默认不自动启动历史处理。
- `POST /api/v1/admin/application-identities/{applicationId}/aliases/rebind`：请求含 UUID `requestId`、`aliasId`、`expectedAliasVersion`、`expectedTargetVersion` 和原因；同号同命令重放原结果，同号异命令返回冲突。

所有 API 使用具名 Request/Response、Bean Validation 和中文 OpenAPI Schema，不返回 Entity、PO、`Map<String, Any?>` 或未说明 JSON。`/api/v1/admin/**` 继续由 `WebSecurityConfig` 集中要求 `ADMIN`；服务只通过 `CurrentWebPrincipalProvider` 获取操作者并调用既有集中审计入口，不复制主体解析、权限判断或审计 SQL。不存在、非活动、版本不符或目标变化均失败关闭。

数据访问和事务边界：

1. 每个 Mapper 只访问一张表；主游标批次上限 100，所有 `IN` 上限 200，空集合不查库，禁止 JOIN、跨表子查询和 N+1。
2. 应用层批量加载 source、SDK/站端事实、alias、identity 和既有 primary fact 后以强类型对象组装；缺失、重复、乱序或超限均显式拒绝，不猜测。
3. 自动建档和事实写入各自在明确事务内完成。唯一键竞争后重读；若竞争结果与本命令输入不一致则写歧义/冲突，不静默改绑。
4. alias 重绑在 alias 版本事实上使用 request ID 唯一约束和 alias/target 双版本 CAS；固定锁顺序为 alias -> source identity -> target identity，任一更新计数异常整事务回滚。同 request ID 同指纹从历史版本重放原结果，同 request ID 异指纹返回冲突。

精确变更文件清单：

- `backend/src/main/resources/db/migration/V237__create_application_identity_shadow_foundation.sql`
- `backend/src/main/kotlin/com/feedback/persistence/applicationidentity/ApplicationIdentityPersistence.kt`
- `backend/src/main/kotlin/com/feedback/persistence/applicationidentity/ApplicationIdentityMappers.kt`
- `backend/src/main/kotlin/com/feedback/repository/applicationidentity/ApplicationIdentityRepositories.kt`
- `backend/src/main/kotlin/com/feedback/model/applicationidentity/ApplicationIdentityContracts.kt`
- `backend/src/main/kotlin/com/feedback/service/applicationidentity/ApplicationIdentityResolver.kt`
- `backend/src/main/kotlin/com/feedback/service/applicationidentity/ApplicationIdentityShadowService.kt`
- `backend/src/main/kotlin/com/feedback/service/applicationidentity/ApplicationIdentityAdminService.kt`
- `backend/src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminContracts.kt`
- `backend/src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminController.kt`
- `backend/src/main/resources/mybatis/mapper/applicationidentity/ApplicationIdentityMapper.xml`
- `backend/src/main/resources/mybatis/mapper/applicationidentity/ApplicationIdentityAliasMapper.xml`
- `backend/src/main/resources/mybatis/mapper/applicationidentity/ApplicationIdentityPrimaryFactMapper.xml`
- `backend/src/main/resources/mybatis/mapper/applicationidentity/ApplicationIdentitySourceMapper.xml`
- `backend/src/main/resources/mybatis/mapper/applicationidentity/ApplicationIdentitySdkMapper.xml`
- `backend/src/main/resources/mybatis/mapper/applicationidentity/ApplicationIdentityStationMapper.xml`
- 对应的 `backend/src/test/kotlin/com/feedback/{persistence,repository,service/applicationidentity,controller/admin,security}/` 定向测试文件，以及本方案、`docs/PROJECT_SNAPSHOT.md` 和完成后按治理要求更新的项目记忆。

不修改 `WebSecurityConfig`、现有 Entity、Cluster/Link Mapper、调度器或一期页面代码。P3-02、P3-03 默认零新增表；P3-04 的候选 generation 表不在本批授权内，届时必须先比较“扩展既有 Cluster/Link”与“独立 generation 表”的表预算、隔离性和回滚风险再单独审批。发现必须跨出上述清单、必须新增第四张表、必须修改活动归簇或无法使用单表批量组装时立即停止并重新审批。

P3-01 回滚与验证：Schema 只前滚、不删除三张新表；运行回滚通过关闭/停止管理员影子命令并保留影子事实，旧活动 Cluster/MAIN 和页面读模型天然继续服务。测试必须覆盖解析正反例、未知/歧义、同号重放/异命令冲突、双版本 CAS、并发唯一键、alias 历史不可变且仅一条活动版本、401/403/管理员成功、直接 ID 失败关闭、Mapper 无 JOIN/空集合/100 与 200 上限、迁移普通文本、表数从 70 精确变为 73、专用 MySQL 正反向兼容，以及证明 P3-01 对 `t_cluster/t_cluster_link` 零写入；完成顺序固定为作者自审、自动验证、独立代码审查、问题清零、用户验收。

### P3-02：确定性边界、指纹 v2 和低信息终态

目标：关闭跨应用误合并、NEW 被名称复用和低信息污染。

实施内容：

1. `ProblemProfile` 引入强类型应用身份，发布指纹 v2。
2. 候选 MySQL 强召回先按应用身份过滤；ES/Milvus 查询携带可重建的应用过滤字段。
3. 硬边界改为三态；修正 `NEW_CLUSTER` 持久化语义。
4. 前向替换 V224 的全局活动名称唯一约束：保留 `cluster_name_key` 为普通检索索引，新增应用问题身份唯一键。
5. 低信息、应用身份未知和歧义写主问题事实终态，调度器不重复消费且不产生业务 MAIN。
6. 现有跨应用合并测试改为反例；补同应用跨 SDK/站端的正例。

本批停止点：只验证新数据和影子样本，不启动历史重分类。

### P3-03：原子子句、候选质量、AI 权限和名称演化

目标：避免跨子句拼接画像，降低碎簇并保持名称可解释。

实施内容：

1. 先按完整句、转折、因果和时序边界生成子句，再在同一句内绑定对象、动作、故障和签名。
2. 单一明确问题写 ACTIONABLE；多个独立问题无法可靠判主次时写终态，不取“最早故障”冒充主问题。
3. 确定性语义分与上下文排序分彻底分离；上下文只能排序，不能越过自动关联门禁。
4. 每簇保存有界代表样本/画像摘要；候选比较不只依赖首条规范名称。代表样本最多 5 条，更新使用版本 CAS。
5. AI 仅处理同应用候选白名单中的歧义解释和确认，真实 AI 不可用时确定性路径完整闭合。
6. 簇名格式为“应用名 + 功能/对象 + 具体症状”；名称演化必须覆盖既有代表样本，不得泛化为“功能异常”。

本批停止点：约 30 至 50 条脱敏黄金样本和随机顺序重放全部通过，仍不切换历史数据。

### P3-04：历史影子重分类、切换和用户验收

目标：在不破坏旧读模型的前提下生成并切换新归簇 generation。

实施内容：

1. 固定 eligible 快照；使用 run ID 在专用候选 generation 表中构建候选簇和候选成员，不提前写正式 Cluster/Link；同时构建一期投影 generation。
2. 每批 100 条，提交后清理持久化上下文；支持租约、幂等重试、失败源隔离和断点续跑。
3. 门禁全部通过后，在单个受控事务内停写、停用旧 MAIN、激活新 MAIN/Cluster，并激活新一期投影 generation。
4. Elasticsearch/Milvus 在 MySQL 切换后按新 generation 重建；索引失败不回滚 MySQL，但搜索能力失败关闭并告警。
5. 完成目标环境 API、权限和用户验收后再关闭 P3；未经用户确认不得进入 P4 或清理旧 generation。

## 6. 涉及文件、API、表和外部系统

### 6.1 预计修改的现有代码

- `backend/src/main/kotlin/com/feedback/service/cluster/ClusterDecisionModel.kt`
- `backend/src/main/kotlin/com/feedback/service/cluster/ClusteringEngine.kt`
- `backend/src/main/kotlin/com/feedback/service/cluster/ProductPhenomenonFormatter.kt`
- `backend/src/main/kotlin/com/feedback/service/cluster/ClusterAiAssistedDecisionService.kt`
- `backend/src/main/kotlin/com/feedback/service/cluster/ClusterReclassificationService.kt`
- `backend/src/main/kotlin/com/feedback/persistence/cluster/ClusterPersistence.kt`
- `backend/src/main/kotlin/com/feedback/repository/cluster/ClusterRepositories.kt`
- `backend/src/main/resources/mapper/cluster/` 下对应单表 Mapper XML
- 一期问题分析 contracts、application service 和 generation 投影装配代码
- 对应单元、权限、契约、MySQL 集成和投影 generation 测试

预计新增职责类：

- `ApplicationIdentityResolver`：别名规范化和唯一身份解析。
- `PrimaryProblemExtractor`：子句级主问题提取，不访问数据库或网络。
- `ProblemBoundaryPolicy`：三态硬边界。
- `ClusterIdentityKeyFactory`：问题身份键和名称键分离。

精确文件清单必须在每个批次方案中冻结；发现需要跨出该清单时停止并重新审批。

### 6.2 数据表

建议新增：

- `t_application_identity`
- `t_application_identity_alias`
- `t_feedback_primary_problem_fact`
- `t_cluster_reclassification_candidate_cluster`
- `t_cluster_reclassification_candidate_member`

建议前向扩展：

- `t_cluster`：应用身份、指纹 v2、指纹版本和稳定问题身份键。
- `t_cluster_link`：应用身份快照、指纹 v2、主问题事实版本；保留旧字段兼容和审计。
- `t_cluster_reclassification_run` / snapshot：影子构建、激活 generation、回滚状态和门禁摘要。
- 一期问题分析投影：显式应用 ID/名称、问题指纹版本和分类结果；继续使用完整 generation 激活。

保留历史引用、不自动改写：`t_cluster_root_cause`、`t_alert_instance` 及其他引用旧 `cluster_id` 的 RCA、Incident、知识和告警事实。需要重新挂接时另写具名审计命令。

约束变更：

- 不修改 V224；新迁移删除 `uk_cluster_active_name_key`，把名称键降为普通索引。
- 新增活动应用问题身份唯一约束；其材料由应用层生成并版本化。
- 旧列、旧索引和旧数据先保留，只有 P3/P4 用户验收后才另提清理方案。

### 6.3 API 合同

现有读 API 路径保持兼容：

- `GET /api/v1/dashboard/problem-analysis`
- `GET /api/v1/reports/cluster/{id}`
- `GET /api/v1/reports/cluster/{id}/feedback`
- 反馈详情中的 `FeedbackClusterSummaryResponse`

响应追加明确字段：`applicationId`、`applicationName`、`fingerprintVersion`；问题簇列表中的 `affectedApplications` 在硬应用边界下只能包含当前应用，暂时保留字段以兼容前端。

新增管理员应用映射 API 必须使用具名 Request/Response、Bean Validation、中文 Schema、请求 ID、版本 CAS 和幂等账本；具体路径及对象在 P3-01 批次方案中冻结。

### 6.4 外部系统

- MySQL：唯一权威源。
- Elasticsearch、Milvus：可重建候选索引，切换后重建。
- AIHub/Responses API：可选建议源；当前 HTTP 400 阻塞不影响确定性验收。
- SDK 与站端来源系统：只读身份事实，不回写来源系统。

## 7. 单表查询、批量组装和性能边界

所有新增数据访问必须为 MyBatis Mapper + Repository 单表查询：

1. 主反馈按稳定游标分页，每批 100 条。
2. source、content、SDK、station、primary fact 各按 source ID 一次批量读取。
3. alias 表按规范别名分块读取，再按 identity ID 批量加载 identity 表，在代码中组装。
4. 候选先按 `application_identity_id + problem_fingerprint_v2` 单表强召回；弱召回 ID 再批量读取 Cluster。
5. Cluster、Link、run、snapshot、projection 每张表独立读写；禁止 JOIN、跨表子查询和循环 N+1。
6. 默认 `IN` 上限 200，空集合不查库；重复键、缺失关联、乱序和超限均失败关闭。
7. 实施前记录当前 100 条调度批次和一期问题分析 API 基线；新路径 P95 不得恶化超过 20%，否则不得切换并需重新审批性能方案。

## 8. 数据迁移和切换方案

### 8.1 前向 Schema

- 只新增下一可用版本 Flyway；实施时先核对仓库和目标库真实最大版本。
- 初始迁移以新增表/可空列/索引为主，不回写活动归簇，不启动任务。
- 旧全局名称唯一约束只在新应用身份字段完成回填、影子测试证明新唯一约束可用后，使用单独前向迁移替换。
- Flyway、schema 和运维 SQL 保持普通 Git 文本；已应用迁移永不编辑。

### 8.2 应用身份影子回填

1. 只读取一期 SDK/站端安全来源的应用别名。
2. 同一渠道内完全一致的规范别名可幂等创建/复用身份；不跨渠道自动合并。
3. 生成覆盖报告：eligible 总数、已解析、未解析、歧义、各 alias 类型计数。
4. 跨渠道映射由授权管理员按脱敏样本确认；每次变更写业务审计和版本。
5. 身份处理覆盖必须达到 100%：每条 eligible 记录要么解析为唯一身份，要么形成明确的未解析/歧义终态。存在没有任何结果的记录时阻止新 generation 激活；明确终态可以进入覆盖分母，但不能形成 MAIN。

### 8.3 主问题事实和影子重分类

1. 固定 `snapshot_safe_count`，输入集合在 run 内不可漂移。
2. 为每条输入写一条主问题事实：ACTIONABLE 或明确终态。
3. ACTIONABLE 只写 run 下的候选簇/成员表；不创建正式 Cluster/Link，不覆盖旧活动 MAIN。
4. 候选结果记录旧簇到新问题身份的 `ONE_TO_ONE/SPLIT/MERGE/NEW` 映射。切换时，一对一且边界纯净的旧簇原地升级并保留 ID；拆分、合并和全新问题才创建新簇，拆分子簇记录 `split_from_cluster_id`。
5. 旧 RCA、Incident、告警和知识关联继续指向旧簇作为历史事实。一个旧簇拆成多个新簇时标记“需要授权人员重新挂接”，绝不自动选择子簇。
6. 一期投影在独立 BUILDING generation 中生成，未通过完整性门禁前不可读。

### 8.4 激活门禁

必须全部满足：

- `eligible = actionable MAIN + 所有明确非行动终态`，无遗漏、无重复。
- 每条 ACTIONABLE 恰好一个新 generation MAIN；终态记录没有业务 MAIN。
- 活动簇内 `application_identity_id` 唯一；未批准跨应用混合数为 0。
- 应用问题身份唯一键无重复；`NEW_CLUSTER` 名称碰撞复用数为 0。
- 低信息活动 MAIN 为 0；占位名、半句截断名为 0。
- 簇名覆盖代表样本，应用名/功能/症状齐全。
- 统计、列表/详情成员和一期 generation 投影覆盖一致。
- run 错误终态为 0；租约、重试和审计完整。
- 黄金样本、权限、集成、static、quick 全部通过。

### 8.5 原子切换

1. 停止普通归簇写入并确认无有效 worker 租约。
2. 锁定 reclassification run 和当前 generation。
3. 在受控事务中按候选映射升级纯旧簇、创建必要的新簇、停用旧 MAIN、写入新 MAIN，并切换一期 ACTIVE generation；切换前候选表不得被业务读取。
4. 事务提交后恢复调度器；新到反馈只走 v2。
5. 标记投影脏数据并有界重建 Elasticsearch/Milvus。
6. 记录切换操作者、请求 ID、前后 generation、覆盖计数和门禁摘要。

## 9. 回滚方案

### 9.1 切换前

- 关闭 v2 影子 worker 即可；旧 MAIN 和旧 ACTIVE generation 未变化。
- 影子 run 标记 `FAILED` 或 `ROLLED_BACK`，保留事实供审计；不得删除错误证据。
- 新增表和列保留，不执行向下迁移。

### 9.2 切换后、用户验收前

1. 立即停普通归簇和 v2 worker，确认租约释放。
2. 以原切换 request/run 为幂等键进入 `ROLLING_BACK`。
3. 在事务内停用 v2 MAIN、恢复 superseded 旧 MAIN 和旧簇状态、切回前一 ACTIVE 投影 generation。
4. 对切换后新进入的反馈单独列清单：能按 v1 安全重放的重放；不能安全重放的保持终态，禁止丢失或硬塞旧簇。
5. 重算受影响簇统计，重建派生索引，验证列表/详情/统计一致后标记 `ROLLED_BACK`。
6. 新 v2 Cluster/Link 不物理删除，改为非活动并保留 run 归属；应用身份和主问题事实保留。

### 9.3 用户验收后

- 仍保留前一 generation 和回滚事实一个经批准的观察期。
- 超过观察期后如需清理旧字段、约束或 generation，必须另提数据保留和恢复方案；本方案不授权物理删除。
- 应用代码回退只能回到仍理解新增 schema 的兼容版本；禁止回退到会重新依赖全局名称唯一语义的旧版本。

## 10. 权限、隐私、审计、事务和并发

### 10.1 权限与隐私

- 自动归簇使用统一系统审计主体，不模拟人工用户。
- 应用映射新增/修改/停用只允许管理员；未认证 401、无权限 403、越权 ID 访问拒绝。
- 技术/产品角色继续复用统一字段策略；新增应用字段不是敏感原文，但仍受现有数据范围约束。
- `primaryClause` 只能来自已脱敏 PASS 正文；审计原因、异常日志、Prompt 和索引不得包含未脱敏正文、密钥或身份明文。
- AI 只接收当前操作者/系统数据范围允许且已脱敏的候选；AI 失败关闭，不扩大候选范围。

### 10.2 审计

必须审计：应用别名映射变更、分类终态、确定性决策、AI 建议与拒绝原因、名称演化、影子 run、切换、回滚、旧簇拆分和下游重新挂接需求。

审计至少包含：request ID、actor/system actor、source/cluster/run ID、应用身份、输入摘要、规则/模型/配置版本、候选白名单引用、前后状态、结果和失败原因。

### 10.3 事务、并发和幂等

- 别名映射使用 request ID 幂等账本与 mappingVersion CAS。
- 自动归簇锁顺序固定为 run/generation -> Cluster -> source/Link；并发写入前重读活动 MAIN。
- 应用身份键、主问题 source ID、活动问题身份键和活动 source MAIN 均由数据库唯一约束兜底。
- worker 使用有界租约；过期 PROCESSING 可回收，超过重试上限形成明确失败，不永久阻塞。
- 切换与回滚只允许一个活动 run，状态机和所有权校验失败时拒绝执行。

## 11. 测试矩阵

| 层级 | 场景 | 预期 |
|---|---|---|
| 单元：身份 | 同 SDK app_id/package 别名 | 唯一解析同一身份 |
| 单元：身份 | SDK 与站端仅展示名相同 | 不自动合并 |
| 单元：身份 | 已批准跨渠道 alias | 解析同一身份并保留映射证据 |
| 单元：身份 | 无映射、重复映射 | 分别为 UNRESOLVED/AMBIGUOUS，无 MAIN |
| 单元：抽取 | 单句对象+动作+故障 | 所有画像字段来自同一子句 |
| 单元：抽取 | 两个独立问题 | `MULTI_PROBLEM_AMBIGUOUS`，不任取最早故障 |
| 单元：抽取 | 长文、噪声、短但有意义 | 完整句；低信息终态；有意义短句不误杀 |
| 单元：边界 | 不同应用、相同描述/签名 | 硬冲突，绝不关联 |
| 单元：边界 | 同应用、不同对象/动作/故障 | CONFLICT 或 UNKNOWN，不自动关联 |
| 单元：边界 | 一侧字段缺失 | UNKNOWN，不作为兼容证据 |
| 单元：决策 | `NEW_CLUSTER` 且同名簇存在 | 仍新建应用限定簇，不改为 AUTO_HIGH |
| 单元：决策 | 上下文排序高、语义不足 | 不越过自动门禁 |
| 单元：AI | 高置信越权关联/换簇 | 建议被拒绝并审计 |
| 单元：AI | 超时、HTTP 400、低置信 | 确定性结果闭合，无永久 pending |
| 属性测试 | 同一黄金集随机 20 种顺序 | 期望问题身份和归属不变 |
| Mapper | 空/重复/201 个 ID、缺关联、乱序 | 空不查；重复收敛；超限/异常失败关闭 |
| Repository | 每张表单表批量装配 | 无 JOIN、无 N+1、缺事实不猜测 |
| MySQL 集成 | 并发同源、同身份新建、同名碰撞 | 唯一 MAIN/身份键，事务无孤儿 |
| MySQL 集成 | 影子 run 中断与租约过期 | 可续跑，不影响旧活动 generation |
| MySQL 集成 | 激活门禁任一失败 | 不切换，旧读模型继续可用 |
| MySQL 集成 | 切换后回滚 | 旧 MAIN/状态/generation 恢复，v2 事实保留 |
| 数据迁移 | 旧跨应用混合簇 | 拆分为不同应用簇，历史旧簇保留 |
| 数据迁移 | 低信息、身份未知、多问题 | 无业务 MAIN，覆盖分母仍完整 |
| API 契约 | 列表、簇详情、簇内反馈、反馈详情 | 应用 ID/名称/版本字段明确且兼容旧字段 |
| 权限 | 管理员映射成功 | 幂等、CAS、审计完整 |
| 权限 | 未认证、非管理员、跨范围直接 ID | 401/403，不能旁路 |
| 安全 | 审计、日志、Prompt、索引 | 无未脱敏正文、Token、密钥和身份明文泄漏 |
| 投影 | BUILDING 不完整 / ACTIVE 完整 | 前者不可读，后者列表详情统计一致 |
| 性能 | 100 条调度批次、问题分析 API | P95 不超过批准基线 20%，无 N+1 |
| 门禁 | `dev-check --static/--quick` | 通过；跨栈合并前再运行 full |

黄金样本至少包括：

- 8 组同应用跨 SDK/站端应合并样本。
- 10 组文字相似但因应用、对象、动作、故障或签名必须分开的样本。
- 6 组低信息、乱码、通用描述和短但有意义文本。
- 6 组多子句、多问题、因果和转折文本。
- 4 组 AI 错误建议、超时和失败。
- 4 组 Top-K、时间窗、同名碰撞和处理顺序反例。

所有样本使用归簇调试模板记录 application_id、逐步输入输出、首次偏差和期望结果。测试先冻结合同，再改实现；不得通过降低断言保持旧错误行为。

## 12. 风险、停止条件和待核验事实

### 12.1 主要风险

- SDK `app_id`、包名与站端 `source_app_id` 未必天然同构，错误 alias 会造成新的跨应用污染。
- 替换 V224 全局名称唯一约束会影响并发创建和现有同名收敛，需要数据库集成证据。
- 旧簇拆分会使 RCA、告警和知识关联需要人工判断，不能自动复制。
- 保守终态会暂时降低活动 MAIN 覆盖率，但比错误归簇安全；页面需明确区分业务问题与数据质量反馈。
- 当前工作树有大量未提交跨栈改动；每批必须冻结文件并保留其他改动。
- 真实 AIHub、物理 Milvus 和生产数据状态仍待目标环境核验。

### 12.2 必须停止并重新审批

- 发现一期 SDK/站端之外的渠道也必须进入本次应用身份迁移。
- 业务要求一条反馈同时进入多个活动问题簇。
- 需要自动迁移 RCA、Incident、告警或知识结论到拆分子簇。
- 需要修改公开字段删除语义、权限角色、数据范围或人工审核流程。
- 需要删除历史数据、编辑已应用迁移、增加 JOIN/直接 SQL 或绕过 MyBatis。
- 性能无法在单表批量组装下达到批准基线，或切换无法做到 generation 级原子回滚。

### 12.3 待核验

- 目标库真实最大 Flyway 版本和 V230 以后迁移应用状态。
- SDK/站端应用别名的实际覆盖率、歧义率和跨渠道映射清单。
- 当前活动簇跨应用污染率、低信息 MAIN 数和需拆分的下游关联数。
- 新增应用身份字段是否需在一期页面直接展示；具体字段顺序需在 P3 页面实现前单独确认。
- 现有 100 条批次和一期问题分析 API 的目标环境性能基线。

## 13. 审批点和下一步

2026-09-01 用户已明确批准：

1. 不同应用默认硬隔离；SDK 与站端只有经审计 alias 才能跨渠道合并。
2. 低信息、应用未知、多问题歧义形成非业务终态，不生成活动 MAIN。
3. 进入 **P3-01：应用身份和影子数据基础**，同时要求所有代码修改严格执行全项目代码规范，不得再次产生既有代码类问题。

当前授权只覆盖 P3-01。P3-01 不改变活动归簇、不启动历史重分类、不切换页面读模型；完成用户验收后才能提交 P3-02 更新方案。

### P3-01-R1 修订批次状态

2026-09-01 用户批准仅修复首次验收的三项 P1：渠道扩展事实缺失必须整批失败关闭、并发同 requestId 在 alias/identity 锁后必须重放首个成功结果、迁移测试不得直接使用 JDBC/SQL。当前候选已完成对应实现与回归：SDK/站端扩展行集合必须与来源 ID 完全对应，真实存在但 alias 为空仍保留业务 `UNRESOLVED`；锁后通过 `FOR UPDATE` requestId 当前读区分同命令重放、异命令冲突和无幂等结果的真实版本冲突；迁移核验及测试数据清理 SQL 只存在于测试专用单表 MyBatis XML。专用 MySQL 双事务同命令竞争验证一个首次成功、一个稳定重放，alias/双 identity 版本只推进一次且测试唯一数据完整清理。

P3-01-R1 未引入 V236/V237 Schema 语义变更或新迁移。复核期间确认 V236 在专用库15:29成功应用后，工作树文件曾于15:55后漂移为 checksum `-478654793`；现已依据本地执行历史恢复为应用时的准确原文，工作树与专用库 checksum 均为 `1559985492`，并新增普通后端单测冻结该值，今后任何语义调整只能使用新的前向迁移。全过程未执行 Flyway `repair`、未改写数据库历史、未新增迁移。专用 MySQL V236/V237 全链迁移、双事务并发、定向/完整后端、static、最终隔离 quick 及前端 211 项/lint/build/包体均通过，最终独立复核无新发现。原 checksum 阻断已关闭，P3-01-R1 已重新具备用户验收条件；尚未部署、未执行影子批次，不代表目标环境或生产验证。P3-02 继续未授权。
