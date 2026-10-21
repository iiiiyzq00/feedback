# 学习机智能反馈分析与运营平台（Feedback Platform）

面向学习机业务的多渠道反馈接入、隐私治理、AI 分类与归簇、告警、RCA 和运营分析平台。本目录包含系统的**全部可运行代码**：Kotlin/Spring Boot 后端、Vue 3 管理端、Android 反馈 SDK 与宿主 Demo，以及配套的 Flyway 迁移、AI 提示词资产、验收与运维脚本和交付文档。

> 从零部署教程（MySQL、Redis、JDK、Node、前后端联通）见仓库根目录 [../README.md](../README.md)。本 README 说明代码框架、模块边界和日常开发约定。

## 技术栈

| 模块 | 技术 |
|---|---|
| 后端 | JDK 17 · Kotlin 2.0 · Spring Boot 3.3 · Spring Security · Spring Data JPA/Redis · Flyway · Gradle |
| 前端 | Node.js 18+ · Vue 3 · TypeScript · Vite · Element Plus · ECharts · Pinia · TanStack Vue Query |
| 移动端 | Android SDK · Kotlin · HMAC 签名 · 附件分块上传 |
| 基础设施 | MySQL 8（44 张物理业务表）· Redis 6+ · XXL-Job · Caffeine 本地缓存 |
| 可选能力 | Elasticsearch · Milvus（BGE-M3 向量）· 真实 LLM（OpenAI 兼容）· 邮件 · 飞书 |

## 目录结构与代码框架

Gradle 根工程 `feedback-platform.backup-20260905` 包含 `backend`、`sdk`、`demo`、`health-demo`、`child-art-demo` 五个模块：

```text
feedback-platform/
├── backend/                              # Spring Boot 后端（Kotlin）
│   └── src/
│       ├── main/kotlin/com/feedback/
│       │   ├── controller/               # REST 接口：admin / alert / auth / imports / query / report / sdk
│       │   ├── service/                  # 业务逻辑：ai / alert / cluster / imports / masking / query / report / sdk / sync
│       │   ├── repository/               # Spring Data JPA 仓储
│       │   ├── entity/                   # JPA 实体（对应受治理业务表）
│       │   ├── dto/                      # 请求/响应对象
│       │   ├── scheduler/                # 定时任务：维护、报表、版本发布
│       │   ├── security/                 # 认证、RBAC、字段级数据权限
│       │   ├── config/                   # 配置类与 SDK 配置
│       │   ├── audit/                    # 操作审计
│       │   └── worker/                   # 导入/处理 worker
│       ├── main/resources/db/migration/  # Flyway 迁移 V1 ~ V178
│       ├── test/                         # 单元测试
│       ├── integrationTest/              # 集成测试（真实 MySQL/Redis）
│       └── realIndexTest/                # 真实 ES/Milvus 索引测试
├── frontend/                             # Vue 3 管理端
│   └── src/
│       ├── views/                        # 页面：接入监控 / 渠道数据 / 公共看板 / 告警报告 / 系统管理
│       ├── api/                          # Axios 接口封装（按域拆分）
│       ├── stores/                       # Pinia 状态
│       ├── router/                       # 路由与权限守卫
│       ├── components/                   # 图表、布局、反馈详情等通用组件
│       └── query/ utils/ styles/         # 数据请求、工具、样式
├── sdk/                                  # Android 反馈 SDK（上报、HMAC、附件、隐私）
├── demo/                                 # 通用宿主接入 Demo
├── health-demo/                          # 健康/绘制类宿主 Demo
├── child-art-demo/                       # 儿童美术类宿主 Demo
├── prompts/                              # AI 技能提示词资产（分类、RCA、知识检索、告警路由等）
├── docs/
│   ├── delivery/                         # 需求第 14 节逐项交付文档（01~13 + 关键问题）
│   └── reference/                        # 技术架构、SDK 接入、指标字典等
├── scripts/                              # mock 生成、提示词发布/回滚、验收脚本
├── tools/                                # 后端安全启动器、验收与数据修复脚本
├── config/                               # 告警环境变量示例、密钥目录
├── deploy/                               # 部署模板（MDM 策略示例）
├── artifacts/ output/ data/              # 产物、报告输出、mock 数据
├── settings.gradle.kts                   # Gradle 模块定义
└── build.gradle.kts                      # 插件与版本管理
```

## 核心功能模块

1. **多渠道反馈接入**：通用SDK、Station Feedback、Jira、质量平台、红黑榜；Jira支持后端Token只读API与表格双入口，表格导入支持模板、预览、映射、幂等、重试与回滚。
2. **隐私治理与脱敏**：指纹脱敏、HanLP NER、接触信息加密、脱敏版本治理、外发隐私门禁与零泄漏验证。
3. **AI 分类与归簇**：提示词技能生命周期、规则/LLM 分类、归簇决策、AI 复核与质量验收、熔断降级。
4. **告警引擎与通知**：业务告警规则、独立测试运行、事件生命周期、升级链、站内/邮件/飞书等通知通道。
5. **AI 报告与 RCA**：六类报表、PDF 导出、RCA 编排与审核、知识采纳与过期追踪。
6. **运营看板**：总览看板、问题深度分析、四渠道趋势、SLA 与对账。
7. **系统管理**：RBAC 用户权限、脱敏策略、告警规则、动态配置、操作审计日志。

## 本地开发

- 从零部署（MySQL、Redis、JDK、Node、前后端联通）：见仓库根目录 [../README.md](../README.md)。
- 后端本地配置：复制 `backend/src/main/resources/application-local.example.yml` 为 `application-local.yml` 并替换全部占位值；数据库密码、Redis 密码、指纹/存储密钥和 SDK secret 不入库。
- Web 登录凭据是 Redis 托管的 256 位随机不透明会话 ID，仅通过 `Secure`、`HttpOnly`、`SameSite=Strict` Cookie 传递，不含用户信息且不写入 `localStorage`；Redis 只保存会话 ID 摘要并设置 TTL，故障时认证失败关闭。本地开发默认使用受信任的 HTTPS 证书，生产必须使用正式域名 CA 证书并保持 `AUTH_SESSION_COOKIE_SECURE=true`。
- Jira API默认关闭；取得真实契约后通过`JIRA_400_API_ENABLED/JIRA_400_BASE_URL/JIRA_400_ALLOWED_HOSTS/JIRA_400_API_TOKEN/JIRA_400_JQL`等环境变量启用，Token不得写入本地配置模板或Git。
- Android Demo 的六个 SDK secret 写入用户级 `~/.gradle/gradle.properties`，取值必须与后端 `app.sdk.app-secrets` 一致。
- 安全启动器（macOS Keychain）：`tools/manage-backend-local-secure.sh start|status|logs|restart|stop`，默认端口 18080，且拒绝同一项目重复启动后端。

## 业务键与幂等

表格导入按渠道业务键幂等，而不是按日期。后端使用 `source_channel + source_record_id` 作为 Jira、Quality、Redblack 和 SDK 模拟导入的受治理业务键，其哈希存入 `t_import_row.business_key_hash`。

质量平台文件的 `source_record_id` 由后端从复合字段 `客户地址 + 故障现象确认 + 商返申请日期` 生成，使用 SHA-256 前缀（`QUALITY-...`）以支持幂等而不暴露原始地址。`商返申请日期` 同时作为事件时间参与时间线、趋势、SLA 与联动分析；`实际出货日期` 作为渠道特定出货事实存储，不参与业务键。

## 验证与构建

```bash
# 从仓库根目录执行日常检查
../tools/dev-check.sh --quick

# 合并前完整本地检查（包含 SDK lint/单测和 Demo 构建）
../tools/dev-check.sh --full
```

只需核对仓库和版本事实时使用 `../tools/dev-check.sh --static`。集成测试有独立基础设施要求，说明见 `docs/delivery/12-testing.md`；不要把 `./gradlew check` 当作纯单元测试，也不要把 `BLOCKED_EXTERNAL` 写成通过。

## 文档导航

- 当前项目记忆（状态、陷阱、待办与优化）：[docs/PROJECT_MEMORY.md](docs/PROJECT_MEMORY.md)
- 交付物索引：[docs/delivery/README.md](docs/delivery/README.md)
- 技术架构（四渠道、统一模型、隐私门禁、AI 治理）：[docs/reference/technical-architecture.md](docs/reference/technical-architecture.md)
- 后端分层、全文件注释与业务类型治理重构方案：[docs/reference/backend-layer-refactoring-plan.md](docs/reference/backend-layer-refactoring-plan.md)
- 代码文件功能地图（每个源文件的职责索引）：[docs/reference/code-file-map.md](docs/reference/code-file-map.md)
- 标注集评测操作手册（API 示例与报告模板）：[docs/reference/annotation-set-guide.md](docs/reference/annotation-set-guide.md)
- SDK 宿主应用接入：[docs/reference/sdk-host-app-integration.md](docs/reference/sdk-host-app-integration.md)
- 数据库与 ER：[docs/delivery/05-database-and-er.md](docs/delivery/05-database-and-er.md)
- 本地运行与验收：[docs/delivery/02-local-run.md](docs/delivery/02-local-run.md)
- XXL-Job 多节点调度部署：[docs/deployment/xxl-job.md](docs/deployment/xxl-job.md)

## 安全提醒

- 不提交 `application-local.yml`、`.env`、私钥、数据库密码、SDK secret 或真实用户数据。
- 本地、测试、生产使用相互独立的密钥；生产必须显式配置 CJK PDF 字体、密钥托管、LLM 合规参数、告警通道与附件存储策略。
- 数据库基线以 `backend/src/main/resources/db/migration` 中的 Flyway 迁移及 `docs/delivery/05-database-and-er.md` 为准。
