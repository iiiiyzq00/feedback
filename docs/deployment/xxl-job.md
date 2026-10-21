# XXL-Job 多节点调度部署

反馈平台沿用 `study-station-server` 的执行器模式：Scheduler 适配层位于
`com.feedback.job`，由 `XxlJobConfig`、`@JobHandler` 和 `IJobHandler` 组成。
Handler 只委托业务编排或 Service；事务、幂等、租约和队列认领仍由业务层负责。

所有后端节点使用同一个 `xxl.job.executor.appname` 注册为一个执行器集群。Cron、
路由、阻塞策略和失败告警由 XXL-Job Admin 维护。项目不再提供 Spring
`@Scheduled` 本地调度入口，XXL-Job 是唯一任务触发链路。

## 启用执行器

生产节点配置相同执行器名，并使用不同地址和端口：

```bash
XXL_JOB_ENABLED=true
XXL_JOB_ADMIN_ADDRESSES=http://xxl-job-admin.example/xxl-job-admin
XXL_JOB_EXECUTOR_APPNAME=feedback-platform
XXL_JOB_EXECUTOR_PORT=-1
XXL_JOB_EXECUTOR_LOG_PATH=/var/log/feedback-platform/xxl-job/jobhandler
```

`XXL_JOB_ENABLED=false` 会关闭执行器及全部定时任务，不会回退到本地调度。

## 任务配置与同步

唯一任务清单为 [`config/xxl-job-tasks.conf`](../../config/xxl-job-tasks.conf)，共 45 个
BEAN Handler，字段依次为：

```text
handler|Quartz Cron|description|route|block|failure
```

本机 Admin 启动并完成执行器注册后，运行以下幂等同步命令：

```bash
tools/sync-xxl-job-tasks-local.sh
```

脚本会按 Handler 名新增或更新任务，重复执行不会创建副本。本地环境不依赖 Docker。
XXL-Job 1.9.1 使用 Quartz Cron，因此日/周表达式的“日”和“星期”字段必须有一个为
`?`。原 Spring `fixedDelay=100ms` 的 HTTP 审计消费者受 Quartz 精度限制，按最小
1 秒调度；其余 fixed-delay 周期按秒换算并记录在任务清单中。

## 多节点策略

- 日报、周报、版本报告等单次业务任务使用 `FAILOVER + SERIAL_EXECUTION`；执行节点
  故障时切换到健康节点，业务幂等键防止重复生成。
- 队列 Worker 和周期扫描使用 `ROUND + SERIAL_EXECUTION`；节点间轮询分摊负载，
  单节点内串行，队列认领、租约或幂等约束负责跨节点并发安全。
- `feedbackJiraAttachmentWorkerHandler` 单次下载可能明显超过 3 秒，单独使用
  `ROUND + DISCARD_LATER` 进行过载保护，防止节点内任务无限排队。由该策略产生的
  “丢弃后续调度”表示容量保护，不应按业务异常告警；需要持续观察丢弃率并据此扩容。
- Handler 抛出的业务异常由适配层转换为 XXL 失败结果，Admin 保留日志并执行
  `FAIL_ALARM`。不启用自动重试，避免绕开业务自己的重试、租约和幂等策略。

## 本机启动

本机 Admin 地址为 `http://127.0.0.1:18081/xxl-job-admin`，执行器应用名为
`feedback-platform`。两个进程均通过当前用户的 macOS `launchd` 托管：

```bash
tools/manage-xxl-job-admin-local.sh status
tools/manage-backend-local-secure.sh status
```

## 发布检查

1. 所有节点使用相同 `XXL_JOB_EXECUTOR_APPNAME`，地址列表包含全部健康节点。
2. Admin 中存在 45 个任务、45 个不同的 BEAN Handler 和 45 个 Quartz Trigger。
3. 后端日志出现 `Initializing XXL-Job executor`，Admin 注册心跳持续更新。
4. 代码与运行时均不存在 Spring `@Scheduled` 本地调度入口。
5. 分别手工触发关键周期任务和 Worker，确认业务异常能返回 XXL 失败状态。
6. 监控失败率、Jira 附件丢弃率和执行器离线情况，再决定扩容或调整任务周期。
