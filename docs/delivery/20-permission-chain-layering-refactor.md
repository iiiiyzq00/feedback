# 权限链路分层重构交付记录

## 范围与调用链

本批次覆盖登录后的完整授权链，并回改登录链作为共享强类型消费者：

```text
HTTP / Cookie 会话
  -> WebSessionAuthenticationFilter
  -> WebUserDetailsService（账号 + 有效角色 + 权限目录一致快照）
  -> WebPrincipal<RoleCode, PermissionCode, AuthProvider>
  -> WebSecurityConfig / AccessAuthorization（URL）
  -> Service 接口 / ServiceImpl（方法级纵深校验）
  -> DataPermissionService / DataPermissionServiceImpl（数据与字段范围）

AdminManagementController
  -> AdminManagementService
  -> AdminManagementServiceImpl
  -> AuthGrantRepository / AuthDataPermissionRepository / AuthUserRepository
  -> MyBatis RepositoryImpl -> Mapper -> XML
  -> 同事务 authVersion + SystemAudit

飞书管理 / 同步入口
  -> FeishuRoleMappingService / FeishuAuthorizationPolicyService
  -> *ServiceImpl
  -> FeishuRoleMappingRepository / FeishuPolicyRepository
  -> MyBatis*RepositoryImpl -> Mapper -> XML
  -> 单用户短事务：角色 + 范围 + 启停/authVersion + 审计 + 组织事实
```

直接文件组包括：`security/WebPrincipal`、`WebUserDetailsService`、`PrincipalAccess`、
`ContentVisibility`、`AccessAuthorization`、`DataPermissionService`、`config/WebSecurityConfig`，
`controller/auth`、`controller/admin`，`service/auth`、`service/admin` 及对应 `service/impl`，
`repository/auth`、`repository/impl/auth`、`mapper/auth`、`model/po/auth`、授权相关 XML、测试与架构门禁。

## 分层结果

- `AdminManagementService`、`FeishuRoleMappingService`、`FeishuAuthorizationPolicyService`、
  `DataPermissionService` 均为无 Spring 实现注解的接口，实现统一位于 `service/impl` 并以 `ServiceImpl` 结尾。
- `AuthGrantRepository`、`AuthDataPermissionRepository`、`FeishuRoleMappingRepository`、
  `FeishuPolicyRepository` 为复杂仓储接口；MyBatis 细节位于 `repository/impl/auth`。
- Controller、Filter 不依赖实现、Mapper 或 Repository；ServiceImpl 不直连 Mapper。
- 数据权限读取以一条带 `limit + 1` 哨兵的 SQL 合并用户与角色事实；需要范围和字段策略时使用
  `DataPermissionSnapshot` 共用一次读取，不产生 N+1 或两个不一致快照。

## 裸值治理字典

| 业务集合 | 内部类型 | 稳定数据库/HTTP/Spring code |
| --- | --- | --- |
| 角色 | `RoleCode` | `ADMIN`、`OPERATOR`、`AUDITOR`、`USER`、`PHASE_ONE_*`；兼容只读 `PHASE_ONE_USER` |
| 认证来源 | `AuthProvider` | `LOCAL`、`SSO` |
| 权限码 | `PermissionCode` 值对象 + `PlatformPermissions` | 保持权限目录中的 `namespace:name` 原值，可扩展，不封闭成 enum |
| 数据范围类型 | `DataScopeType` | `CHANNEL`、`MODULE`、`REGION`、`FIELD` |
| 数据权限效果 | `DataPermissionEffect` | `ALLOW`、`DENY` |
| 数据访问级别 | `DataAccessLevel` | `READ`、`WRITE` |
| 飞书规则类型 | `FeishuRuleType` | `USER`、`USER_GROUP`、`DEPARTMENT`、`DEFAULT` |
| 飞书授权种类 | `FeishuGrantKind` | `ROLE`、`DATA_SCOPE` |
| 飞书审批状态 | `FeishuMappingDecision` | `DRAFT`、`APPROVED`、`REJECTED`、`RETIRED` |
| 飞书同步状态 | `FeishuSyncStatus` | `RUNNING`、`COMPLETED`、`PARTIAL_SUCCESS`、`FAILED` |
| 权限审计动作 | `AuthorizationAuditAction` | 保持既有/新增安全审计 operation code |

MyBatis PO/XML、HTTP DTO/响应和 Spring `GrantedAuthority` 是字符串转换边界。数据库出现未知封闭编码时
失败关闭；权限码格式非法时拒绝装配。未修改数据库 schema、历史数据、HTTP 字段、Cookie 或 RPC 协议。

## 事务与并发清单

| 场景 | 边界与不变量 |
| --- | --- |
| 主体装配/会话恢复 | `WebUserDetailsService` 短只读事务覆盖账号、角色、权限三次相关查询；角色权限批量展开 |
| 数据权限快照 | `DataPermissionServiceImpl.snapshot` 只执行一条有界 SQL，不额外开启事务；SQL 返回后在事务外同时计算行级与字段策略 |
| 替换角色权限包 | 加锁确认角色后，在一个短写事务中替换权限、批量提升所有受影响用户 authVersion、写审计 |
| 分配用户角色 | 最后管理员与飞书绑定校验、角色替换、authVersion、审计同事务 |
| 替换用户数据范围 | 删除重建、authVersion、审计同事务；修复原链路未失效旧会话的问题 |
| 用户启停 | 最后管理员保护、条件更新（SQL 内按启停变化提升版本）、审计同事务 |
| 创建本地用户 | BCrypt 在事务外；账号、角色、范围、审计在 `TransactionOperations` 短事务中共同提交 |
| 飞书映射编辑/审批 | version + 当前状态双条件 CAS 与审计同事务，冲突不写成功审计 |
| 飞书全量同步 | 飞书 HTTP 在事务外；每个用户独立短事务原子提交角色、范围、启停/authVersion、审计和事实 |
| 同步启动与重试 | 数据库命名锁由启动短事务持有并在完成回调释放；瞬态重试次数/退避有配置上限 |

单用户 authVersion 更新严格校验影响行数；角色权限包使用同事务集合式 SQL 更新全部当前有效成员，
并把数据库影响行数写入审计。飞书 HTTP、Redis、Cookie 和 BCrypt 均不进入权限写事务。

第二次事务整改后，用户角色变更统一采用“按稳定顺序锁定有效角色目录 → 锁定目标用户行”的顺序；
角色权限包更新锁定同一角色目录行，因此不会遗漏并发加入角色的用户。最后管理员计数在该共同栅栏内
通过数据库聚合完成，消除了并发写偏差和全量 ID 扫描。角色权限变化后的 authVersion 提升改为
`UPDATE ... JOIN t_auth_user_role` 集合式 SQL，不再回拉无界用户 ID 或生成巨大 `IN`。

数据权限读取每次只有一条有界 SQL，已移除额外只读事务；范围合并在查询返回后执行。飞书同步的
规则匹配、目标范围转换及组织事实 JSON 序列化均在单用户写事务外完成，写事务只保留角色/用户锁、
当前授权读取、条件变更、authVersion、审计和事实落库。角色栅栏仅用于低频授权控制面写入，
不进入普通 URL、方法级或数据权限读取热路径。

## 安全不变量与兼容性

- 最后一个有效管理员不可停用或移除 ADMIN；当前操作者不可停用自己。
- 一期技术/产品角色互斥且只能分配给已绑定、启用的飞书账号；固定权限包不可扩权。
- 新飞书账号仍只幂等授予最小 `USER` 角色；映射草稿或未审批规则不会参与运行时授权。
- 未认证、未知角色/认证来源/数据权限编码、授权事实溢出均失败关闭。
- DENY 优先；无法表达“全量减排除项”的 MODULE/REGION 组合返回空范围。
- HTTP 路径、响应键和值、数据库 code、Spring `ROLE_` authority 以及现有 RPC 均保持不变。

## 反回归门禁与验证

新增 `PermissionChainArchitectureTest`，冻结以下约束：

- 权限入口不依赖实现、Mapper 或 Repository；
- 登录/权限完整调用链不得重新出现登记过的角色、权限、认证来源、数据范围、飞书授权状态和审计结果裸值；仅 `AuthorizationTypes` 允许定义稳定编码；
- `WebPrincipal`、Repository 契约保持强类型；
- 权限写必须同时出现 authVersion 失效与安全审计；
- 数据权限查询保持有界、禁止 `SELECT *`，权限写 SQL 保持多行字段/值排版。
- 登录/权限链按 Mapper、Model/PO/Result、Repository 接口与实现、Service 接口与实现、Controller 及整个 `security` 目录递归扫描；每个命名函数必须有紧邻 KDoc，不再仅检查 Impl。
- 注解与 `fun` 必须分行；`if (...) throw/return/require/...` 和单行 `else` 执行体禁止压缩，使异常、锁和失败关闭分支可被审查。
- 分层、KDoc、BusinessScalar、事务、锁/CAS、重试、有界 SQL 和 SQL 排版属于同一验收矩阵；任一修改都必须重跑全矩阵，防止“修一项、遗漏另一项”。

最终验证（2026-09-09）：

- 权限域定向测试（security、auth、admin、Auth MyBatis/Repository、登录 Controller）通过。
- `AuthLayeringArchitectureTest`、`AuthTransactionBoundaryTest`、`PermissionChainArchitectureTest`、
  `P0RepositoryLayeringContractTest` 通过。
- 完整 `:backend:test`：1344 个测试，0 失败，44 跳过，通过。
- `:backend:bootJar`：通过。
- 完整 `:backend:architectureTest`：40 个测试中仅有两个既有附件域失败；分别为
  `AttachmentMediaPreviewPo.kt` 的单文件公开 PO 命名门禁、`SecureAttachmentRepository`
  公共方法数 25 超出既有评审上限 20。本批未修改附件域；权限域新增门禁全部通过。

后续注释及事务二次整改（2026-09-09）：登录/权限实现中的公开方法及关键私有方法已补齐紧邻中文
KDoc；`PermissionChainArchitectureTest` 新增递归方法注释、热路径无事务、统一锁顺序、集合式
authVersion 更新及事务外规则计算门禁。完整 `:backend:test` 共 1354 项、0 失败、44 跳过，
`:backend:bootJar` 通过。完整 `:backend:architectureTest` 共 42 项，仍仅有两个既有附件域失败：
`AttachmentMediaPreviewPo.kt` 单文件公开 PO 命名门禁和 `SecureAttachmentRepository` 公共方法数上限；
本批未修改附件域。

第二次收口复验（2026-09-09）：生产代码递归扫描已扩大到上述整条登录/权限链，未发现无紧邻
KDoc 的命名函数，也未发现注解与函数同行、`if (...) throw/return` 压缩写法或字典外的
登记业务裸值。登录审计动作/结果/目标和飞书组织用户状态也已纳入强类型字典，对外与数据库
code 不变。权限/登录定向测试、`PermissionChainArchitectureTest` 和 `AuthTransactionBoundaryTest`
通过，`:backend:bootJar` 通过。完整 `:backend:architectureTest` 共 43 项，仍仅上述两个附件域既有失败。
完整 `:backend:test` 共 1370 项，权限/登录相关项通过，44 项跳过；当前唯一失败为范围外
`ApplicationIdentityPersistenceContractTest` 期望 Mapper 语句数 6、实际 7，本批未修改应用身份域。
