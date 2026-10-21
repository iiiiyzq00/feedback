# AI Prompt assets

Prompt 文件采用 `prompts/<skill_code>/<semver>.txt` 命名，并登记在 `manifest.json`。文件、模型参数和 SHA-256 进入 Git review；创建 API 会拒绝任何不在 manifest 中或内容/模型参数不一致的版本。启动导入器以 Git 为权威源，将历史 V16 baseline 一次性校准到 manifest，后续发现数据库漂移会直接阻断。

发布流程：Git 文件与 manifest → 创建 DRAFT → 结构门禁 → 真实模型标注集/安全集评测 → TESTED → 第二人 APPROVED → PUBLISHED/current。发布会原子退役旧版本；回滚只允许选择曾发布或已退役版本。已发布文件和数据库记录均不得原地修改，任何变更必须创建新语义版本。

Git 是内容唯一来源，数据库是经审批后的运行时投影。调用记录保存 skill version、prompt version/id/SHA-256、模型配置、应用版本、token、延迟、费用和供应商请求号。生产环境启用 `AI_REQUIRE_EVALUATION_FOR_INVOCATION=true` 后，没有真实模型评测证据的 Prompt 不能被调用。
