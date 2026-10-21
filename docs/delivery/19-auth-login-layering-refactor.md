# 登录主链分层重构交付记录

## 范围

本批次覆盖本地账号登录、飞书 OAuth 登录、一次性 state/登录短码、Cookie 会话生命周期及其直接数据访问边界。
飞书组织同步、角色映射审批和管理员权限治理属于登录后的授权治理，不在本批 Service 迁移范围；其 Mapper/XML 仅做格式与注释整理，未改业务行为。

## 分层结果

```text
AuthController
  -> LocalAuthenticationService
     -> LocalAuthenticationServiceImpl
        -> AuthenticationManager
        -> LocalLoginSuccessService
           -> LocalLoginSuccessServiceImpl
              -> AuthSessionRepository / LoginAuditRepository

AuthController
  -> AuthSessionLifecycleService
     -> AuthSessionLifecycleServiceImpl
        -> AuthSessionRepository
           -> MyBatisAuthSessionRepositoryImpl

AuthController
  -> FeishuSsoService
     -> FeishuSsoServiceImpl
        -> FeishuOAuthGateway
           -> OfficialFeishuOAuthGateway
        -> FeishuSsoRepository
           -> MyBatisFeishuSsoRepositoryImpl

WebUserDetailsService
  -> AuthUserRepository / AuthGrantRepository
```

- Service 接口保留在 `service/auth`，实现迁入 `service/impl/auth`。
- 远程飞书 OAuth 调用迁入 `repository/remote/auth`，不向上层暴露 OkHttp 或 access token。
- 复杂 SSO 数据边界迁入 `repository/impl/auth`，接口不暴露 Mapper 或 PO。
- 登录审计抽取为 `LoginAuditRepository`，成功审计加入短写事务，失败/拦截审计保持单条写入。
- HTTP 请求模型迁入 `dto/auth`，飞书身份模型迁入 `model/result/auth`。
- Controller 只依赖 Service 接口，ServiceImpl 不直接依赖 Mapper。

## 事务决策

事务不以“单表/多表”判断，而以一次本地一致性阶段中的实际数据库交互判断：相关的多次查询、
多次写入或查询后写入必须共同提交；只有一条原子 SQL 且没有前置数据库判断时不额外开启事务。
远程 HTTP、Redis、Cookie 等外部 I/O 不进入数据库长事务，必要时把一个客户端请求拆成多个本地阶段。
该要求已提升为 `backend-layer-refactoring-plan.md` 第 12.1 节全局原则，后续其他模块同样执行。

保留或新增四类明确事务：

1. `WebUserDetailsService.loadUserByUsername/loadUserById`：主体装配包含三次相关查询，使用短只读事务取得一致快照。
2. `LocalLoginSuccessServiceImpl.commit`：版本条件登录时间 UPDATE 和成功审计 INSERT 共同提交。
3. `FeishuSsoServiceImpl.exchangeLoginCode`：短码锁定/消费、主体读取和最后登录时间共同提交或回滚。
4. `MyBatisFeishuSsoRepositoryImpl.completeCallback`：OAuth state 消费、身份绑定和登录短码创建必须原子完成。

以下操作不声明事务：

- 退出时 authVersion 递增：单条原子 `UPDATE`。
- OAuth state 创建：单条 `INSERT`。
- 登录失败或被限制的审计：每个阶段只有单条审计 `INSERT`；Redis 计数不伪装成本地数据库事务。
- `consumeLoginCode`：加入上层兑换事务，不重复声明事务。

原先远程换票前的 `FOR UPDATE` 读取已拆成无锁 `findUsable`；飞书 HTTP 完成后，RepositoryImpl 在本地短事务中重新 `FOR UPDATE` 并消费，避免数据库锁跨网络 I/O。

请求级复核结果：

| 请求 | 数据库边界 |
| --- | --- |
| `/auth/csrf` | 无 SQL |
| `/auth/login` | Redis 限制检查在事务外；用户/角色/权限由短只读事务装配；BCrypt 在事务外；版本条件登录时间更新和成功审计使用短写事务；Redis 重置和会话签发在提交后 |
| `/auth/me` | 过滤器内主体三次读取由只读事务覆盖；Controller 直接复用主体，不再重复查询 |
| `/auth/logout` | 过滤器主体读取使用只读事务；注销是独立、无前置数据库判断的 authVersion 原子递增，不额外开启写事务 |
| `/auth/feishu/authorize` | 一条 state INSERT，自动提交 |
| `/auth/feishu/callback` | 远程换票前无锁读取；HTTP 完成后用本地短事务完成 state 消费、身份绑定和短码创建 |
| `/auth/feishu/exchange` | 短码消费、主体读取和登录时间更新处于同一事务；返回 authVersion 后直接签发 Redis 会话，不再重复查询 |

失败登录是有意的分段边界：认证读取事务先回滚/结束，再执行 Redis 失败计数和单条失败审计 INSERT，
这样审计不会随认证异常回滚，也不会让数据库事务跨 Redis 调用。

本地登录提交使用 `id + is_active + auth_provider + authVersion` 条件 UPDATE。角色、角色权限或账号
启用状态变化都会提升 authVersion；如果 BCrypt 执行期间快照失效，登录编排最多重新认证一次，
不会使用旧权限签发会话，也不会因持续并发变更产生无界重试。

## 可读性治理

- `mapper/auth` 下 14 个 Kotlin Mapper 全部改为标准多行声明。
- `mybatis/mapper/auth` 下 14 个 XML 全部按字段、条件、动态集合和写入列分行。
- 保留并补强接口职责、实现边界、锁、CAS、敏感凭证、事务和失败策略注释。
- `AuthCoreRepositories.kt` 中原有压缩成一行的函数与参数已展开，未改变 SQL 或外部契约。

## 验证

- 登录域 Controller、Service、安全会话及 MyBatis 契约测试通过。
- `AuthLayeringArchitectureTest` 与 `AuthTransactionBoundaryTest` 通过。
- 完整 `:backend:test` 通过。
- `:backend:bootJar` 通过。
- 完整 `:backend:architectureTest` 仍有两个与本批无关的既有失败：附件 PO 文件命名门禁、`SecureAttachmentRepository` 方法数门禁；登录域新增门禁均通过。

## 外部兼容

HTTP 路径、请求/响应字段、Cookie 名称、数据库表名和既有状态编码均未改变；未新增 Flyway，也未访问或修改业务数据。
