# Shadow 归簇只读预览

## 交付边界

管理员页面 `/admin/cluster-shadow-preview` 只允许读取固定白名单 generation
`shadow-11-full-20260909-post-identity-r3`。服务端逐次要求数据库状态为 `VERIFIED`，并核对算法版本、
26,141 输入、4,937 ACTIONABLE、21,204 条分原因旧终态输入、461 个候选簇和 335 个 provisional 成员；任一项漂移即安全关闭，不回退其他 generation。

这是独立于“问题深度分析”的投影。“问题深度分析”仍读取具备 `PROBLEM_ANALYSIS` 能力的 ACTIVE generation；
本页不调用 `ClusterReclassificationService`，没有 ACTIVATE、Cluster、Link、MAIN 或 ACTIVE 写入口。

## 安全投影

- 候选读取前按成员重新确认 `masking_status='PASS' AND is_deleted=0`。只有 ACTIONABLE 成员的 PASS 脱敏标题/正文会在服务端进入确定性症状提取，用于重建可读问题名并与 generation 的 cluster key/name hash 逐簇核对；正文不进入响应。REDBLACK `REVIEW_NEEDED` 不会被读取。
- source 表只选择内部关联所需的 ID 和渠道；ID 只在服务内部 join，响应不返回 source ID、业务 ID、public/canonical UUID 或任何 hash。
- application 摘要只能反向匹配活动 canonical identity 的稳定 UUID，响应只返回展示名；缺失或歧义时整页失败关闭。QUALITY 的硬件字段不参与此映射。
- 簇以页面稳定匿名编号 `SC-xxxx` 展示。成员以当前匿名簇内序号 `SC-xxxx-Mxxxx` 展示，仅包含渠道、application、决策、分数以及五项边界是否已知。
- 候选表没有可对外展示的安全正文投影，详情明确返回 `bodyProjectionAvailable=false`；服务端只输出哈希复核通过的规范症状名，不输出代表正文或成员正文。
- 每次投影重新验证同簇自动成员的 application、failureMode、target、action、errorSignature 摘要零冲突。

## 接口

- `GET /api/v1/admin/cluster-shadow-preview`
- `GET /api/v1/admin/cluster-shadow-preview/clusters/{previewId}`

两者仅允许 `ADMIN`，且只有 GET。列表支持 `largeOnly`、`provisionalOnly`、`crossChannelOnly`、application 精确筛选和分页；详情最多每页 100 个匿名成员。

页面沿用“问题深度分析”的阅读结构：顶部口径与筛选、已确认应用问题簇和跨渠道问题簇两个独立分页区。具体问题列显示 `application·canonical symptom`（例如“知识课程·设备网络连接异常”），不再单列“影响应用”；application 在后端仍是不可跨越的硬隔离边界。页面不会跳转到 ACTIVE generation 的旧簇详情。

旧版未归簇区按当前白名单 generation 的真实成员状态展示九类原因，并明确说明这不是系统执行失败。页面同时标明：v3 最大项 application 身份未解析属于旧 generation 的提前终止口径，不能当作永久失败数量。

## application 待定症状归簇修正

后续 shadow generation 已切换到 `cluster-classification-v4-pending-application-shadow`：

- Jira、质量平台、红黑榜继续仅从 masking PASS 安全文本做当前活动 canonical application 的唯一精确命中；唯一命中即作为权威 application，零命中不伪造身份，多命中不任意挑选。
- 已确认 application 使用 `resolved:<application>` 内部分区，相同症状在不同 application 下绝不互相召回。
- 零命中使用 `pending:unresolved`，多命中使用独立的 `pending:ambiguous`；两者先提取原子症状，再受 failureMode、target、action、errorSignature 和症状相似度约束形成只读候选簇，且均不与已确认 application 合并。
- 待定分区的 `applicationKey` 始终为空，展示名仅为“应用待确认”或“应用有歧义（待确认）”。QUALITY 的主板、设备等硬件字段不会写入或伪装成软件 application。
- masking `REVIEW_NEEDED`、已删除或其他不合格内容仍在读取正文前失败关闭；无有效故障事实、噪声、信息不足等记录仍保持相应非归簇终态。
- v3 已确认 application 的症状指纹保持逐字节兼容，当前固定 VERIFIED generation 的只读 hash 复核不会因本次修正失效。

本次只完成算法和新 generation 构建路径修正，不修改当前 VERIFIED generation，也不 ACTIVATE。页面仍诚实展示当前白名单 v3 数据；只有完成新的 BUILD、VERIFY、结果审计并显式更新预览白名单后，才能展示新的“已确认应用问题簇 / 应用待定问题簇”真实统计。

## 本地查看

按现有本地安全启动方式启动后端和前端，以管理员账号登录。通过本机 HTTPS 入口时打开：

`https://192.168.3.140:8088/admin/cluster-shadow-preview`

使用 Vite 开发服务器且本地会话 Cookie 已配置为允许 HTTP 时，也可打开：

`http://localhost:5173/admin/cluster-shadow-preview`

也可从“系统管理 → Shadow 归簇预览”进入。数据库需要已经存在 V241 三张隔离候选表及上述 VERIFIED generation；不满足时页面显示“预览已安全关闭”。无需也不得重新运行 BUILD、VERIFY 或 ACTIVATE。

## 定向验证

```bash
./gradlew :backend:shadowPreviewTest --no-daemon
cd frontend
npm test -- --run src/api/clusterShadowPreview.spec.ts src/views/admin/ClusterShadowPreviewPage.spec.ts src/router/index.spec.ts
npm run build
```

`shadowPreviewTest` 使用独立源码集，不编译已知受 Station 缺失类型影响的常规测试源码，也不编译存在历史 `userIds` 问题的 `clusterShadowTest` 源码。
