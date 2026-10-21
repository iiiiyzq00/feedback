# 交付物 02：README 与本地运行说明

## 1. 前置条件

- JDK 17；仓库自带 Gradle Wrapper。
- MySQL 8.x，字符集 `utf8mb4`、排序规则 `utf8mb4_0900_as_cs`。
- Redis 6+。
- Node.js 18+ 与 npm。
- Elasticsearch、Milvus、真实 LLM 均为可选；缺失时使用受控降级配置。
- 构建 Android Demo 需要 Android SDK。

## 2. 数据库与配置

```sql
CREATE DATABASE feedback_platform_local_deploy CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;
CREATE USER 'feedback'@'localhost' IDENTIFIED BY '<local-password>';
GRANT ALL ON feedback_platform_local_deploy.* TO 'feedback'@'localhost';
```

复制 `backend/src/main/resources/application-local.example.yml` 为忽略提交的 `application-local.yml`，填入本地数据库密码和相互独立的随机密钥。设置一次性管理员环境变量：

```bash
export AUTH_BOOTSTRAP_USERNAME=admin
export AUTH_BOOTSTRAP_PASSWORD='<at-least-12-characters>'
```

## 3. 启动

通用开发方式：

```bash
export DB_USERNAME=feedback DB_PASSWORD='<local-password>'
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379
# Redis 启用密码时再设置：export REDIS_PASSWORD='<local-redis-password>'
export AUTH_SESSION_TTL_HOURS=8
export FEEDBACK_PUBLIC_SCHEME=https
export AUTH_SESSION_COOKIE_SECURE=true
export MASKING_FINGERPRINT_SECRET='<independent-32+-random-characters>'
export CONTACT_STORAGE_SECRET='<independent-32+-random-characters>'
export IMPORT_RAW_STORAGE_SECRET='<independent-32+-random-characters>'
./gradlew :backend:bootRun
```

本机浏览器登录默认通过 HTTPS：

```bash
mkcert -install
mkdir -p .runtime/tls
mkcert -cert-file .runtime/tls/feedback-platform-local.pem \
  -key-file .runtime/tls/feedback-platform-local-key.pem \
  192.168.3.140 localhost 127.0.0.1 ::1 YangdeMacBook-Pro.local
nginx -c "$PWD/deploy/nginx/local-https-nginx.conf"
```

证书私钥位于已忽略的 `.runtime/tls/`，不得提交或复制到生产。访问地址为
`https://192.168.3.140:8088`；`http://192.168.3.140:8089` 仅提供 308 跳转。
飞书开放平台必须登记完全一致的 HTTPS 回调地址：
`https://192.168.3.140:8088/api/v1/auth/feishu/callback`。生产环境应换成正式域名和公共/企业 CA 证书。

Flyway 随后端启动自动执行；健康检查：`http://localhost:18080/actuator/health`。

macOS 安全凭据启动方式为 `tools/manage-backend-local-secure.sh start`，默认端口 18080；使用 `status`、`logs`、`stop` 管理，具体密钥名见脚本。

前端：

```bash
cd frontend
npm install
npm run dev
```

默认页面为 `http://localhost:5173`。前端开发代理需要指向实际后端端口。

## 4. Demo 与测试

```bash
./gradlew :demo:assembleDebug :health-demo:assembleDebug :child-art-demo:assembleDebug
./gradlew :backend:test :sdk:testDebugUnitTest
cd frontend && npm test && npm run build
```

生产密钥、真实 LLM、邮件、飞书和 Milvus 未配置时，不应伪造成功；相应功能明确降级或失败关闭。

## 5. 首次验收检查

1. 健康接口返回正常，Flyway 无待执行或校验失败迁移。
2. 登录后可打开四个渠道页、统一查询页、隐私工作台和接入看板。
   工作区页面默认只对飞书（SSO）来源开放；本机没有真实飞书租户时，需按第 8 节打开
   `app.security.local-workspace.enabled`，否则口令账号进入渠道页会得到 403（这是设计好的失败关闭）。
3. 导入一份模拟文件，批次满足 `总行数 = 新增 + 更新 + 跳过 + 失败`。
4. 普通账号无法查询非 PASS 正文；AI、报告和导出也不包含该记录。
5. 数据库表数保持 42；不要通过 Hibernate 自动建表补齐环境。

可在 MySQL 当前 schema 中执行：

```sql
SELECT COUNT(*) AS table_count
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';
```

## 6. 常见故障

| 现象 | 检查与处理 |
|---|---|
| 后端启动时数据库失败 | 检查数据库名、账号、字符集、端口和环境变量；不要提交本地密码 |
| Flyway 校验失败 | 确认使用当前仓库和正确数据库；不要手工修改已执行迁移或清空业务库 |
| 前端 401/接口不通 | 检查 bootstrap 用户、JWT 密钥、开发代理和实际后端端口 |
| SDK 签名失败 | 检查 app key/secret 是否成对、设备时间、nonce 和后端应用配置 |
| 表格预览失败 | 检查扩展名、工作表、必填映射、日期/枚举格式和文件大小 |
| AI/向量不可用 | 查看降级状态；普通接入、查询和 SQL 统计应继续可用 |
| secure launcher 拒绝第二实例 | 使用 `status`/`stop` 管理现有实例，避免多个调度器操作同一数据库 |

## 7. 停止与清理

- 普通 `bootRun`、Vite：在各自终端正常停止进程。
- 安全启动：`tools/manage-backend-local-secure.sh stop`。
- 演示数据清理由有权限的产品回滚/作废能力处理；不要对工作区或数据库执行未经确认的递归删除。
- 构建缓存不是交付必需品，需要释放空间时使用各构建工具的标准 clean；不要删除源码、迁移或历史验收证据。

## 8. 仅限本机的显式开关（默认全部关闭）

下面这些键**默认都是关闭**，只在没有真实外部依赖的本机演示环境里显式打开；
它们不改变生产语义，关闭时的行为就是失败关闭（403 / 404 / 401 / 503），不是降级或静默兜底。
密钥一律从 600 权限的文件读入进程 environment，不写进配置模板、仓库或命令行参数。

| 键 | 打开后的作用 | 关闭时 |
|---|---|---|
| `app.phase-one.list-projection.read.enabled` | 渠道列表读 ACTIVE 投影 | 503 `FEEDBACK_FEEDBACK_LIST_READ_DISABLED` |
| `app.phase-one.problem-analysis-projection.read.enabled` | 问题深度分析读投影 | 503 `FEEDBACK_READ_UNAVAILABLE` |
| `app.station-feedback.gateway.auth.enabled` + `...auth.secret` + `...auth.adapter.enabled` | 站点网关 `POST /v1/feedback[/new]` 做 HMAC 认证（adapter 模式跳过外部 UC 换票） | 401 |
| `app.security.local-workspace.enabled` | 持有 `access:phase_one_workspace` 的**口令（LOCAL）账号**也能读工作区页面 | 403（工作区只认 SSO 来源） |
| `app.auth.registration.enabled` | 登录页开放 `POST /api/v1/auth/register` 自助注册 | 404 `AUTH_REGISTRATION_DISABLED` |

要点：

- 工作区放宽只放开「来源必须是 SSO」这一条，**权限判定不变**：注册出来的账号只有 `USER` 角色、
  没有 `access:phase_one_workspace`，即使开关打开也读不到渠道数据。该开关状态会随
  `/api/v1/auth/me` 的 `workspaceAccessRelaxed` 下发给前端，避免前端另一套 SSO 判断与其漂移。
- 自助注册不接受客户端指定的角色或租户：一律 `USER` + 默认租户，口令长度 12~128，
  账号名 3~50 位 `[A-Za-z0-9_.-]`，成功与否都写 `t_system_audit_log`。
- 站点网关写入是产生业务事实的正规入口：`t_feedback_source.data_environment` 取列默认值
  `PRODUCTION`，因此不需要改动任何夹具标记就能满足读开关的数据前提。

### 8.1 启动期单动作入口（每次启动只做一件事）

投影 generation 的受控回填与 dirty 队列排空由两个**默认为关**的启动期入口承担，
打开后应用启动即执行一次，任何一步失败都以异常终止启动（不吞异常、不自动重试）：

| 键 | 作用 |
|---|---|
| `app.phase-one.list-projection.backfill.enabled` + `...action/generation-key/actor-id/batch-size/max-batches` | 单人操作一次 generation 状态机（CREATE / REFRESH / VERIFY / ACTIVATE） |
| `app.phase-one.list-projection.drain.enabled` + `...drain.max-batches` | 按补偿轨排空 dirty 队列（与 XXL 补偿任务同一个 worker，仅少了调度器） |

dirty 队列的**可靠恢复口径始终是数据库 dirty 行**，不是提交后的即时事件。
2026-10-11 之前这里有一个实测缺陷：`FeedbackListProjectionMutationCoordinator.claimDirty/refreshIncremental`
是默认传播（REQUIRED），被 `@TransactionalEventListener(AFTER_COMMIT)` 调用时会加入那个
「已提交但仍绑定」的写入事务（它们自己不提交，行锁要等连接被回收才释放），而同类的
`acknowledgeDirty/deferDirty/retryDirty/failDirty` 是 `REQUIRES_NEW`，会另起连接去等这些锁直到
`innodb_lock_wait_timeout`。后果是**每次写入请求的响应要等约 100 秒**，且并发写入在 generation 行上串行，
dirty 行被留在 `PROCESSING` 且租约过期。已修：这两处注解改为 `REQUIRES_NEW`，
让「领取事务保持短小」「投影刷新不持有 dirty 行锁」这两个既有设计在提交后事件里也成立
（`FeedbackListProjectionSynchronizer.refreshIncremental` 是 `MANDATORY`，因此协调器必须自开事务）。
实测：同一条写入请求的响应从约 100 秒降到 1.1 秒 / 0.12 秒，dirty 表在请求返回时已为空，
新行在 0.5 秒内出现在 ACTIVE 投影里。

drain 入口仍然保留，用途是**没有 XXL 调度中心时人工排空积压行**
（例如开关曾经关闭、进程中途退出、或修复前留下的过期租约行），跑完核对该表为空、
`t_phase_one_feedback_list_projection` 的 ACTIVE generation 行数正确。
