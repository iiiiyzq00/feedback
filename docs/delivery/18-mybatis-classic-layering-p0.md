# MyBatis 经典分层重构 P0 状态

> 状态：P0 至三类经典分层基线全部清零；V2.0 Mapper/PO 目录迁移已覆盖仓库全部业务域；第七阶段 Repository 瘦身、第八阶段读模型性能治理和第九阶段运行治理代码基线已完成；第 18 节完成定义已完成本地代码与门禁收口，外部/运行证据仍按本文件 1.12 保留。核验日期：2026-09-05。
>
> 范围：仅处理无 SQL 绑定的活跃 Mapper 止血、五条生产调用链分层收口和全局回归门禁；不是 456 个 Mapper 的一次性迁移。

## 1. 本批范围与结果

本批保持 `Controller -> Service -> Repository -> Mapper -> XML`，未引入 DDD、六边形目录或新的数据库迁移。

- `FeishuAuthorizationPolicyService` 改为组合现有 `FeishuPolicyRepository`、`FeishuRoleMappingRepository`、`AuthExternalIdentityRepository`、`AuthUserRepository` 和 `AuthGrantRepository`。同步运行首错码、单用户失败标记、角色/范围替换和会话版本推进语义保留。
- `FeishuRoleMappingService` 改为使用 `FeishuRoleMappingRepository`、`AuthRoleCatalogRepository` 和 `SystemAuditMutationRepository`，草稿、乐观版本、负责人决策与安全审计语义保留。
- `SdkTelemetryService` 改为使用 `SdkTelemetryRepository`；单请求最多 100 条，仍由一个 MyBatis 多值 statement 批量写入，不退化为循环逐条写库。
- `SdkAttachmentOrphanCleaner` 改为使用现有 `FeedbackAttachmentMutationRepository`；Repository 新增有界路径集合查询，仍复用第一批补齐的 `FeedbackAttachmentMapper.findRegisteredStoragePaths` statement。文件根目录约束、数据库异常时失败关闭以及只删除未登记普通文件的语义不变。
- Jira 同步状态从 Service 包中的 `JiraSyncStateStore` 迁入 `repository/jira/JiraSyncStateRepository`，`Jira400SyncService` 不再直连 Mapper。状态 DTO 随 Repository 边界迁移，`ensure`、`claim`、`fail` 的事务范围、条件抢占后读取顺序、指数退避和安全错误摘要保持不变；旧 Store 在生产与测试调用均完成替换后删除。
- 附件对象键、存储后端、生命周期和已存储对象事实的权威定义已下沉到 `repository/attachment`；Service 存储端口保留兼容类型别名，Repository 不再接收或返回 Service 包类型。门禁同时补强为扫描全限定类型引用，避免通过不写 import 绕过反向依赖检查。
- 应用身份的来源渠道、Alias 键/输入、身份快照和来源输入等共享契约已下沉到 `repository/applicationidentity`；Service 保留兼容别名。Repository 内部完整性异常在 Worker 边界翻译为 Service 异常，Controller 不再直接捕获 Repository 类型。
- AI 管线领取结果 `ClaimedAiTask` 的权威定义已下沉到 `repository/ai`；Service 保留兼容类型别名，任务关闭、`SKIP LOCKED` 候选领取、持有者复读确认和重试语义未改变。
- 自动脱敏恢复聚合结果 `MaskingClusterReconciliation` 的权威定义已下沉到 `repository/masking`；Service 保留兼容类型别名，安全记录、技术失败、已归簇和待归簇计数口径未改变。
- `AuthController` 的成功登录时间更新和退出会话失效已收口到窄边界 `AuthSessionLifecycleService`，Controller 不再依赖 `AuthUserRepository`；认证、登录保护、Redis 不透明会话签发和安全审计保持明确的调用顺序。
- Feishu 全量组织同步增加 Repository 层单实例运行门禁：短事务内使用 MySQL 命名锁串行化“检查活动运行、回收超时运行、创建新运行”，锁保持到事务完成后释放；远程 Feishu 请求仍在事务外。新鲜 `RUNNING` 返回稳定的 `FEISHU_SYNC_ALREADY_RUNNING`/HTTP 409，默认 6 小时后的遗留运行才允许标记 `FAILED/FEISHU_SYNC_STALE` 后接管，时限可配置为 30～1440 分钟。历史若存在多条遗留 `RUNNING`，必须全部超时才会逐条关闭，不能只检查最新一条。
- Feishu 身份列表读取、单用户短事务、失败事实和最终运行状态增加最多 1～5 次的有限数据库瞬态重试，默认 3 次、25ms 线性退避；同一用户远程组织请求不随数据库重试重复调用。非瞬态错误仍按原语义立即计入单用户失败，启动事务本身不因未知提交结果盲目重放。
- 告警/通知域复核确认供应商 HTTP 调用位于事务外，`NotificationOutbox.complete` 只包裹本地 attempt、outbox 终态及死信审计的短事务。Repository 现在对 `DELIVERED`、可重试 `FAILED` 和 `DEAD_LETTER` 三类条件更新统一要求恰好命中一行；并发或陈旧领取导致的零行更新会抛出稳定状态冲突，使先写入的 attempt 随事务回滚。死信终态未成立时不会追加死信审计，审计插入失败同样回滚终态。
- 供应商异步回执链按通知行增加悲观串行化，锁只覆盖本地回执事务，不包含外部调用。首次到达但事件时间较旧的回执仍写入追加审计，随后按 `event_time,id` 判定为非最新并停止状态变更；重复回执继续由既有 `provider:callbackId` 幂等键忽略。失败回执只有在自身为最新且通知状态条件更新实际命中时才推进责任链，避免乱序失败覆盖较新成功事实，或已终结通知重复升级。
- 责任链超时扫描不再依赖同类方法自调用的失效 `@Transactional`，改为每个候选一笔 `TransactionOperations` 短事务。扫描结果携带内部链 ID、展示 ID、责任人序号和渠道序号，推进 SQL 再以 `RUNNING + target_order + channel_sequence + 已到期 deadline` 做 CAS；扫描后发生确认、失败推进或新截止时间时，旧候选不会误跳过新渠道。责任人耗尽推进和新截止时间写入也补齐当前状态条件，CAS 失败不再创建下一条通知；非运行链在构造事件前即返回，避免无效读取。
- 责任链回执确认继续以链 `RUNNING -> ACKNOWLEDGED` 的条件更新决定唯一赢家；赢家随后写责任人确认和具体通知确认，两笔 Repository 写入现在都要求命中正确记录类型，否则异常向外传播并由既有事务回滚链状态。历史重复确认仍返回 `false`，不会重复更新下属事实；引用的目标记录缺失只在本次确实取得确认权时失败关闭，不改变重复回执兼容语义。目标确认允许已失败渠道的合法晚到确认，但不能误写其他记录类型。
- 告警人工确认与内部取消不再丢弃责任链 CAS 行数。Service 先辨别是否存在运行中责任链：P2/P3 等本来没有责任链的告警保持合法 no-op；已读取到运行链却更新零行则作为并发状态冲突回滚。`ALERT_ACK` 既有审计新增 `escalationChainAcknowledged`，明确记录本次是否实际确认责任链。供应商送达、接通和失败状态也统一返回是否实际应用，本地回执响应新增 `notificationStateApplied`，重复或不兼容终态不再伪装成已改状态。
- 当前责任人进入处理中、运行链严重度、关联 Incident 严重度与耗尽原因、批量告警实例严重度、自动禁用审计均补齐影响行数校验；部分批量更新、缺失 Incident、陈旧责任人或审计写入失败会抛出并回滚当前事务。责任链通知读取同时限定 `record_type='NOTIFICATION'`，避免内部链/责任人行被误当成通知事实。
- 归簇统计全量维护移除无效的事务自调用假设，外部 `recompute/recomputeOne` 仍各自有事务，全量 `recomputeAll` 和定时漂移修复则通过 `TransactionOperations` 按最多 200 个 Cluster 一笔短事务执行；400 个 Cluster 的失败注入夹具已证明形成两笔事务，不再把全表 keyset 扫描包进一个外层事务。重分类回滚的 Cluster 生命周期快照必须整批恢复，部分命中立即失败；重分类失败状态写入也必须命中当前运行记录，租约丢失或记录缺失时不能继续追加“失败已记录”审计。
- 报表生成任务的报告关联、失败状态和幂等占位新增严格单行校验；关联冲突时不完成任务，租约丢失时不伪报失败已记录，幂等占位零行时不继续生成。复核确认 `REQUIRES_NEW` 状态方法位于独立 `ReportTaskLifecycle` Bean，事务代理有效，没有按错误的“自调用”假设重复拆分。
- 报表取消竞态与管理员硬删除现在都按最多 500 条通知一批先清理投递尝试，再要求通知删除数与扫描事实数完全一致；并发新增、漏删或条件漂移会在报告删除和任务终态更新前失败，并由既有事务回滚。支持任务数量允许为零或多条，未施加错误的单行假设。
- 定时报表运行的成功、跳锁和失败三类终态只允许从 `RUNNING` 条件更新且必须命中一行，陈旧执行者不能覆盖既有终态。调度器只捕获报告生成异常并记录失败；终态记录自身的冲突直接向上抛出，不再被二次改写成生成失败。
- 删除经全仓引用核对后确认无生产/测试消费者、且已有完整替代链路的 `FeishuAuthorizationPolicyMapper`、`FeishuRoleMappingMapper`、`SystemAuditMapper`。门禁首轮还发现同型无引用旧接口 `AuthSessionMapper`、`FeishuSsoMapper`，一并删除。
- 门禁首轮发现仍被 `SdkAttachmentOrphanCleaner` 使用的 `FeedbackAttachmentMapper.findRegisteredStoragePaths` 缺少 XML statement；该方法不能删除，已在现有附件 Mapper XML 中补齐有界候选集合的单表读取。

本批没有修改或新增 Flyway，没有执行 SQL，没有访问业务库，也没有部署。

### 1.1 Mapper 目录迁移阶段：auth

- `AuthCorePersistence.kt` 与 `AuthDataPermissionPersistence.kt` 已移除；14 个认证 Mapper 分别迁移到 `com.feedback.mapper.auth` 的独立 Kotlin 文件，`SystemAuditMutationMapper` 迁移到 `com.feedback.mapper.audit`，实现一个 Mapper 接口一个文件。
- auth 持久化对象迁移到 `com.feedback.model.po.auth`，系统审计写入对象迁移到 `com.feedback.model.po.audit`；Repository、Service、安全组件及测试引用同步更新，不保留旧 `com.feedback.persistence.auth` 兼容别名。
- 14 个 auth XML 的 namespace、resultType 与 parameterType 同步指向新包；历史文件名偏差 `AuthGrantMapper.xml` 更名为 `AuthUserRoleMapper.xml`。SQL statement 内容、事务和业务接口未改变。
- 全局 Mapper 发现范围从仅扫描 `com.feedback.persistence` 扩展为同时扫描 `com.feedback.mapper`，防止新包方法绕过 statement 门禁；auth 域新增 Kotlin 文件名、XML 文件名、namespace 一一对应门禁，后续新增偏差立即失败。

### 1.2 Mapper 目录迁移阶段：audit

- `BusinessAuditPersistence.kt` 与旧 `PhaseOneAttachmentAuditMapper.kt` 已移除；业务审计锁、审计链、HTTP 审计队列和一期附件审计 4 个 Mapper 分别迁入 `com.feedback.mapper.audit` 独立文件，与已迁入的系统审计写 Mapper 合计 5 个一接口一文件。
- 业务审计持久化对象迁入 `com.feedback.model.po.audit`，Service、Repository 和契约测试引用同步更新；不保留 `com.feedback.persistence.audit` 兼容类型。
- 4 个既有 audit XML 的 namespace 和 resultType 同步新包；连同 `SystemAuditMutationMapper.xml`，audit 域 Kotlin 文件名、XML 文件名、namespace 全部一一对应。SQL statement 内容未改变。
- 迁移域布局门禁由 auth 扩展至 auth、audit，未迁移域继续保持过渡兼容，避免一次性移动全部 Mapper。

### 1.3 Mapper 目录迁移阶段：sdk 与 PO 文件规范

- 原 `SdkMaterializationMappers.kt` 的 9 个 Mapper 已分别迁入 `com.feedback.mapper.sdk` 独立文件；9 个 XML 同步迁至 `mybatis/mapper/sdk`，namespace 与 resultType 指向新 Mapper/PO 包，SQL statement 内容未改变。
- 原 `SdkMaterializationPersistence.kt` 的 9 个持久化对象已迁入 `com.feedback.model.po.sdk`，每个 PO 独立 Kotlin 文件；生产、测试、MyBatis 契约和测试资源路径全部同步，不保留 `com.feedback.persistence.sdkmaterialization` 兼容类型。
- PO 采用经典 MyBatis 项目的窄职责规范：按业务域分包、每个公开 PO 一个同名文件，仅承载持久化字段；不继承通用基类、不复用为 HTTP DTO、不加入业务行为。复杂联表结果与查询条件后续分别进入 `model.result`、`model.query`，不冒充单表 PO。
- auth 原 `AuthCorePo.kt` 中 14 个 PO/读取投影已同步拆成同名独立文件，audit 原 `BusinessAuditPo.kt` 中 4 个 PO 也完成同样拆分；字段、类型、默认值与 XML 映射保持不变。
- 架构门禁将 Mapper Kotlin/XML/namespace 一一对应范围扩展到 auth、audit、sdk，并新增三域 PO 文件名与单声明约束。

### 1.4 Mapper 目录迁移阶段：attachment

- `AttachmentGrantMapper` 与 `FeedbackAttachmentMapper` 已从 `com.feedback.persistence.attachment` 迁入 `com.feedback.mapper.attachment`，各自保持一个 Mapper 一个同名 Kotlin 文件。
- 授权写入、附件列表、完整附件、安全扫描候选、对象迁移候选和本机副本清理候选共 6 个 PO 已迁入 `com.feedback.model.po.attachment` 独立文件；原 Mapper/PO 混放及多 PO 聚合文件已移除。
- 既有 `mybatis/mapper/attachment` 资源目录保持不动，仅同步 2 个 XML namespace 和全部 resultType；statement、SQL、锁、CAS、排序、结果上限及业务事务均未改变。
- Repository、附件安全流、对象存储迁移工具、SDK/Jira/导入调用方以及生产、测试和集成测试引用已同步；不保留旧 `com.feedback.persistence.attachment` 兼容类型。
- Mapper/PO 文件一一对应门禁从 auth、audit、sdk 扩展至 attachment。

### 1.5 Mapper 目录迁移阶段：jira

- Jira 同步状态、附件拉取任务和来源查找 3 个 Mapper 已从 `com.feedback.persistence.jira` 迁入 `com.feedback.mapper.jira`；负责人初始化 6 个 Mapper 从独立旧包 `com.feedback.persistence.jiraowner` 收口到 `com.feedback.mapper.jira.owner`。9 个 Mapper 均为一接口一文件。
- 附件任务、任务汇总、同步状态及负责人来源/经办人/工作流/分配/显示名匹配/活动用户/写入命令共 10 个 PO 已分别迁入 `com.feedback.model.po.jira` 和 `com.feedback.model.po.jira.owner`，每个 PO 独立文件。
- 主 Jira 的 3 个 XML 保持 `mybatis/mapper/jira` 路径；负责人子域的 6 个 XML 从 `mybatis/mapper/jiraowner` 迁入 `mybatis/mapper/jira/owner`。全部 namespace、resultType、测试资源路径和 statement 断言已同步。
- Repository 包与服务调用边界保持不变；附件队列租约、同步水位、负责人初始化锁与批量写入 SQL、事务和业务接口均未改变，不保留旧 Jira persistence 类型。
- Mapper/PO 文件一一对应门禁扩展至包含 `jira/owner` 子目录的完整 Jira 域。

### 1.6 Mapper 目录迁移阶段：config

- 原 `SystemConfigPersistence.kt` 中的 `SystemConfigMapper` 已迁入 `com.feedback.mapper.config` 独立文件；系统配置、动态目录、版本计数、脱敏证据和批量写入参数 5 个 PO 已迁入 `com.feedback.model.po.config`，每个 PO 独立文件。
- 既有 `mybatis/mapper/config/SystemConfigMapper.xml` 路径保持不动，仅同步 namespace 和全部 resultType；配置目录锁、历史分页、批量停用/写入和脱敏证据 SQL 均未改变。
- 根 Repository 与 `repository.config` 中的两个既有调用边界只更新 Mapper/PO import，未合并或改名，避免结构迁移改变现有职责。
- 生产、测试及 statement 断言已同步，不保留旧 `com.feedback.persistence.config` 类型；Mapper/PO 一一对应门禁扩展至 config。

### 1.7 Mapper 目录迁移阶段：imports

- 原 `com.feedback.persistence.imports` 下 8 个 Mapper/PO 聚合文件已移除；36 个导入核心、分片上传、治理、质量、对账、回滚及统一质量 Mapper 分别迁入 `com.feedback.mapper.imports`，每个 Mapper 一个同名 Kotlin 文件。
- 34 个 imports 专用持久化对象已迁入 `com.feedback.model.po.imports`，每个 PO 一个同名 Kotlin 文件；原本直接映射 `ImportBatch`、`ImportRow`、`ImportTemplate`、`ImportAttachmentTask` 和渠道实体的 Mapper 继续使用既有实体类型，未扩大本批模型范围。
- `mybatis/mapper/imports` 目录保持不动，36 个 XML 仅同步 namespace 与 PO resultType；statement、SQL、锁、批量边界、事务和业务接口均未改变。
- Repository、Service 和测试引用已同步，不保留 `com.feedback.persistence.imports` 类型；imports 契约继续验证 36 个 XML 可解析且每个 statement 保持既有单表边界，Mapper/PO 一一对应门禁扩展至 imports。

### 1.8 Mapper 目录迁移阶段：剩余业务域

- 按既定顺序完成 station、masking、cluster、phaseone、alert、report、scheduler，并依据实际业务包继续完成 ai、applicationidentity、core、knowledge、normalization、query、sync。
- 本阶段共迁移 375 个 Mapper/375 个 XML、395 个 PO 和 4 个查询条件对象：station 13/16、masking 24/15、cluster 17/31、phaseone 58/62、alert 141/157、report 40/38、scheduler 13/5、ai 13/22、applicationidentity 6/9、core 6/1、knowledge 9/7、normalization 6/6、query 23/19、sync 6/7；斜线前后分别为 Mapper 与 PO 数量。
- Station 的 legacy/online 与 phaseone 的 analysis/detail/diagnostics/listprojection 保留业务子域层次；查询条件进入 `model.query`，不冒充 PO。cluster 历史聚合 XML 文件名已按既有 namespace 标准化为 `ClusterSourceTableMapper.xml`。
- `SixReportFactsRepository` 是原持久化包中混放的真实 Repository 类；结构迁移批次先保持其包与职责，仅抽离同文件的 Mapper、PO 与查询对象，第七阶段再迁入 `repository.report`。旧 `persistence` 最终不再保留生产 Kotlin 类型。
- 结构迁移完成时共有 451 个 Mapper Kotlin 文件与 451 个 XML 一一对应、479 个 PO 同名独立文件和 4 个 `model.query` 查询对象；第八阶段随后将一期访问校验的两个单表 Mapper/PO 合并为一个受控读侧 Query Mapper/Result，当前为 450 个 Mapper/450 个 XML、477 个 PO 和 1 个 `model.result`，一一对应规则保持成立。

### 1.9 第七阶段：Repository 瘦身

- 全量按单个 Repository 类审计构造器参数、Mapper 注入和公开方法，不再用“单文件 Mapper 引用数”误判包含多个 Repository 的聚合文件。
- `FeedbackListProjectionRepository` 的 25 个 Mapper 拆为 generation 状态、投影存储、来源事实、流程辅助、导入辅助、归簇辅助、五渠道辅助和应用字典 8 个窄 Repository；具体边界最多注入 5 个 Mapper，原 Service API、200 条批量上限、锁/CAS 和事实组合顺序保持不变。
- `FeedbackDetailRepository` 的 18 个 Mapper 拆为核心事实、流程/审计、归簇、渠道和导航 5 个窄 Repository；具体边界最多注入 7 个 Mapper，原详情、时间轴、导航、外链和审计接口保持不变。
- `SensitiveRetentionRepository` 的 10 个 Mapper 拆为 SDK、Station、导入敏感数据和附件 4 个留存边界；500 条分批、活动告警保护、上传会话先删分片再过期以及清理顺序保持不变。
- `ReportTaskRepository` 的 9 个 Mapper 拆为任务生命周期、报告产物清理和执行人权限 3 个边界；通知投递尝试、通知、支持任务、报告和任务终态的既有删除顺序及单行校验保持不变。
- `SixReportFactsRepository` 从 `com.feedback.persistence.report/SixReportFactsPersistence.kt` 迁入 `com.feedback.repository.report/SixReportFactsRepository.kt`；Service 与测试 import 同步，事实 Mapper、SQL 和报告组装不变。`com.feedback.persistence` 下最终无生产 Kotlin 文件。
- 保留的高指标对象均经职责复核：`AlertMetricRepository`、`SixReportFactsRepository`、`ClusterReportRepository` 是有界读侧事实聚合；`InAppNotificationRepository` 是统一收件箱读取/已读回执边界；`UnifiedQualityRepository` 是跨渠道质量快照边界；`EntityClosureRepository` 是原子关闭事实/写入边界；其他公开方法较多的对象是稳定生命周期 API 或本批新增兼容门面。未为了数字机械拆散同一查询或事务。

### 1.10 第八阶段：读模型性能治理

- 五个重点对象已按入口、输入上限、固定查询数、随批次变化查询数和循环内调用完成代码审计；查询预算由 MockK 定向测试执行，不以方法名扫描代替运行事实。
- 一期访问校验原先串行读取 `t_feedback_source`、`t_feedback_content` 两次，现使用 `PhaseOneFeedbackAccessMapper` 的受控读侧 `INNER JOIN` 一次返回同一访问事实；旧两个 Access Mapper/XML/PO 已在零引用后移除。该查询仍只按唯一 source ID 返回一行，权限、生产环境、脱敏和删除态判断保持在 Guard 中。
- `ProductFeedbackRequestService` 在线列表固定为 count、分页、批量用户名 3 次读取，单页上限 100；在线详情固定为请求、事件、批量用户名 3 次读取。只读详情改用无锁 `findById`，状态更新继续使用 `lockById ... FOR UPDATE`，写侧并发语义不变。
- `MetricGovernanceRepository` 对账继续以 500 个 source ID 为一批，每个有安全数据的批次最多执行 source、content、AI、cluster 4 类事实查询；无 PASS 数据时不查询 AI/cluster。指标定义使用 501 哨兵读取并在达到哨兵时失败关闭，对外最多允许 500 条。
- 列表投影属于默认关闭的 backfill/后台 dirty 同步，不是在线列表逐请求拼装。每批 source ID 上限保持 200，来源、正文及所有辅助事实均批量读取，渠道只查询本批实际出现的渠道，空批次不执行 Mapper SQL；完整 generation 验证中的应用字典由每个 keyset 批次重复读取改为同一事务只读取一次。投影替换、checksum、fence、锁/CAS 和安全资格顺序不变。
- 分发补偿属于后台调度而非在线读模型；未知回执和到期补偿两个扫描入口均硬限制为 1～20 条，随后保持逐项独立事务与外部回执查询，避免把远程调用和整批补偿错误合并成长事务。
- 详情主体仍按独立安全、渠道、附件、流程、归簇和审计事实组合；相邻导航以 200 条 keyset 候选配一次批量正文读取，已有稀疏权限测试证明不会逐行查询。由于继续分页直到找到可见邻居是现有页面语义，本阶段未增加会截断合法邻居的总页数上限，也未将敏感/审计事实揉入超大聚合 SQL。
- 本阶段不引入缓存、索引或运行时监控，不修改 Flyway、不访问业务库；真实 MySQL `EXPLAIN ANALYZE`、慢查询和目标数据量容量基线仍属于需要隔离测试库或目标环境证据的发布门禁，不能由单元契约替代。

### 1.11 第九阶段：运行治理代码基线

- 新增全局 MyBatis Executor 插件，按 Mapper statement ID、SQL command 和成功/失败记录 `mybatis.statement.duration`；Timer 自带调用次数，另以 `mybatis.statement.rows` 记录查询返回行数或写入影响行数。statement ID 来自编译期 Mapper namespace/method，指标不包含 SQL、参数、返回内容、用户或 request ID。
- SQL 异常只按 MySQL/JDBC 稳定错误码归类为 `deadlock`、`lock_timeout`、`sql_error` 或 `other`，写入 `mybatis.statement.failures`；异常本身原样传播，不吞掉事务回滚语义。失败日志与超过默认 500ms 的慢 statement 日志只记录 statement ID、command、耗时、分类/行数和既有 MDC request ID，不输出 SQL、参数或业务数据。
- 新增 Repository AOP 计时 `repository.method.duration`，标签只包含 Repository 类、方法和 outcome；参数、返回值及请求标识不进入指标。嵌套 Repository 边界分别计时，用于定位编排门面与具体数据边界，不把多层耗时相加冒充端到端耗时。
- 复用 Spring Boot Actuator 对 HikariCP 的自动绑定，不重复实现连接池采集；部署后应使用 `hikaricp.connections.acquire`、`hikaricp.connections.pending/active/idle` 等实际暴露指标核验连接等待和池状态。
- MyBatis 可观测性默认开启，可由 `MYBATIS_OBSERVABILITY_ENABLED=false` 关闭，慢 statement 阈值由 `MYBATIS_SLOW_STATEMENT_THRESHOLD_MS` 配置。全局 SQL timeout 不在本批直接启用；仅在显式设置 `app.mybatis.observability.default-statement-timeout-seconds` 时应用 1～300 秒的 MyBatis 默认 timeout，未配置时保持现有 JDBC/MyBatis 行为，便于后续灰度。
- 定向测试验证成功查询的次数/耗时/行数、死锁分类、关闭开关、Repository 成功/失败计时，以及 SQL timeout 的未配置保持和显式配置两种契约。完整后端、架构、bootJar、static/quick 与前端回归通过。
- 本批未启动应用、未连接业务库、未执行 SQL、未部署。Prometheus 端点的真实新增序列、Hikari 指标名、真实慢查询、锁等待/死锁样本和 timeout 灰度仍须在隔离测试库或目标环境核验；代码测试通过不能替代这些运行证据。

### 1.12 第 18 节完成定义收口审计

本轮不虚构原方案之外的“第十阶段”，而是按第 18 节 14 条完成定义逐项核对。当前本地代码与自动门禁已经直接证明：活跃 Mapper 无缺失 statement；全部生产 Mapper 位于 `com.feedback.mapper` 且以 `Mapper` 结尾；全部 `@Repository` 组件以 `Repository` 结尾；Controller、Service、Repository 的禁止依赖基线为零；一个 Mapper 一个同名 Kotlin 文件；450 个 Mapper Kotlin、450 个 XML 与 namespace 一一对应；477 个专用 PO 均按域一文件一声明；`architectureTest` 已接入 Gradle `check`。

- `MyBatisBindingGateTest` 新增生产 Mapper 根包/后缀及旧 `persistence` Kotlin 源为零的全局检查；`RepositoryComplexityGateTest` 新增全部 Repository 组件后缀检查。两项均从编译后的生产类发现目标，不依赖人工类名枚举。
- 删除 8 个 Service 中迁移后遗留且未使用的 `com.feedback.mapper...*` 通配 import；生产 Service 当前没有 Mapper、`JdbcTemplate`、`SqlSession` 或 `EntityManager` 引用，修改只清理 import，不改变调用、事务或业务语义。
- 旧 `com.feedback.persistence` 只剩普通测试文件的历史 package 名；生产目录没有 Kotlin 文件，生产、普通测试、集成测试、架构测试及 Mapper XML 中旧 persistence Mapper/PO 全限定引用均为零。测试 package 名不代表生产类型或数据访问实现仍位于旧目录。
- SQL 写侧/读侧规则已由全局安全门禁、既有域契约和第八阶段五个重点读模型预算覆盖，但尚未形成对 450 个 Mapper 的全量“写侧单表/在线集合必有界”语义分类证明，因此第 18 节第 9、10 条不扩大表述为全仓关闭。
- 普通 `backend:test` 与现有业务契约已通过；本轮遵守“不访问业务库、不执行 SQL”，没有运行需要真实 MySQL/Testcontainers 的完整 `integrationTest`，因此第 12 条仍需隔离 MySQL 环境证据。
- 第九阶段已提供 statement 耗时、调用次数、行数、失败分类、Repository 耗时和 Actuator HikariCP 接入代码；Prometheus/Hikari 实际序列、真实慢查询、锁等待/死锁及 timeout 灰度仍需目标环境证明，所以第 13 条当前只判定代码基线完成。
- 全部域已经按包、文件与门禁独立维护，但本工作树按约束不提交；没有逐域 commit/标签，不能证明“每个业务域可以独立回滚”，第 14 条待用户后续按提交策略形成恢复点。

最终本地回归：三类 Kotlin 编译、`architectureTest`、完整 `backend:test`、`backend:bootJar`、`dev-check --static`、`dev-check --quick` 全部通过；quick 包含前端 48 文件 216 项、lint、TypeScript、生产构建和包体预算。最终文档更新后另执行 `git diff --check`。本结论不代表部署或生产验收。

最终收口再次直接执行 `:backend:check`，确认 Gradle 总检查链成功，包含 19 项架构测试和当前 `integrationTest` 的 8 项无数据库测试，均为 0 skipped、0 failure、0 error。P0 专用 MySQL 开关和 URL 均未配置，Docker CLI 不可用，因此这 8 项不能冒充第 12 条要求的真实 MySQL 集成证据。

| 第 18 节条目 | 最终状态 | 可验证边界 |
|---|---|---|
| 1. 生产链路无无绑定 Mapper | 本地完成 | Mapper 方法与 XML/注解 SQL 双向门禁通过 |
| 2. Mapper 全部位于 `com.feedback.mapper` | 本地完成 | 编译类全量发现门禁通过，旧生产 persistence Kotlin 为 0 |
| 3. Mapper 后缀统一 | 本地完成 | 编译类全量后缀门禁通过 |
| 4. Repository 后缀统一 | 本地完成 | 全部生产 `@Repository` 组件门禁通过 |
| 5. Service 只经 Repository 访问数据库 | 本地完成 | Mapper、JDBC、SqlSession、EntityManager 引用为 0 |
| 6. Repository 不依赖 Service/Controller | 本地完成 | 全限定类型扫描门禁通过 |
| 7. 一个 Mapper 一个文件 | 本地完成 | 450 个 Mapper 文件全域门禁通过 |
| 8. Kotlin/XML/namespace 一一对应 | 本地完成 | 450/450，双向 statement 与文件名门禁通过 |
| 9. 写侧/读侧规则分离 | 部分完成 | 重点域契约已覆盖；尚无 450 Mapper 全量语义分类证据 |
| 10. 在线集合全部有界 | 部分完成 | 五个重点读模型已有预算；尚无全部在线入口的全量证明 |
| 11. 架构测试接入 `check` | 本地完成 | `:backend:check` 已实际执行并通过 19 项架构测试 |
| 12. MySQL 集成和业务契约通过 | 部分完成 | 普通契约与当前 8 项无数据库 integration 通过；专用 MySQL 未启用 |
| 13. 关键查询监控 | 代码完成、运行待验 | 全局 statement/Repository/Hikari 代码具备；目标指标序列与故障样本待验 |
| 14. 每域独立维护和回滚 | 维护完成、回滚点待建 | 域包和门禁已独立；当前禁止提交，尚无逐域 commit/tag |

至此，V2.0 九个实施阶段和所有可在当前授权、当前环境内完成的本地收口工作结束。表中四类“部分完成/运行待验/回滚点待建”是发布与版本管理验收项，不通过修改业务代码或弱化门禁强行消除。

## 2. 自动化门禁

新增独立 `architectureTest` 源集并接入 Gradle `check`，使架构门禁不受当前普通测试源码存量编译债影响而失去执行能力。

### 2.1 MyBatis 绑定门禁

扫描全部生产 Mapper 类与 `src/main/resources/mybatis/mapper/**/*.xml`：

1. XML namespace 必须唯一。
2. 每个 namespace 必须解析为带 `@Mapper` 的接口。
3. 每个生产 Mapper 声明方法必须具有同 namespace、同方法名的 XML statement，或使用 MyBatis SQL/Provider 注解。
4. Mapper 从编译类发现；全部业务域强制一个 Kotlin 文件一个 Mapper。
5. 全部业务域及其子目录的 PO 强制每个 Kotlin 文件恰好声明一个同名 `*Po`，防止重新形成聚合文件。
6. 每个 XML statement 必须反向解析到同 namespace Mapper 的真实方法，防止删除或改名方法后遗留孤儿 SQL。
7. Mapper 方法的返回类型及嵌套泛型不允许使用通用 `Map`/`MutableMap`。
8. Mapper XML 禁止 `SELECT *`（包括别名星号投影）和未列入白名单的 `${...}` 原样替换；当前未设置任何 `${...}` 白名单。
9. 每个生产 Mapper 必须位于 `com.feedback.mapper` 且使用 `Mapper` 后缀；生产旧 `com.feedback.persistence` 目录不得重新出现 Kotlin 源。

该门禁在删除旧接口前实际检出了本批三类无绑定 Mapper，以及额外两个无引用旧接口和一个活跃附件方法漏绑，证明不是只针对固定文件名的伪门禁。

### 2.2 经典分层门禁

以下新增违规立即失败：Controller 直连 Repository/Mapper、Service 直连 MyBatis Mapper、Repository 反向依赖 Service 类型。已处理批次涉及的生产调用链均从违规集合清零。

Controller 同时禁止依赖 `com.feedback.model.po`；import、别名 import 与全限定类型引用都会被扫描，当前基线为零。

存量基线固定到具体文件和具体依赖，过期日为 2026-10-31；债务减少时也必须同步缩小基线，禁止用宽目录、数量阈值或正则豁免吞掉新增问题。当前剩余：

- Controller 直连数据层：0 项，基线已删除；import 与全限定 Repository/Mapper 引用任一新增均立即失败。
- Service 包直连 MyBatis Mapper：0 项，基线已删除；任何新增直连立即失败。
- Repository 反向依赖 Service 类型：0 项，基线已删除；import 与全限定引用任一新增均立即失败。
- Controller 依赖 PO：0 项，基线已删除；任何新增直接依赖立即失败。

### 2.3 Mapper 扫描入口

- Web 主应用通过独立 `MyBatisConfig` 显式使用 `@MapperScan(basePackages = ["com.feedback.mapper"], annotationClass = Mapper::class)`，统一扫描迁移后的 Mapper 根包且只注册带 `@Mapper` 的接口；配置与 `FeedbackApplication` 分离，避免 `@WebMvcTest` 等无 MyBatis 测试切片强制实例化 Mapper。
- 附件对象迁移命令继续保留 `attachment-migration` Profile 下的窄扫描；该独立入口不加载 Web 主应用配置，扫描边界未扩大。

### 2.4 Repository 复杂度门禁

- 新增 `RepositoryComplexityGateTest`，从编译类反射计算每个 `@Repository` 的构造器参数、直接 Mapper 参数和公开方法数。
- 默认上限分别为 8、8、15；超过任一上限必须进入按全限定类名登记的审查清单，并同时冻结三项最大值和非空职责理由。
- 新增高指标 Repository、现有对象继续膨胀或已拆对象仍残留在清单都会失败；债务减少时必须同步删除或收紧审查项。

## 3. 验证结果

- 第七阶段前五项低风险收口定向验证：`compileKotlin`、`compileTestKotlin`、`compileArchitectureTestKotlin` 与 `architectureTest` 通过；架构测试现为 16 项（MyBatis 绑定 7、经典分层 4、P0 Repository 契约 5）。新增门禁证明 XML 孤儿 statement、Controller → PO、Mapper → 通用 Map、`${...}` 均为 0；4 处别名星号投影已按原返回字段等价展开为显式列清单。SQL 条件、窗口排序、结果上限和返回类型未改变。
- 完整 `backend:test` 与 `backend:bootJar` 通过；首次把 `@MapperScan` 直接放在主启动类时，3 个无 MyBatis 的 Web 测试切片因扫描出 Mapper 却没有 `SqlSessionFactory` 而失败，扫描随后迁入独立 `MyBatisConfig`，3 个受影响测试类定向复验及完整后端回归均通过。
- `../tools/dev-check.sh --static` 与 `../tools/dev-check.sh --quick` 通过；quick 包含前端 lint、48 文件 216 项测试、TypeScript、生产构建和包体预算。
- 第七阶段定向验证覆盖详情/导航、列表投影同步与 generation、报告任务以及敏感留存；最终 17 项 `architectureTest`、完整 `backend:test` 和 `backend:bootJar` 通过。

- `./gradlew :backend:architectureTest --no-daemon --console=plain`：通过；第二批新增附件路径委托与 Jira 抢占顺序契约，当前共 10 项测试，包含 5 项全局门禁和 5 项分层行为契约。
- `:backend:compileKotlin`：由上述任务执行并通过。
- `./gradlew :backend:compileTestKotlin --no-daemon --console=plain`：通过。原阻塞确认为共享快照中的测试 API 漂移，已按当前统一异常、Feishu Store、Jira Import Port/Repository 和拆分后的 SDK 服务更新测试，没有恢复旧生产类型。
- SDK、Jira、Feishu、登录保护与查询预览定向回归：通过，46 项。完整回归还发现 Jira 附件批任务的 Map 生产者使用 snake_case、消费者误读 camelCase；已统一为既有 snake_case 契约，并补验隔离重试行为。
- `../tools/dev-check.sh --static`：最终通过。首轮曾因记忆未记录当前 V238 和迁移治理元数据中的 63 表口径失败；补记“代码 V238、63 表治理口径与专用库 73 表实测存在已知差异”后，仓库卫生与项目一致性均通过。
- 附件存储与应用身份定向回归：通过，86 项；附件对象发布单独复验 4 项通过。macOS `/var -> /private/var` 父目录别名改为以真实路径做白名单判断、以数据库规范化词法路径继续访问和审计，符号链接末级仍显式拒绝。
- AI 管线、自动脱敏恢复及隐私队列契约定向回归：通过；`architectureTest` 同步验证 Repository → Service 实际集合为空。
- `../tools/dev-check.sh --quick`：最终通过。后端完整单测通过；前端 lint、48 个文件 216 项测试、TypeScript、生产构建和包体预算全部通过。首轮发现的 12 个后端运行期失败及未使用 `logSource` 已按所属契约处理，不再作为遗留阻塞。
- 下一批新增并扩展 `P0MyBatisMysqlContractTest`，仅在 `P0_MYBATIS_MYSQL_ENABLED=true` 时连接显式配置且库名必须精确为 `feedback_platform_test` 的专用库。2026-09-05 本机真实 MySQL 5 项通过：除 `markFailure`、同步首错码和 SDK 批量遥测外，还验证两个真实事务竞争启动时命名锁保持到首事务完成、第二个事务稳定冲突，以及新鲜运行拒绝和超时运行接管。测试不执行 `Flyway clean`，不连接业务库。
- `FeishuAuthorizationPolicyServiceTest` 新增 3 项：单用户数据库瞬态失败重试时远程组织请求只调用一次；身份列表瞬态失败耗尽后运行标记失败并重抛；活动同步冲突发生在读取身份列表前。Auth MyBatis 契约和全局绑定/分层门禁同步覆盖新增 Mapper 方法。
- 告警/通知定向契约新增 6 项：校验完成入口保留事务注解、终态冲突向上抛出、成功/失败零行更新拒绝、死信状态竞争失败时不写审计，以及审计失败向事务边界传播；连同既有 outbox 用例共 10 项通过。外部通知未实际发送。
- 供应商回执新增 5 项定向契约：锁定通知的 Mapper/Repository 委托、审计事实按供应商事件时间排序、乱序失败只审计不改状态、状态条件更新未命中不推进升级链。回执 Service、Repository 与 Alert MyBatis 三个测试类共 8 项通过。
- 责任链超时推进新增 6 项定向契约：每候选短事务、超时 CAS 失败不入队、成功后才继续编排、责任人推进竞争、目标失败写入顺序及 deadline 状态冲突；连同既有责任链和 Alert MyBatis 用例，本批定向共 13 项通过。
- 责任链确认事实新增 7 项定向契约：链/责任人/通知写入顺序、重复确认幂等、目标引用缺失回滚传播、事务注解、两类 Repository 零行更新失败关闭及 XML 记录类型约束；责任链 Service、Repository 和 Alert MyBatis 定向现共 20 项通过。
- 告警/通知后续状态审计的域内全套定向回归通过：新增运行链确认竞争、无责任链兼容 no-op、确认/取消结果、供应商状态应用结果、当前责任人推进、批量严重度部分更新、Incident 联动及自动禁用审计失败注入；Mapper 契约锁定通知读取和确认/取消的记录类型、状态条件。
- 归簇/重分类定向回归通过：新增双页 400 个 Cluster 的两笔短事务证据、协调器无外层 `@Transactional` 契约、生命周期快照部分恢复拒绝和失败状态租约冲突拒绝；现有归簇治理与重分类 Service/Repository 用例同时通过。
- 报表任务与调度定向回归通过：新增报告关联、失败状态、幂等占位、取消/硬删除通知清理数量冲突，以及定时运行三类陈旧终态失败注入；MyBatis 契约锁定租约、关联空值保护和全部调度终态的 `RUNNING` 条件。
- 全部业务域 Mapper/PO 结构迁移定向回归通过：迁移完成时 `architectureTest` 验证 451 个 Mapper 的 statement 完整性、全部业务域及子目录的 Kotlin/XML/namespace 一一对应和 479 个 PO 一文件一声明；第八阶段受控合并后对应当前 450 个 Mapper/450 个 XML、477 个 PO 和 1 个读侧 Result，架构门禁继续通过。
- 第八阶段定向预算测试覆盖一期访问单查询、产品反馈列表/详情固定读取与无锁详情、指标每批查询数及空安全集合短路、指标定义哨兵、列表投影字典单次读取和补偿扫描上限；完整 `backend:test`、`backend:bootJar`、`architectureTest`、`dev-check --static/--quick` 与 `git diff --check` 通过。quick 继续包含前端 lint、48 文件 216 项、TypeScript、生产构建和包体预算。
- 本轮最终 `architectureTest`、完整 `backend:test`、`dev-check --static`、`dev-check --quick` 和 `backend:bootJar` 均通过；quick 继续包含前端 lint、48 文件 216 项测试、TypeScript、生产构建与包体预算。只构建了可启动制品，因未取得一次性空库/专用测试库启动凭据，按隔离方案未启动 Spring、未打开登录后页面。
- `backend:test` 在该真实 MySQL 开关启用时禁用 up-to-date 复用，避免把此前 JUnit 条件跳过的结果误报为已执行。首轮契约曾尝试清理 3 条已提交的合成 SDK 遥测，数据库 `AUDIT_LOG_APPEND_ONLY` 触发器按设计拒绝；这 3 条带随机 `p0-sdk-*` 目标键的记录保留在专用测试库，最终合同改为事务内验证后回滚，不再产生追加审计残留。

## 4. 剩余批次与风险

1. Controller → 数据层、Service → Mapper、Repository → Service 三类经典分层存量基线均已清零；后续任何新增违规由门禁直接失败，不再维护存量豁免。
2. Mapper/PO 结构迁移已覆盖仓库实际全部业务域；后续仅处理验证发现的结构回归，不再保留未迁移域兼容口径。
3. 本批 P0 新增 statement、基础事务回滚、通知终态条件更新和 Feishu 同步启动竞争已覆盖；目标环境连接池/隔离级别、真实飞书长批次耗时、真实通知供应商回执和部署回归仍是独立发布门禁，不能由本机契约替代。

## 5. 合并前隔离启动与页面冒烟方案

本轮只准备方案和可启动制品，不连接业务库、不部署、不发送真实通知。执行启动前必须由操作者提供专用 `feedback_platform_test` 或新建的一次性空库凭据，并再次核对 JDBC URL 的数据库名不属于业务库；没有该前提时只构建 `bootJar`，不启动 Spring。

1. 后端使用随机空闲端口和显式测试库 URL 启动，设置 `XXL_JOB_ENABLED=false`、`APP_CLUSTER_RECLASSIFICATION_WORKER_ENABLED=false`、`FEEDBACK_RPC_ENABLED=false`、`MILVUS_ENABLED=false`、`FEISHU_SSO_ENABLED=false`、`FEISHU_ORGANIZATION_SYNC_ENABLED=false`，并保持邮件 provider 为 `disabled`；所有 Feishu、短信、电话、邮件收件人与 webhook/token 环境变量必须为空。Flyway 只允许对一次性空库前向 migrate，`clean` 继续禁用。
2. 只检查 `GET /actuator/health` 为 `UP`、启动日志不存在 Mapper statement/namespace 绑定错误、应用关闭后端口释放；不调用 SDK/Station 接入、通知分发、供应商回执或任何会写业务事实的接口。
3. 前端以隔离后端为代理启动后，用全新浏览器会话检查 `/login` 首屏无 console error；具备专用合成测试账号时再检查 `/channel/all`、`/dashboard/analysis`、`/alert/center`、`/notifications`、`/admin/alerts` 的路由渲染、空态/合成数据态、权限拒绝和 API 错误提示。没有隔离账号时停在登录页，不伪造“登录后页面通过”。
4. 冒烟证据只记录端口、制品 SHA、Git 状态、测试库逻辑名、健康响应、页面 URL 与 console/network 结论；不得记录密码、token、真实收件人、业务数据或数据库连接串中的秘密。

当前未做目标环境部署或回归，也未以真实飞书目录执行长批次、进程强杀恢复和目标连接池故障注入。新增 `markFailure`、同步完成首错码、SDK 批量遥测以及 Feishu 启动互斥已同时由静态门禁、单元契约和本机专用 MySQL 契约覆盖，但不能据此宣称生产就绪。
