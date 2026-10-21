# 交付物 06：SDK 与核心 API

SDK 初始化、反馈入口、附件、离线重试和进度查询见 `../reference/sdk-host-app-integration.md`。所有 SDK 写入和状态查询使用 HMAC-SHA256、时间窗和 nonce；receipt 还必须通过应用归属校验。

## 1. 通用约定

- Web API 前缀为 `/api/v1`，采用 Redis 不透明会话 Cookie；SDK API 采用每应用独立 HMAC。
- JSON 响应统一为 `ApiResponse<T>`；下载接口返回 CSV、XLSX、PDF 或附件流。
- 时间使用 ISO-8601 输入，数据库统一记录毫秒级时间并按 Asia/Shanghai 展示。
- 普通读取必须满足角色、数据范围和 `masking_status=PASS`。
- 分页参数从 1 开始，列表大小由接口限制；批量写入和导出均有数量上限。

## 2. SDK API

| 方法 | 路径 | 请求要点 | 响应/安全 |
|---|---|---|---|
| POST | `/api/v1/sdk/feedback` | `schema_version`、加密正文信封、可选加密联系方式、附件清单 | 仅接受受支持契约版本；返回 `client_feedback_id`、`receipt_id`、`ENCRYPTED_ACCEPTED` |
| GET | `/api/v1/sdk/feedback/{receipt_id}/status` | 空请求体，同样参与 HMAC | 只返回阶段、进度和时间；校验 receipt 所属应用 |
| POST | `/api/v1/sdk/ingest/{receipt_id}/attachments` | metadata + 加密文件 | 校验应用归属、ID、MIME、大小和 SHA-256 |
| POST | `/api/v1/sdk/ingest/{receipt_id}/attachments/chunks` | 分片序号、总片数、分片哈希 | 独立幂等和断点续传 |
| POST | `/api/v1/sdk/telemetry` | 安全诊断事件数组 | 不接受任意原始日志正文 |
| GET | `/api/v1/sdk/config` | `package_name` | 动态表单、录屏和附件限制 |

V186起，通用SDK主体信封在脱敏成功后不再立即删除。`COMPLETED`只表示主体已安全处理并可使用脱敏结果，`encrypted_data_key + payload_nonce + payload_ciphertext + payload_tag`继续保留到`retention_until`，再由统一维护任务原子清理；联系人传输密文在联系方式已指纹化并按授权独立AES-GCM存储后仍立即销毁，不能借主体复核权限绕过联系人专门权限。进入`REVIEW_NEEDED`时，主体密文保留期至少延长到90天，未完成人工闭环的任务不得由普通到期任务清理。明文和解封装后的data key仍只存在于最小内存作用域并及时清零，禁止持久化明文。

V209起，安全处理阶段把手机号、邮箱、用户ID和设备序列号按类型独立加密写入`t_feedback_identity`，并把应用日志、帐号上下文、私有文件清单组成独立JSON诊断包后以AES-GCM写入`t_channel_sdk`的诊断密文字段；普通敏感列必须写`NULL`。这些提取后的权威诊断事实按长期留存契约治理，常规维护任务不得删除。主体大信封仍只服务于完整原文复核并继续遵守`retention_until`，其权限和生命周期不能被诊断权限替代。`POST /api/v1/feedback/{id}/diagnostics/authorize`每次实时检查`action:view_feedback_diagnostics`和数据范围，要求3至200字用途，只返回获准字段类型和值，响应`no-store`，并以`FEEDBACK_DIAGNOSTICS_VIEW`安全审计记录元数据；审计、日志和通知不得包含明文。关闭查验窗口后客户端必须清除内存。生产仍须把本机密钥方案替换为KMS/Secret Manager并完成正式轮换演练。

## 2.1 Station Feedback 兼容实时 API

这里的 Station Feedback 是旧版反馈 SDK/旧反馈平台接口兼容渠道，数据库渠道代码为 `STATION_FEEDBACK`。通用反馈 SDK 使用独立的 `SDK` 渠道且尚未上线，两者在开发期同时保留；只允许公共反馈字段在统一主表层汇合，不共享接入协议、认证、扩展字段、任务状态、重试接口或指标。

只有一个旧反馈平台，`POST /v1/feedback` 与 `POST /v1/feedback/new` 是它的两个实时上报接口，共用同一个 `FeedbackVo`，主要区别是手机号处理；不存在两个旧平台。当前迁移阶段由旧平台保留 UC 身份认证、文本审核和手机号解析，再通过 Dubbo RPC 将已解析请求交给新系统可靠任务；新系统后台严格按“旧 FBP 成功→新平台正式入库”处理。RPC API坐标为`com.feedback:feedback-platform-rpc-api:1.0.0-SNAPSHOT`，服务版本`1.0.0`；调用方和可靠双写均默认关闭。当前交付的目标环境为本机部署环境，新平台及本机基础设施可重建；启用前仍须完成本机注册中心、Dubbo 2.6→3.3运行态互操作、真实旧FBP故障及对账验证。此前HMAC/HTTP适配器保留为历史验证方案，不再是当前调用端实现。后续如改为本系统直接消费 UC API 自行认证和手机号回查，仍必须以供应方真实 UC/Dubbo 契约为准。

RPC请求显式携带`V1/V2`、稳定幂等键、已解析用户ID/手机号、产品编码及完整反馈字段。可靠模式下Provider只在新系统MySQL事务中写入AES-GCM密文任务并快速返回receipt，正式入库仍复用`StationFeedbackIngestService`，不建立旁路数据链。返回值非空且`status == "0"`表示任务已可靠受理，不表示旧FBP或新平台正式入库已经完成。旧平台开关为`study.station.feedback.rpc.enabled`与`study.station.feedback.async.enabled`；新平台使用`FEEDBACK_RPC_ENABLED`、`FEEDBACK_RPC_REGISTRY_ADDRESS`、`FEEDBACK_RPC_PORT`及`app.station-feedback.dual-write.*`。双写稳定并完成对账前不得删除旧FBP路径；最终下线旧路径必须另行完成失败补偿、回滚和存量对账门禁。

2026-08-17本机运行态已完成JDK 8 / Dubbo 2.6.3-private Consumer到Apache Dubbo 3.3.5 Provider的互操作验证。旧私有`DubboCodec.inConflictVersions()`在直连时会在标准参数描述符前写入额外整数`-1`，造成Dubbo 3解码错位；这不是业务DTO或Provider实现错误。两端改为通过ZooKeeper发现后，Consumer读取到Provider的`dubbo=2.0.2`元数据并发送标准报文，首次请求成功落库且幂等重放返回同一receipt。该旧Consumer禁止使用直连URL；注册中心可用性和Provider协议元数据必须纳入部署及故障门禁。

同日本机入口验收覆盖V1、V2、旧FBP失败短路及RPC失败传播：旧FBP以Mock HTTP响应隔离外网，V1/V2随后调用真实ZooKeeper/Provider；旧FBP失败时不产生RPC调用，RPC失败统一返回旧反馈平台错误。照片列表在Consumer边界转换为Java`ArrayList`以满足Dubbo 3 STRICT序列化策略。入口、Consumer契约和幂等互操作共8项测试通过。

可靠异步任务放在有权威MySQL的新系统中，而不是缺少可确认数据库的旧服务中。显式同时启用旧服务`study.station.feedback.async.enabled=true`和`study.station.feedback.rpc.enabled=true`后，V1/V2完成既有认证、文本审核及手机号解析，只同步调用一次RPC；Provider由`V195__station_reliable_dual_write.sql`的`t_station_dual_write_task`原子保存AES-GCM密文信封并返回。租约Worker先调用旧FBP，成功后独立落状态，再正式入库新平台；正式入库失败只重试新平台步骤，不重复调用旧HTTP。任务具有请求指纹幂等、过期租约恢复、指数退避及最终失败状态；旧FBP token按配置短期缓存，并设置独立连接/整次调用超时。2026-08-19定向单元测试与`feedback_platform_test` MySQL集成测试通过，覆盖同键重放/冲突、密文恢复、租约恢复及“旧FBP成功→新平台失败→重试跳过旧HTTP→COMPLETED”。同日新平台本机部署已通过Keychain注入配置实际启用，健康UP、Dubbo 20880监听、V195/53表成功、旧FBP token只读连通PASS，未写合成反馈且任务表0行。由于本机没有运行中的旧`study-station-server`实例，旧V1/V2调用端开关尚未运行态生效，完整首批真实流量仍待旧服务真实UC/审核配置具备后验证。

两个兼容入口返回 `{"code":0,"msg":"ok"}` 成功响应，并最终统一写入 `STATION_FEEDBACK` 渠道、共用同一套业务字段映射。V1和V2入口都被`AuthInterceptor`的`/**`规则覆盖，都会通过`AuthApiService.authForUserUid`完成UC身份认证；V2并非不需要UC认证。差异只在手机号：V1忽略请求`phone`并按已认证用户回查UC手机号；V2有`phone`时本地执行旧AES解密，因此省去这一次手机号查询，空值时仍回查UC。旧源码允许正文`userId`覆盖认证用户，但这是已识别的信任缺陷，本系统只保存其作为来源参考，不用于认证或手机号查询。可靠模式的同步路径只保留旧服务既有认证、审核、手机号解析和新系统任务受理；旧FBP token/提交HTTP及新平台正式入库均移到新系统Worker。`feedbackAttributeRecords`继续固定忽略，不落库、不参与幂等哈希，也不进入查询、AI、索引、报表或导出。

V185起，Station实时V1/V2请求与历史Excel导入都必须在同一业务事务内写入`t_station_feedback_raw` AES-256-GCM原始负载信封，正式内容表仍只保存脱敏结果；原文密文写入失败时整次受理回滚，禁止形成“已隔离但无复核证据”的半成功记录。密钥使用Station独立用途域和key ID，目标环境必须配置独立`STATION_RAW_STORAGE_SECRET`；默认保留90天，已完成任务到期可清理，待审任务不得被普通清理任务提前删除。只有`action:inspect_masking_raw`可在填写用途后受控解密，响应禁止缓存并记录帐号、用途、IP和任务；返回范围仅含标题、正文、补充描述及定位元数据，手机号、附件URL和完整原始行不随隐私复核响应返回。Station支持误报放行、重脱敏放行和保持隔离，所有放行继续以安全重检和审计为前提。

Station来源协议版本继续保存为`source_api_version=V1/V2`，公共字段转换另以`station-map-v1`记录，标准化处理以`station-norm-v1`记录；三者职责不同。只有V1/V2公共字段语义发生变化时才发布新的字段映射版本，不能用API版本冒充映射版本。

调用方可提供 8～100 字符的 `Idempotency-Key`；同键同请求返回原 receipt，同键不同请求拒绝为冲突。调用方未提供时，服务端以规范化后的完整兼容请求生成稳定 SHA-256 业务键，用于相同请求重放去重。Worker 可重新领取租约已过期的 `PROCESSING` 记录，以保证应用在受理后重启仍能继续处理。

Station 的 `photoUrlList` 由受理后的 Worker 异步下载，不占用兼容接口响应时间。下载器仅允许 HTTPS/443，拒绝凭证 URL、重定向及解析到环回、私网、链路本地、运营商 NAT 或组播地址的域名；响应采用流式大小限制并校验 JPEG/PNG/WebP 文件头、Content-Type 和 SHA-256。文件复用 `t_feedback_attachment`，以 `STATION_FEEDBACK` 标识来源并停留在 `QUARANTINED`，内容安全扫描通过前不得进入普通查看、AI、索引、报表或导出。

附件的“上传/下载完整性通过”和“内容安全通过”是两个不同结论。Station 明文图片由异步扫描 Worker 通过 ClamAV `INSTREAM` 扫描，只有扫描引擎明确返回 `OK` 才将 `security_status` 从 `QUARANTINED` 改为 `VERIFIED`；发现恶意内容改为 `REJECTED + FAILED`，扫描器关闭、超时或不可用均保持隔离。SDK 和导入当前保存客户端加密信封，密文哈希通过不代表文件安全；在可信解密域完成解密和明文扫描之前同样保持 `QUARANTINED`。禁止将 hash/MIME 校验写成“病毒扫描通过”。

V174 在原附件表内拆分 `integrity_status`、`decryption_status`、`malware_scan_status` 和 `content_review_status`，不新增表。可查看的必要条件为完整性 `VERIFIED`、解密 `NOT_REQUIRED/DECRYPTED`、病毒扫描 `CLEAN`、内容审核 `PASS`、总安全状态 `VERIFIED`，且文件未删除、未过保留期。内容审核默认失败关闭为 `REVIEW_NEEDED`；病毒清洁不能替代涉黄涉暴、诈骗二维码、敏感图像等内容审核。

附件按当前角色权限直接查看，无请求体且不填写理由；底层一次性短时安全流绑定当前用户、反馈、附件和固定动作码并记录审计。实际读取时再次查询全部安全状态和保留期，且只允许读取配置附件根目录内的普通文件，拒绝软链接、数据库越界路径和永久直链。日志仍仅限管理员/审计员，普通图片按数据范围与既有角色控制。

Android SDK 附件协议升级为 `FBA2-AES-256-GCM+RSA-OAEP-256`：每个附件生成独立 AES-256 key，客户端只保存 RSA-OAEP-256 封装后的 key，metadata 携带 `encrypted_data_key` 和 `algorithm_version`。服务端收到完整密文并验证传输 SHA-256 后，在受控临时文件中解封装和校验 GCM，立即清除内存 key/明文数组，持久文件保持隔离并进入统一病毒和内容审核。FBA1 旧附件缺少独立封装 key，服务端失败关闭且不得自动使用正文 key 降级。生产查看启用前还必须将本地存储替换/挂载到 KMS 或具备静态加密的对象存储；应用层受控路径不等于生产静态加密已完成。

Jira、质量平台和红黑榜的导入附件上传协议统一为 `FBI2-AES-256-GCM+RSA-OAEP-256`，multipart 除密文、密文 SHA-256 和 MIME 外必须提供 `encrypted_data_key` 与 `algorithm_version`。FBI2 信封为 magic、12 字节 nonce、ciphertext、16 字节 GCM tag；服务端独立解封装后才进入统一扫描。旧 `client-envelope` 不具备服务端可验证的独立密钥契约，禁止自动兼容放行。

扫描和内容审核全部通过后，平台使用独立存储密钥生成 `FBS1` AES-256-GCM 静态加密文件，删除隔离明文，并记录 `storage_key_id`。授权查看时只在受控临时流中解密；未配置至少 32 字符的存储密钥时保持隔离。生产应把该密钥放入 KMS/Secret Manager 并实现轮换，源码和普通配置文件不得保存密钥。

Station 身份链分为两层。外围 HMAC 只认证调用方和请求完整性，不产生终端用户身份；规范串依次包含 HTTP 方法、原始路径、Unix 秒时间戳、nonce、调用方 ID、请求体 SHA-256，请求头为 `X-Station-Client-Id`、`X-Station-Timestamp`、`X-Station-Nonce`、`X-Station-Signature`。服务端限制时间窗并使用 Redis 原子 nonce 防重放，认证配置关闭、密钥不足 32 字符或 Redis 不可用时一律拒绝。

旧源码已确认终端用户认证使用 `x-auth-token`、产品编码和请求来源 IP 调用 UC `AuthApiService.authForUserUid(token, appCode, ip)`，只采用 UC 返回的非空用户 ID；客户端提交的 `X-Station-User-Id` 不可信且不参与新契约。产品编码缺失、客户端版本缺失或不符合三段式数字版本均拒绝。当前实现以 `StationUcAuthenticator` 端口承接该契约，未配置目标环境 UC/Dubbo 实现、UC 异常或返回空用户时失败关闭。外围 HMAC 正式启用前仍必须与实际网关签名规范逐字节联调；限流、调用方/app 白名单、灰度比例和旧 Base URL 回滚在网关配置，应用继续提供签名、重放保护、UC 身份认证和业务幂等最后防线。

HMAC 规范：`HMAC-SHA256(app_secret, METHOD + PATH + timestamp + nonce + client_id + sha256(body))`，各字段以换行分隔。服务端验证时间窗、nonce 唯一性和调用方密钥，未知调用方或签名失败拒绝处理；HMAC 成功不能替代 UC Token 认证。

### 真机接入结论

仓库包含可用于真机的 Android SDK，不只是服务端模拟接口。`health-demo` 与 `child-art-demo` 是两个宿主示例，可自动采集应用版本、Android 版本、设备型号，并在授权和配置允许时提供网络类型、剩余存储和电量。客户端预过滤位于 `sdk/src/main/kotlin/com/feedback/sdk/privacy/`，环境采集位于 `sdk/src/main/kotlin/com/feedback/sdk/telemetry/EnvironmentCollector.kt`。服务端严格脱敏仍是最终门禁。

调用链为：宿主初始化 → 获取动态表单 → 生成稳定业务 ID → 客户端基础过滤与加密 → HMAC 上报 → 保存 receipt → 查询处理状态。弱网请求进入本地队列，恢复后复用业务 ID 重试。

Android SDK在每次反馈请求中发送动态表单对应的`schema_version`，服务端在受理前检查支持列表并保存到`t_sdk_ingest.schema_version`；异步解密和字段转换使用该已冻结版本，未知版本失败关闭。当前兼容版本为`sdk-form-v1`。
SDK `trigger_type` 的传输、入口校验与两级存储上限统一为64字符；客户端只在超过64字符时截断，不能恢复为历史20字符边界。正式`t_channel_sdk`仍遵守既有触发枚举，未知宿主标识归一为`API_CALL`。

| 阶段 | 含义 | 宿主建议 |
|---|---|---|
| `ENCRYPTED_ACCEPTED` | 已接收密文，不代表脱敏完成 | 提示“已收到，处理中” |
| 处理中 | 解密、脱敏或正式化进行中 | 按退避策略查询 |
| 完成 | 已生成平台记录 | 显示成功与平台处理状态 |
| 隔离/失败 | 需隐私修复或技术重试 | 不展示内部原始错误 |

## 3. 导入 API

| 方法 | 路径 | 权限 | 用途 |
|---|---|---|---|
| GET | `/api/v1/import/templates` | 导入管理 | 查询已发布渠道模板 |
| POST | `/api/v1/import/upload` | 上传文件 | 小文件上传、预览和映射判断 |
| POST | `/api/v1/import/upload/chunk` | 上传文件 | 加密大文件分片暂存 |
| POST | `/api/v1/import/upload/chunk/{fileId}/complete` | 上传文件 | 完成暂存；SDK 模拟渠道额外要求管理员 |
| GET | `/api/v1/import/batches` | 导入管理 | 按渠道和状态查询批次 |
| POST | `/api/v1/import/batches/{id}/confirm` | 上传文件 | 确认并版本化字段映射 |
| POST | `/api/v1/import/batches/{id}/retry*` | 操作员/管理员 | 失败或技术失败重试 |
| POST/DELETE | `/api/v1/import/batches/{id}/rollback` | 管理员 | 在允许窗口内安全回滚 |
| GET | `/api/v1/import/batches/{id}/export` | 导出 | 下载脱敏对账报告 |

## 4. 查询与分析 API

- `GET /api/v1/feedback`：渠道、关键词、严重度、状态、模块、版本、时间和来源标签筛选。
- `GET /api/v1/feedback/{id}`：统一字段、渠道元数据、来源、AI、问题簇、附件和审计时间线。
- `GET/POST /api/v1/feedback/export`：条件导出或显式 ID 批量导出，要求导出权限并执行字段过滤。
- `GET /api/v1/search/suggest|similar`：关键词建议和相似反馈查询。
- `GET /api/v1/dashboard/*`：接入、趋势、分布、热点、原始/去重计数和数据新鲜度。
- `POST /api/v1/dashboard/ask`：自然语言分析，只叙述程序查询事实。

## 5. AI、问题簇、报告和告警 API

- AI 复核：分类字典、待复核队列、单条/批量确认或修正。
- 问题簇：候选列表、确认、拒绝、稍后处理、新建簇、解除关联、合并拆分和生命周期。
- 报告：六类报告生成任务、查询、取消、重试、PDF，以及 RCA 触发/审核。
- 告警：事件列表、统计、证据、确认、转派、静默、解决、关闭和通知回执。
- 管理：用户角色、数据权限、模板/枚举配置、Prompt、脱敏版本、评估集和告警规则。

## 6. 错误、幂等和审计

安全错误返回稳定代码，不回显异常原文。SDK 使用 `app_key + client_feedback_id`；导入使用渠道业务键；归簇、报告和高风险操作使用任务 key、request ID 或期望版本。查询、导入、导出、人工修正、原文检查和附件解密均记录审计。

| HTTP | 场景 | 客户端处理 |
|---:|---|---|
| 400 | 字段、映射或分片参数不合法 | 修正输入，不盲目重试 |
| 401 | 签名、时间窗或 nonce 不合法 | 校时/刷新配置后有限重试 |
| 403 | 用户、渠道、字段或 receipt 归属越权 | 停止并记录安全事件 |
| 409 | 幂等、版本或分片状态冲突 | 查询既有状态 |
| 413 | 文件或附件超过限制 | 压缩、分片或提示用户 |
| 429 | 限流 | 按 `Retry-After` 退避 |
| 5xx | 暂时性故障 | 使用相同幂等键指数退避 |

## 核心 API 目录

| 领域 | 基础路径 | 主要能力 |
|---|---|---|
| SDK | `/api/v1/sdk` | 反馈、附件分片、遥测、配置、receipt 进度 |
| 导入 | `/api/v1/import` | 模板、上传、分片、批次、重试、回滚、对账 |
| 反馈 | `/api/v1/feedback` | 多渠道列表、详情、来源、状态、导出 |
| 搜索/看板 | `/api/v1/search`、`/api/v1/dashboard` | 关键词、相似搜索、指标和分析助手 |
| AI 复核 | `/api/v1/ai-reviews` | 分类字典、待复核队列和人工结论 |
| 问题簇 | `/api/v1/cluster` | 候选、确认、拒绝、解除、合并和拆分 |
| 报告/RCA | `/api/v1/reports` | 任务、报告、PDF、RCA 和知识关联 |
| 告警 | `/api/v1/alerts` | 列表、证据、确认、转派、静默和关闭 |
| 管理 | `/api/v1/admin` | 用户、角色、数据权限、Prompt、脱敏和告警规则 |

当前项目未启用 Swagger，真实契约以控制器、DTO、安全矩阵和集成测试为准，不能使用旧文档中的 `/swagger-ui/index.html`。
