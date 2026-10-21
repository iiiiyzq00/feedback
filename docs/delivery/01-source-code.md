# 交付物 01：完整项目源代码

## 1. 交付范围

本仓库交付可构建的后端、Web 管理端、Android 通用 SDK、三个宿主应用、数据库迁移、AI Prompt、模拟数据、测试与运维脚本。源码不包含真实业务数据、生产密钥或可直接访问生产环境的配置。

| 目录 | 技术与职责 | 主要产物 |
|---|---|---|
| `backend/` | Kotlin、Spring Boot；接入、脱敏、查询、AI、报告、告警、权限和任务 | 可执行后端与测试报告 |
| `frontend/` | Vue 3、TypeScript；管理后台与工作台 | `dist/` 静态资源 |
| `sdk/` | Android 通用反馈 SDK；签名、队列、重试、分片和进度查询 | AAR/测试报告 |
| `demo/` | SDK 通用集成样例 | Debug APK |
| `health-demo/`、`child-art-demo/` | 两个业务 Demo；演示应用、系统、设备及可选环境字段 | Debug APK |
| `prompts/` | 版本化 Prompt、结构化输出约束和安全评估样例 | Prompt manifest |
| `data/test-fixtures/` | 四渠道模拟数据、脱敏与归簇验收集 | 可重复测试输入 |
| `tools/`、`scripts/` | 启停、生成数据、回放、验收和治理脚本 | 命令行工具 |
| `docs/` | 最终交付、当前参考和历史验收材料 | Markdown 文档 |

## 2. 模块边界

- 四个来源由独立接入适配器处理，统一写入来源元数据和标准模型。
- 脱敏是接入、AI/索引出站以及查询输出的共同门禁；普通链路只允许 `masking_status=PASS`。
- MySQL 是权威数据源，搜索、向量和 AI 均为可重建或可降级能力。
- 表格来源只读、不回写；只有 SDK 反馈可更新平台内部处理状态。
- 数据库结构由现有Flyway迁移管理，当前V178交付基线保持**44张物理业务表**；已执行迁移禁止修改，只能新增前向迁移。

## 3. 配置和秘密边界

可提交的示例配置位于 `backend/src/main/resources/application-local.example.yml`。数据库密码、JWT、脱敏指纹、联系人存储、导入原文存储和 SDK 签名分别使用独立秘密，通过环境变量或被 Git 忽略的本地配置注入。生产私钥必须位于仓库之外。

提交前至少检查：

```bash
git status --short
git grep -nE '(BEGIN (RSA |EC )?PRIVATE KEY|password[=:].+|secret[=:].+)' -- ':!*.example.*' ':!docs/**'
```

命中项必须人工判断，不能仅依赖自动扫描。

## 4. 构建与完整性检查

```bash
./gradlew :backend:test :sdk:testDebugUnitTest
./gradlew :demo:assembleDebug :health-demo:assembleDebug :child-art-demo:assembleDebug
cd frontend && npm test && npm run build
```

交付验收应能从干净检出重新生成构建产物，不依赖仓库中的 `build/`、`dist/`、`node_modules/` 或本机缓存。具体环境、启动和故障排查见 [交付物 02](02-local-run.md)，测试矩阵见 [交付物 12](12-testing.md)。
