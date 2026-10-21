# 文档中心

本目录已经按用途整合，避免评审者在零散过程文档中寻找当前结论。

开发续做首先阅读短入口 [`PROJECT_SNAPSHOT.md`](PROJECT_SNAPSHOT.md)，再按任务路由读取一个交付或专题文档。只有短快照不足以判断状态、阻塞或已知陷阱时，才定向查询 [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md)；评审交付物进入 `delivery/`。

| 目录 | 定位 | 使用方式 |
|---|---|---|
| [`PROJECT_SNAPSHOT.md`](PROJECT_SNAPSHOT.md) | 新任务默认低 Token 入口 | **先读本文件，再按任务路由读取** |
| [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md) | 完整当前状态、阻塞和踩坑 | 按关键词定向查询，不默认通读 |
| [`delivery/`](delivery/README.md) | 最终、完整、可验收的 13 项交付物 | **评审从这里开始；内容为当前交付口径** |
| [`reference/`](reference/) | 当前实现的深入技术参考和指标字典 | 交付文档需要展开细节时查阅 |
| [`archive/`](archive/) | 42 表整合记录及历史专项验收证据 | 仅用于追溯，不替代当前交付口径 |

项目上级目录 `/Users/yang/projects/docs/` 保存更早的需求、日级实施记录和图件证据，属于过程证据库。旧版 v5/v6 方案、历史截图或单项验收报告如与当前文档冲突，以当前代码、`PROJECT_MEMORY.md`、42 表迁移基线和 `delivery/` 为准。42 是物理表数；Flyway V171 后告警治理目录期望数为 57，二者不得混用。

四渠道完整数据流图位于 [`delivery/04-architecture-and-channel-flows.md`](delivery/04-architecture-and-channel-flows.md) 的“**四渠道完整数据流图**”章节，图中分别绘制 SDK、400 Jira、质量平台和红黑榜四条接入链路。
