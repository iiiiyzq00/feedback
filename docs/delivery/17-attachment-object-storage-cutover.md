# 附件切换至私有 MinIO/S3

## 边界

本流程只切换已经通过完整安全检查的附件。数据库 `AVAILABLE` 是权威读取切换点：一旦登记为 `AVAILABLE`，读取失败必须关闭请求，禁止静默回退本地副本。启用本机清理时，只有对象长度、SHA-256、版本、数据库 `AVAILABLE` 事实和安全路径全部重新核对成功，才逐文件精确删除旧密文；禁止目录级、递归或通配符删除，禁止删除 MinIO 数据。

页面按当前角色权限直接展示附件，不存在“受控查看”或填写查看理由的产品流程。一次性同源流只是防止暴露永久 URL、对象键和凭据的底层实现。

## 前置条件

- 私有 bucket 已创建并启用 TLS；生产不得设置 `ATTACHMENT_OBJECT_STORAGE_ALLOW_INSECURE_ENDPOINT=true`。
- 应用身份具备 bucket readiness、PutObject、GetObject、HeadObject、DeleteObject 所需的最小权限。
- 通过密钥管理注入 endpoint、region、bucket、access key 和 secret key，不把真实凭据写入仓库。
- 已备份数据库；迁移前本地加密目录保持可读，清理开关必须默认关闭。
- 明确配置 `MINIO_DATA_DIR`；清理器把该目录作为禁止目标，启动脚本也只用此变量指定 MinIO 数据根。

## 分阶段开关

基础连接配置：

```text
ATTACHMENT_OBJECT_STORAGE_ENABLED=true
ATTACHMENT_OBJECT_STORAGE_ENDPOINT=https://minio.example.internal
ATTACHMENT_OBJECT_STORAGE_REGION=us-east-1
ATTACHMENT_OBJECT_STORAGE_BUCKET=feedback-private
ATTACHMENT_OBJECT_STORAGE_PATH_STYLE_ACCESS=true
ATTACHMENT_OBJECT_STORAGE_ACCESS_KEY=<secret injection>
ATTACHMENT_OBJECT_STORAGE_SECRET_KEY=<secret injection>
MINIO_DATA_DIR=/srv/feedback-minio/data
```

1. 连接验证：保持 `ATTACHMENT_OBJECT_STORAGE_PUBLISH_NEW=false`、`ATTACHMENT_OBJECT_STORAGE_MIGRATION_ENABLED=false`。启动失败时不得绕过 readiness。
2. 新写验证：设置 `ATTACHMENT_OBJECT_STORAGE_PUBLISH_NEW=true`。上传各一份图片、视频、日志和压缩日志，核对对象读回 SHA-256、页面预览和原始日志下载。
3. 历史盘点：设置 `ATTACHMENT_OBJECT_STORAGE_MIGRATION_ENABLED=true`、`ATTACHMENT_OBJECT_STORAGE_MIGRATION_DRY_RUN=true`，观察每批候选数量和字节数；默认每 30 秒最多 25 条。
4. 小批迁移：确认容量与告警后设置 `ATTACHMENT_OBJECT_STORAGE_MIGRATION_DRY_RUN=false`。需要降载时调小 `ATTACHMENT_OBJECT_STORAGE_MIGRATION_BATCH_SIZE` 或增大 `ATTACHMENT_OBJECT_STORAGE_MIGRATION_DELAY_MS`。
5. 对账：数据库 `AVAILABLE` 行必须能从配置 bucket 读取，长度及 SHA-256 与附件事实一致；未登记行继续使用本地加密文件。

## 本机空间有界迁移与清理

本机使用专用非 Web 命令，避免索引回填、聚类和其他调度任务抢占迁移资源：

```bash
ATTACHMENT_OBJECT_STORAGE_MIGRATION_BATCH_SIZE=100 \
ATTACHMENT_OBJECT_STORAGE_MINIMUM_FREE_DISK_BYTES=32212254720 \
MINIO_DATA_DIR="$PWD/.runtime/minio/data" \
./tools/run-attachment-object-migration-local-secure.sh
```

本机脚本显式列出当前附件根以及已确认的两个历史只读根：项目级 `data/attachments` 和后端工作目录级 `backend/data/attachments`。后者仍包含 Jira 历史附件，不能因为根未配置而误报缺源；禁止自动发现或放宽到任意绝对路径。

命令从 macOS Keychain 读取凭据，不把 endpoint 或凭据写入数据库。每批流程固定为：

1. 上传前完整校验本机密文解密后的长度与 SHA-256；单文件明文内存上限默认 128 MiB，使用后立即覆零，不落明文临时文件。
2. 上传到私有 bucket，完整读回并核对 bucket、对象键、版本、长度和 SHA-256。
3. 用最多 5 秒的独立单行 CAS 登记 `AVAILABLE`；MinIO I/O 和文件 I/O 均在事务外。
4. 重新读取 DB 行和 MinIO 对象，再次解析白名单真实路径，显式排除 `MINIO_DATA_DIR`。
5. 通过 `Files.delete` 精确删除一个普通非符号链接文件，再用独立单行 CAS 清空旧 `storage_path/storage_key_id`。
6. 审计文件记录验证删除意图和完成结果，只保存附件 ID、字节数、内容哈希、路径摘要和对象键摘要，不保存凭据或主机 URL。

磁盘可用空间减去下一对象大小低于安全水位时整批暂停。失败项最多重试配置次数，仍失败则保留本机源和数据库候选；轮转游标先处理其余可读附件，下一轮再回访失败项。只有“路径仍在显式白名单根内、但单文件已经不存在”的记录可以用 Repository/MyBatis 单行 CAS 登记 `ATTACHMENT_LOCAL_SOURCE_UNAVAILABLE`，并追加 `SOURCE_UNAVAILABLE_CLASSIFIED` 审计；路径越界、符号链接、目录或配置错误不得借此退出候选。该终态不伪造对象位置，也不得标记 `AVAILABLE`。完成判定必须同时满足：活跃迁移候选为 0、`AVAILABLE` 本机清理候选为 0、永久缺源均有终态失败审计、审计无未闭合删除意图、旧根无仍应迁移的 `.fbs`，且本机 MinIO 健康。

迁移以数据库状态断点续跑。对象上传、完整读回校验成功后才用单行 CAS 登记 `AVAILABLE`；CAS 失败会补偿删除本次对象，另一工作线程已经登记同一确定性对象键时按幂等成功处理。新附件使用同一发布器；CAS 提交后由独立清理 worker 重复执行相同四重验证，再安全清理暂存副本。

## 未来迁往服务器 MinIO

数据库只保存 `bucket/object_key/object_version/file_sha256` 等对象事实，不保存主机 URL；endpoint 只存在于部署配置，因此切换服务器不需要改对象键。

1. 在服务器创建同名私有版本 bucket 和最小权限身份，保持本机写入开启。
2. 使用管理员侧 `mc mirror --watch --preserve --overwrite local/feedback-private server/feedback-private` 做基线复制；该命令不使用 `--remove`，禁止反向或删除本机 MinIO。
3. `mc mirror` 复制当前对象内容和元数据，但不保证目标对象沿用源 MinIO 的 S3 version ID。进入维护窗口后停止应用写入，再执行一次不带 `--watch` 的增量 mirror，并按全部数据库对象清单核对 bucket、key、长度和 SHA-256。
4. 使用专用离线应用命令连接服务器 endpoint，逐对象读回并取得服务器返回的目标 version ID；四项校验成功后，才通过 Repository/MyBatis 单行 CAS 把该行从源 version 重新绑定到目标 version。禁止直接 SQL。任一行失败则保持维护窗口并终止切换。
5. 全部行重新绑定且活跃写入仍停止时，在同一维护窗口切换应用 endpoint/凭据并启动；bucket 与 object key 不变。运行 readiness、Head/Get、页面预览和原始日志下载验收后再恢复写入。
6. 观察错误率、对象版本与新增附件清理审计。确认稳定前保留本机 MinIO，禁止删除其数据。
7. 回滚同样需要维护窗口：停止服务器写入，把切换后新增/变更对象增量 mirror 回本机，使用同一离线应用命令全量校验并 CAS 重新绑定本机目标 version，再切回本机 endpoint。不得只切 endpoint、直接 SQL 回写版本、删除任一侧 MinIO 数据或静默回退旧本机附件文件。

## 回滚与故障处理

- 尚未登记 `AVAILABLE`：关闭历史迁移或新写开关即可，旧行仍从本地读取。
- 已登记 `AVAILABLE`：不得只关闭对象存储并期望自动回退。清理开启后本机副本可能已删除；跨 MinIO 回滚必须先追平对象并用应用层 CAS 重新绑定目标 version，再切 endpoint，不能回写旧路径。
- 对象读回长度或哈希不一致：该行不会登记，上传对象会被补偿删除，本地源保留供排障和重试。
- MinIO 不可用：`AVAILABLE` 行失败关闭；恢复对象服务后重试读取，不把本地副本当作静默容灾源。

## 环境结论口径

本机 `127.0.0.1:19000/feedback-private` 已完成 32,730 个附件、128,331,368,229 字节的迁移和逐项旧文件清理。命令运行 167 批后报告活跃迁移候选、清理候选、永久缺源和未闭合删除意图均为 0；两个历史根的 `.fbs` 均为 0，审计 JSONL 83,500 条且格式有效，MinIO live 健康。该结果只证明 macOS 本机闭环，不代表服务器或生产已部署。服务器 endpoint、TLS 与最小权限凭据未提供前，服务器切换仍记为 `BLOCKED_EXTERNAL`。
