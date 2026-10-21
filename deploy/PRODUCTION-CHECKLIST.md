# 生产部署清单（K8s / 裸机）

面向 feedback-platform 的生产上线检查表。所有配置键均来自
`backend/src/main/resources/application.yml` 与本目录 compose 文件，
逐项核对后再放量。

## 1. 构建产物

- [ ] 后端：`./gradlew :backend:bootJar` → `backend/build/libs/feedback-platform-backend.jar`（JDK 17）
- [ ] 镜像：`deploy/backend.Dockerfile`，基础镜像 `eclipse-temurin:17-jre-jammy`（glibc 2.35）。
      **不得换成 alpine 或 glibc < 2.32 的底座**，否则 DJL tokenizers native 加载失败，
      BGE-M3 向量退化为无语义 hashing。
- [ ] 前端：`npm run build` → `frontend/dist`，由 nginx 静态托管（参考
      `deploy/nginx/frontend-cache.conf`），`/api` 反代到 backend:18080。
- [ ] BGE-M3 模型：`model.onnx + tokenizer.json` 以只读卷挂到 `/models/bge-m3`（约 2GB，不进镜像）。
- [ ] DJL native 缓存卷挂 `/root/.djl.ai`（避免每实例重复下载）。
- [ ] 中文字体：`REPORT_PDF_FONT` 指向容器内可用字体文件，或挂载字体目录；
      缺失时 `ReportPdfService` 启动校验直接失败。

## 2. 必需环境变量（缺失即启动失败或功能降级）

| 类别 | 键 | 要求 |
| --- | --- | --- |
| 数据库 | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 指向生产 MySQL 8.0，库 `feedback_platform_*` |
| Redis | `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | application.yml 无占位符，靠宽松绑定覆盖 |
| ES | `SPRING_ELASTICSEARCH_URIS` | `http(s)://es:9200`，生产开 xpack 时加账号 |
| 脱敏/存储密钥 | `MASKING_FINGERPRINT_SECRET`、`CONTACT_STORAGE_SECRET`、`IMPORT_RAW_STORAGE_SECRET` | 各 ≥32 随机字符，启动强校验 |
| SDK 密钥 | `COURSE/QUESTION_BANK/WRONG_BOOK/AI_TUTOR/APP_STORE/HEALTH_DEMO/CHILD_ART_DEMO/UNIVERSAL_FEEDBACK_SDK_SECRET` | 各 ≥32 随机字符；SDK 接入方一对一分发 |
| 首次管理员 | `AUTH_BOOTSTRAP_USERNAME` / `AUTH_BOOTSTRAP_PASSWORD` | 密码 ≥12 位；仅首库需要，`AdminBootstrap` 幂等 |
| 向量 | `EMBEDDING_PROVIDER=onnx-bge-m3`、`BGE_M3_PATH=/models/bge-m3` | 缺省退化为 hashing，语义搜索失效 |
| 功能开关 | `MQ_ENABLED`+`MQ_NAMESRV_ADDR`、`XXL_JOB_ENABLED`+`XXL_JOB_ADMIN_ADDRESSES`、`FEEDBACK_RPC_ENABLED`、`MILVUS_ENABLED` | 按环境显式开/关，不要依赖默认值 |
| 会话 | `AUTH_SESSION_COOKIE_SECURE=true` | 生产必须 true（默认即 true），配合全站 HTTPS |
| 外链 | `APP_PUBLIC_BASE_URL` | 报告/通知里的对外回跳地址 |

## 3. 密钥管理

- [ ] 密钥只走 K8s Secret（`envFrom: secretRef`）或 KMS，**不进** argv、镜像层、git 仓库。
- [ ] `deploy/.dev-secrets` 仅限本机开发（已 gitignore，600 权限），禁止复制到生产。
- [ ] 轮换注意：`MASKING_FINGERPRINT_SECRET` 更换后历史脱敏指纹全部对不上；
      用 `*_KEY_ID` 版本化（如 `identity-hmac-v1`）并保留旧密钥至数据重刷完成。
- [ ] bootstrap 管理员创建后，轮换 `AUTH_BOOTSTRAP_PASSWORD` 或删除该账号，改用正式账号体系。

## 4. 中间件 HA

- [ ] MySQL 8.0：主从或 InnoDB Cluster；`character-set-server=utf8mb4`、`collation-server=utf8mb4_0900_ai_ci`。
- [ ] Redis：Sentinel 或 Cluster（承载 Web 会话 + 登录限流计数器，挂了=全员掉线）。
- [ ] Elasticsearch 8.18：≥3 节点；生产开启 xpack 安全；`feedback-v1` 索引至少 1 副本（应用不自建索引，首个文档写入时自动创建）。
- [ ] RocketMQ 5.x（启用 MQ 时）：namesrv ≥2 节点、broker 主从；**broker 不得暴露公网**
      （application.yml 明示：无鉴权，安全边界=网络隔离）。
- [ ] Zookeeper：仅 `FEEDBACK_RPC_ENABLED=true` 时需要。
- [ ] Milvus：仅 `MILVUS_ENABLED=true` 时需要。

## 5. 网络 / TLS

- [ ] 入口 nginx（或 Ingress）终结 TLS：静态前端 + `/api` → backend:18080。
- [ ] 全站 HTTPS；会话 Cookie 已默认 `Secure; HttpOnly; SameSite=Strict`，勿放宽。
- [ ] `/actuator/**` 已有 ADMIN 角色约束，仍建议网络层再限制为内网可达。
- [ ] MySQL/Redis/ES/RocketMQ 端口只对应用网段开放，不绑 0.0.0.0。

## 6. 健康检查与监控

- [ ] 探针：`/actuator/health`（liveness + readiness 同用即可，无外部依赖探活分离需求）。
- [ ] Prometheus：`/actuator/prometheus` 已默认暴露，接入抓取；Grafana 看 JVM/连接池/慢请求。
- [ ] 慢 SQL：默认阈值 500ms（`MYBATIS_SLOW_STATEMENT_THRESHOLD_MS`），日志告警。
- [ ] GC：JDK 17 默认 G1；如需观测加 `-Xlog:gc*`（容器 JAVA_OPTS），避免 G1 Full GC。
- [ ] 堆：容器内存 limit ≥ JVM `-Xmx` + 512MB 余量；BGE-M3 推理另需约 1GB 堆外。

## 7. 数据与备份

- [ ] MySQL：每日全量 + binlog 增量；**发布前**手动备份（Flyway 只前滚，不回滚）。
- [ ] ES：`feedback-v1` 配置 snapshot 仓库定期快照。
- [ ] Redis：开 AOF；会话丢失可接受（用户重新登录），限流计数器可重建。
- [ ] Flyway：`validate-on-migrate=true` 已默认；checksum 冲突时禁止 `repair` 冲账，
      按迁移治理流程处理。

## 8. 发布流程

- [ ] 滚动更新：先扩后缩；Pod/实例间共享 Redis 会话，无粘滞需求。
- [ ] 数据库迁移与应用同包发布（Flyway 启动即迁）；先小流量实例验证迁移通过再全量。
- [ ] 回滚：应用镜像可回滚；schema 不回滚——新列/新表须向后兼容一个版本以上。
- [ ] 首次发布核对：`flyway_schema_history` 全绿、`t_auth_user` 存在 bootstrap 管理员、
      登录 → `/api/v1/auth/me` 返回 200 且 `roles` 含 ADMIN。

## 9. K8s 落地要点

- [ ] Deployment：多副本 + `envFrom: secretRef`；probes 指 `/actuator/health`。
- [ ] 模型卷：只读 PVC（或 hostPath/CSI 只读）挂 `/models/bge-m3`；DJL 缓存用独立 PVC。
- [ ] resources：backend requests/limits 按第 6 节堆估算；ES 单独 StatefulSet。
- [ ] 硬化（可选）：当前容器以 root 运行（DJL 缓存路径 `/root/.djl.ai`）；
      如需非 root，同步改 DJL 缓存挂载路径与镜像内目录属主。

## 10. 裸机落地要点

- [ ] OS glibc ≥ 2.32（Ubuntu 22.04+ / Debian 12+）；Ubuntu 20.04（glibc 2.31）
      无法加载 DJL native，必须走容器。
- [ ] JDK 17（`JAVA_HOME`），systemd unit 管理 jar 进程，`EnvironmentFile=` 加载密钥（600 权限）。
- [ ] nginx 托管 `frontend/dist` 并反代 `/api`；日志目录独立挂载 + logrotate。
