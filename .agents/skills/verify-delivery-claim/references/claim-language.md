# Claim language and evidence traps

| Evidence | Defensible wording | Do not claim |
|---|---|---|
| Static/unit tests | 代码或静态交付通过 | 真实集成、已部署 |
| Mock/fake provider | 供应商契约逻辑通过 Mock 验证 | 供应商真实联调成功 |
| Missing service with fallback | 降级/失败关闭路径通过 | 外部依赖可用 |
| Local real database/service | 本机真实依赖联调通过 | 目标环境或生产通过 |
| Local deployment | 已部署到本机并完成指定核验 | 已上线生产 |
| Target environment | 指定目标环境、版本和依赖已联调 | 所有生产流量验收完成 |
| Production evidence | 指定版本已发布并满足观察窗指标 | 后续版本自动继承结论 |

Recurring traps:

- A mocked malware scanner proves the state machine, not ClamAV or virus-database readiness.
- A mocked decryption port proves downstream behavior after key recovery, not that the deployed key pair matches the client.
- A passing fallback gateway proves failure handling, not Elasticsearch, Milvus or the real LLM.
- A historical full test run cannot prove later migrations or code changes.
- A DRAFT Prompt with a static evaluation is not the deployed PUBLISHED/current Prompt.
- A browser stopped at login does not prove authenticated page visual acceptance.
- Notification task creation is not provider delivery; timeout requires final receipt reconciliation before compensation.
