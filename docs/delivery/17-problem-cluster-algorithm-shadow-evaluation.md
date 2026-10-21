# 问题簇归类算法 v3 离线/影子评测报告

> 日期：2026-09-08（Asia/Shanghai）  
> 状态：已完成第三轮 untouched 决策均衡冻结验证和 shadow-12 窄修正试验；试验未过门禁并已撤回，最终仍为 shadow-11；已实现隔离 BUILDING/VERIFY generation，但未执行迁移、未创建运行实例、未修改 Cluster/MAIN/ACTIVE generation  
> 算法版本：`cluster-classification-v3-shadow-11`（shadow-9/shadow-10 指标保留为历史基线与开发证据）  
> 脱敏版本：`pii-v4-device-label`

## 1. 结论

shadow-11 仍只判断“用户表现是否为同一症状”，不进入根因分析。它在 application、failureMode、target、action、errorSignature 硬边界内使用 BGE-M3 补充同义召回；模型异常、超时、空向量或维度错误时整次精确回退规则结果。针对 shadow-10 已接受风险池暴露的误合并，shadow-11 细分题目/答案/过程/表达/讲解类 failureMode，并新增高余弦自动接受资格门禁：宽泛 failureMode，或 target、action、errorSignature 任一字段单侧缺失时，即使余弦达到 0.82 也只能进入 `PROVISIONAL`，不能自动 `ACCEPT`。application、五项症状硬边界和“不以字段缺失证明兼容”的契约均未放宽。

新增 untouched 决策均衡集 v3 共 450 项，在任何 shadow-12 代码改动或正文查看前，从排除既有 26+360+450+429 项后的 shadow-11 库存中冻结 `ACCEPT/PROVISIONAL/REJECT=150/150/150`。真值为 140 个同义正例、156 个不同症状负例和 154 个歧义排除。shadow-11 得到 TP/FP/FN/TN=110/5/30/151，precision=0.9565、recall=0.7857；precision 虽超过 0.95，但 5 个误接受和低于 0.90 的 recall 均不满足正式门禁。

基于此前 v2 的 FN 诊断，曾试验 shadow-12：把口语、书写、答案判错拆成具体症状，并为网络、无声、口语识别补默认 target。v3 一次性门禁结果为 TP/FP/FN/TN=114/3/26/153，precision=0.9744、recall=0.8143；仍有 3 个误接受，且旧 360 开发集 TP 从 124 降至 118。试验未继续使用 v3 调参，所有 shadow-12 运行代码和快照均已撤回。因此当前结论仍是：不具备正式全量 generation 重建条件，不启动全量重跑，也不切换 ACTIVE。

accept=0.82、provisional=0.68、语义权重=0.20 保持不变。现有证据说明提高阈值会显著损失同义正例，提高语义权重则会扩大高余弦困难负例的暴露面；本轮通过资格门禁和更具体的症状字段降低误合并，而不是用未经独立证据支持的参数漂移换取表面指标。冻结游标 33214 后 SDK/STATION_FEEDBACK 仍无新增；2026-09-08 最新只读聚合查询确认游标后 115 条均为 JIRA，项目键不作为 application，故独立增量 precision/recall 继续为 NA。本任务未进入 RCA、告警或人工运行链路。

随后从当前 859 个跨簇接受风险中排除全部 26 个开发样本，以三阶段“先冻结 hash 清单、后查看脱敏表述”的方式审完 360 个分层压力样本，得到 142 个同义正例、64 个不同症状困难负例和 154 个歧义排除。当前策略对可判定样本全部接受，条件 precision=0.6893；由于抽样母体本身就是已接受风险池，recall 必须记为 NA。将 accept 提高到 0.90 可把该压力集 precision 提到 0.9583，但正例保留率仅 0.4859；0.84–0.88 也无法同时满足精度和召回门槛。因此不调整 0.82/0.68/0.20，不把该分层压力集解释为生产自然分布或前瞻指标，也不基于它继续开发规则。

## 2. 算法变化

1. application 成为问题身份和候选召回的硬边界；不同 application 即使文本完全相同也不能成为候选。
2. 先按完整句、转折和并列边界切分，再从同一原子子句提取对象、动作、故障模式和稳定错误签名；标题/正文中的兼容重复事实先合并，“原因导致症状”句优先提取连接词后的结果症状，只有真正冲突的多问题进入 `MULTI_PROBLEM_AMBIGUOUS`。
3. 前置路由自动区分噪声、非故障输入、意图不完整、历史信息损失、未知症状和多问题歧义；这些状态不生成业务簇，也不进入人工等待链路。
4. 中英文有限同义归一化用于确定性特征；可选 BGE-M3 只补充候选召回，不调用 LLM，也不依赖 Milvus。
5. 硬边界使用 `MATCH/CONFLICT/UNKNOWN` 三态；字段缺失不会被解释为匹配。相似度拆为故障 0.45、对象 0.25、动作 0.15、签名 0.10、规范文本 Dice 0.05，分数只在同 application 内计算。稳定指纹一致可直接关联；非精确指纹候选必须边界匹配且分数不低于 0.70。
6. 规则候选与最多 50 个有界语义候选合并后取 Top-K 20。余弦不低于 0.82 才按 0.20 权重补分，0.68 至 0.82 只创建隔离临时候选簇；单次总预算 3 秒，任何模型异常精确回退规则结果。最优与次优候选分差不超过 0.03 时同样隔离，簇级冲突集防止传递误合并。
7. 簇指纹使用独立的 `symptom-fingerprint-v1` schema 和 application 生成 SHA-256，不再因运行算法版本号变化而必然全量换键；名称固定为“应用名·具体症状”，名称不参与身份或合并判断。
8. 人工结论作为不可变输入，输出 `MANUAL_LOCKED`，自动重跑不能覆盖。输入顺序变化、重复导入和并发重跑产生相同身份键、名称和归属。
9. shadow-10 增加 `READ_ALOUD_CONTENT_ERROR`，并把明确的“网络不好”纳入 `NETWORK`；这两项均在语义评分前参与 failureMode 硬边界。
10. shadow-11 进一步增加 `ANSWER_CONTENT_ERROR`、`QUESTION_INPUT_ERROR`、`PROCESS_ERROR`、`ANSWER_VARIANCE`、`EXPRESSION_ERROR`、`EXPLANATION_METHOD_ERROR`、`EXPLANATION_RESULT_ERROR`；DOWNLOAD/UPLOAD/SUBMIT/PLAYBACK 可从明确 failureMode 推导 action，下载对象可从症状文本提取，不同明确下载对象保持 target 硬冲突。
11. shadow-11 新增 typed `SemanticAcceptEligibilityReason`。宽泛 failureMode，或 target/action/errorSignature 单侧缺失时，高余弦结果降为 `PROVISIONAL`；只有 `ELIGIBLE` 才可语义自动接受。该门禁不把缺失字段改判为冲突，也不把歧义样本伪造成负例。

## 3. 可复现评测

执行命令：

```bash
./gradlew :backend:clusterShadowTest --rerun-tasks
```

冻结黄金集为 40 条合成脱敏样本，覆盖同应用同义表达、跨应用相同现象、同应用不同原子问题、空/短/模板/乱码/URL 噪声、中英文、多个问题、应用未知、重复导入和人工锁定。pairwise 指标只把具有业务真值的同组 pair 视为正例，非业务终态不当作同一簇。

| 指标 | 简化缺陷基线 | shadow-9 离线影子 | 变化 |
|---|---:|---:|---:|
| Pairwise precision | 0.2195 | 1.0000 | +0.7805 |
| Pairwise recall | 0.5625 | 1.0000 | +0.4375 |
| Pairwise F1 | 0.3158 | 1.0000 | +0.6842 |
| 业务簇数 | 19（含低信息聚集） | 15 | -4 |
| 非业务终态 | 基线继续成簇 | 11 | 低信息/歧义隔离 |

上表基线是测试内对旧缺陷的简化复现，不是完整生产管线回放，因此只用于回归发现，不将差值解读为真实业务提升。0.70 关联阈值和 0.03 竞争差值是与当前权重组合配套的保守影子参数，必须在真实人工真值上校准后才能进入正式决策。

性能门禁继续要求 5,000 条合成脱敏输入在 8 秒内完成。shadow-10 的 2,000 条历史复验产生 603 条可执行文本，本地 BGE-M3 推理总计约 54.2 秒，平均约 89.8 ms、P95 约 154.2 ms、最大约 383.2 ms；该值受本机热缓存和负载影响，不代表生产 SLA。

### 3.1 shadow-9 本机真实历史只读重放

2026-09-07 使用 `cluster-classification-v3-shadow-9+semantic` 和本地完整 BGE-M3 对本机 `feedback_platform_local_deploy` 从 source ID 0 开始读取同一首批 100 条。回放禁用 Flyway、调度器、启动回填、重分类工作线程和提示词发布，Hikari 连接池设为只读；测试内对同一快照连续执行两次且结果逐条一致。该次回放未建档、未写归簇事实、未修改活动簇。

| 指标 | 结果 |
|---|---:|
| 有效输入 | 100 |
| 当前 MAIN 去重簇数 | 25 |
| v3 建议业务簇数 | 34 |
| `ACTIONABLE` | 50 |
| `NOISE` | 8 |
| `NON_FAULT_INPUT` | 7 |
| `USER_INTENT_INCOMPLETE` | 9 |
| `LEGACY_INFORMATION_LOSS` | 1 |
| `MULTI_PROBLEM_AMBIGUOUS` | 3 |
| `APPLICATION_UNRESOLVED` | 22 |
| 人工锁定 | 0 |
| 全批 Pairwise 关系变化数 | 758（5 合并 / 753 拆分） |
| 50 条可执行问题的当前簇数 | 18 |
| 50 条可执行问题的 v3 簇数 | 34 |
| 可执行问题 Pairwise 变化 | 128（5 合并 / 123 拆分） |
| 下一游标 | 7165 |
| 可能还有下一批 | 是 |
| 两次快照重跑 | 逐条完全一致 |

全批关系变化主要由非业务终态从当前 MAIN 移出，以及原大簇按 application、对象和故障模式拆分产生，不能直接解读为“碎簇”。5 个新增合并 pair 来自同应用内稳定症状的自动归并；来源标签等弱证据只创建隔离临时候选簇。

### 3.2 真实 BGE-M3 校准与困难负例

shadow-9 基线只读扫描 2,000 条真实历史，其中 600 条可执行。原 595 个未标注兼容 pair 实际归并为 17 个独立标准化表述组；当时硬边界使 2 pair 在语义前安全退出，严格展开 593 pair。累计兼容真值库存中可评测为 647 正例和 2 个 `AMBIGUOUS_EXCLUDED`。

shadow-10 对同一 2,000 条窗口正式复验通过，603 条为可执行；17 个冻结表述组严格展开 581 pair。新增朗读 failureMode 硬边界使旧库存中的 12 pair 在语义前退出，可评测为 635 正例和 2 个歧义排除。歧义样本不计入混淆矩阵。

| 策略 | TP | FP | FN | Precision | Recall |
|---|---:|---:|---:|---:|---:|
| shadow-9 规则端到端（历史基线） | 622 | 0 | 25 | 1.0000 | 0.9614 |
| shadow-9 语义策略（历史基线） | 626 | 0 | 21 | 1.0000 | 0.9675 |
| shadow-10 语义策略（当前回溯） | 614 | 0 | 21 | 1.0000 | 0.9669 |

shadow-10 在 accept=0.82 时，语义权重 0.20/0.25/0.30/0.35 在当前 635 个兼容正例上的 TP 分别为 614/624/627/627。提高权重可增加回溯正例召回，但正 pair 仍受重复短句支配，且本轮 9 个兼容困难负例中仍有 4 个误合并风险；没有证据证明新增合并安全，因此仍选择 0.20。两版回溯表中的 precision=1.0000 都不能当作生产或独立前瞻精度。

另审阅 60 个真实高相似结构冲突 pair：确认 58 个硬负例，2 个样本推动具体 failureMode 纠错；统一语义清洗后仍有 58 个确认负例全部被 application 或 failureMode 硬边界阻断，误合并 0。真实 errorSignature 冲突组仍为 0；8 个高余弦合成签名夹具只验证契约，不计入真实 precision/recall。

同一 2,000 条窗口的本地 BGE-M3 分层进一步说明，余弦只能排序，不能突破硬边界：

| 分层 | pair | min | p50 | max |
|---|---:|---:|---:|---:|
| 冻结兼容正例 | 635 | 0.5709 | 1.0000 | 1.0000 |
| 同 application/failureMode、target 或 action 冲突 | 281 | 0.6919 | 0.8293 | 0.9210 |
| failureMode 冲突 | 14,690 | 0.3998 | 0.6857 | 0.9024 |
| application 冲突 | 153,487 | 0.3703 | 0.6070 | 1.0000 |
| 真实 errorSignature 冲突 | 0 | NA | NA | NA |

跨 application 的真实负例余弦最高可达 1.0000，target/action 冲突的中位数也高于 0.82，再次证明不能用更高语义权重替代硬边界。target 与 action 在该历史校准器中合并为一个冲突分层，未伪造字段级独立样本量；errorSignature 只有合成契约证据，真实分布仍为 NA。

### 3.3 缺失字段与后校准风险库存

既有 held-out 按支持渠道读取器扫描 13,189 条后续历史并隔离 calibration source/current cluster，窗口最后一个支持渠道 source 游标为 22254；冻结 882 条、其中 129 条可执行。2026-09-08 将三份冻结资产中原先混用全局游标 33214/全局读取数 13,191 的窗口元数据校正为支持渠道语义，所有输入、候选、簇和分组 digest 以及判定计数均未变化。缺失字段高余弦审阅共 14 组：1 个确认困难负例、13 个歧义排除；target/action/signature 分布为 12/1/0。唯一负例推动 `POST_DOWNLOAD_USAGE_FAILURE` 硬边界，修正后 false merge=0，但样本量不足以计算可信 precision/recall。

纯 action challenge 修正后 pair-level TP/FP/FN/TN=11/0/1/3，案例级为 2/0/1/1。shadow-9 后校准回溯窗口共有 5,027 个边界兼容表述组，2,617 个进入语义 accept 区间，1,579 个满足完整策略接受条件，其中 885 个跨当前簇；“跨当前簇”只用于风险排序，不自动等同负例。

本轮对这 885 个去重标准化表述 pair 完成冻结分层；它们对应 1,114 次原始 pair occurrence。application 和 failureMode 全部为 `SAME`，errorSignature 全部为 `BOTH_MISSING`，没有任何字段冲突。具体分层如下：

| application/failureMode/target/action/errorSignature 关系 | 去重 pair |
|---|---:|
| `SAME/SAME/BOTH_MISSING/SAME/BOTH_MISSING` | 401 |
| `SAME/SAME/LEFT_MISSING/SAME/BOTH_MISSING` | 28 |
| `SAME/SAME/RIGHT_MISSING/SAME/BOTH_MISSING` | 46 |
| `SAME/SAME/SAME/BOTH_MISSING/BOTH_MISSING` | 403 |
| `SAME/SAME/SAME/LEFT_MISSING/BOTH_MISSING` | 1 |
| `SAME/SAME/SAME/RIGHT_MISSING/BOTH_MISSING` | 1 |
| `SAME/SAME/SAME/SAME/BOTH_MISSING` | 5 |

因此该库存包含 target 单侧缺失 74 项、action 单侧缺失 2 项，不能把“字段缺失且余弦高”自动标成兼容或负例。冻结真值进一步明确分为 10 个 `SAME_SYMPTOM`、9 个 `DIFFERENT_SYMPTOM`、7 个 `AMBIGUOUS_EXCLUDED`：硬冲突负例必须能由明确的症状字段冲突证明；字段缺失、只能证明“无法确认兼容”的样本一律归歧义排除。所有冻结资产只含域隔离 hash、标签、reasonCode、五字段关系和计数，不含正文或业务 ID。

本地 BGE-M3 对 10 个正例的余弦分布为 min=0.8397、p50=0.9821、max=0.9976；窄修正后 9 个困难负例为 min=0.5346、p50=0.7623、max=0.8433。5 个负例由新硬边界在模型前拒绝，4 个仍会误合并，说明单靠 0.82 余弦不能保证同应用、同故障模式且字段缺失场景的安全性。shadow-10 重算后校准窗口为 4,962 个兼容表述组、2,584 个 accept 区间组、1,541 个完整策略接受组，其中 859 个跨当前簇；原 885 库存作为 shadow-9 历史证据保留，不覆盖重写。

### 3.4 独立于规则开发的分层压力集

从 shadow-10 当前 859 个跨当前簇策略接受项中排除既有 26 个开发审阅 hash。首轮在查看表述前冻结 178 项：全部 74 个 target 单侧缺失项、两个主分层各按 hash 顺序取 50 项、以及 4 个除 signature 外字段齐全项。首轮受真实库存中的 DOWNLOAD 高频短句支配，随后分别先冻结再审阅 102 个非 DOWNLOAD failureMode 增补项和 80 个 CONTENT_ERROR/WRITING_TRAJECTORY_ERROR challenge 项。三份清单均先于对应正文审阅冻结；不得将这种顺序式、困难负例富集设计解释为简单随机样本或自然患病率。

最终 360 项真值为 142 个 `SAME_SYMPTOM`、64 个 `DIFFERENT_SYMPTOM`、154 个 `AMBIGUOUS_EXCLUDED`。74 个 target 单侧缺失样本全部因不能证明兼容或冲突而排除，没有强行制造负例。主要确认负例包括：答案内容错误与正确答案误判 14 项、答案错误与题目错配 13 项、错误笔顺被接受与正确书写被拒绝 6 项、真实错字与正确书写误判 5 项、答案错误与答案漂移 5 项、讲解方法与讲解结果错误 4 项。冻结资产仅含 hash、标签、reasonCode、五字段关系和 occurrence count。

本地 BGE-M3 一次性评分结果：正例余弦 min=0.8212、p50=0.8981、max=0.9952；困难负例 min=0.8205、p50=0.8687、max=0.9399。因为母体限定为当前已接受风险，当前策略 TP/FP=142/64，条件 precision=0.6893，FN/TN 和 recall 不具有可辨识性，必须记为 NA。为展示参数取舍，仅报告同一压力集的阈值敏感性：

| accept | TP | FP | FN | TN | 条件 precision | 压力集正例保留率 |
|---:|---:|---:|---:|---:|---:|---:|
| 0.82 | 142 | 64 | 0 | 0 | 0.6893 | 1.0000 |
| 0.84 | 123 | 54 | 19 | 10 | 0.6949 | 0.8662 |
| 0.86 | 100 | 37 | 42 | 27 | 0.7299 | 0.7042 |
| 0.88 | 80 | 18 | 62 | 46 | 0.8163 | 0.5634 |
| 0.90 | 69 | 3 | 73 | 61 | 0.9583 | 0.4859 |

没有一个简单阈值同时达到 precision≥0.95 和正例保留率≥0.90。提高语义权重只会扩大当前已知误合并，宽化 CONTENT_ERROR 或 WRITING_TRAJECTORY_ERROR 词典也会把本压力集变成开发集并需要新的 untouched 验证。因此参数和 shadow-10 症状规则均保持不变。

### 3.5 shadow-11 决策均衡冻结验证

为避免只在“当前已接受风险池”中评估、从而无法识别 recall，本轮从 shadow-10 的 `ACCEPT/PROVISIONAL/REJECT` 三种决策中分别冻结样本，再查看脱敏表述并按同一症状、不同症状、无法证明三类裁决。冻结产物只保存 64 位域隔离 hash、标签、reasonCode、五字段关系和 occurrence count；不保存正文、业务 ID、手机号、身份证、儿童姓名、学校或班级。

第一份 v1 决策均衡集共 450 项，真值为 81 个 `SAME_SYMPTOM`、53 个 `DIFFERENT_SYMPTOM`、316 个 `AMBIGUOUS_EXCLUDED`。它在后续症状规则调整中被查看并用于诊断，因此从那一刻起降级为开发集，不能再称 untouched 或前瞻独立集。shadow-11 在该集上的 TP/FP/FN/TN=59/0/22/53，precision=1.0000、recall=0.7284；只作为开发回归证据。

随后在任何进一步规则调整前冻结 v2：与既有 26 项开发集、360 项压力集和 v1 的 hash 交集均为 0。库存提供 `ACCEPT=150`、`PROVISIONAL=150`、`REJECT=129`，REJECT 分层已耗尽，未补造样本。裁决后共 429 项：80 个同义正例、47 个兼容困难负例、302 个歧义排除。歧义主要来自硬边界字段缺失、宽泛 failureMode、对象身份不明或噪声表述；它们只能说明“不能证明兼容”，不能记为硬冲突负例，也不进入混淆矩阵。

| 冻结集 | 角色 | SAME | DIFFERENT | AMBIGUOUS | TP | FP | FN | TN | Precision | Recall |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| v1 | 查看后转开发集 | 81 | 53 | 316 | 59 | 0 | 22 | 53 | 1.0000 | 0.7284 |
| v2 | untouched 独立冻结集 | 80 | 47 | 302 | 58 | 0 | 22 | 47 | 1.0000 | 0.7250 |

v2 说明新增资格门禁能把本独立集中的确认负例全部挡在自动接受之外，但 22 个同义正例也被保守降级或拒绝。precision=1.0000 不可外推为生产精度或自然分布结果；recall=0.7250 明确低于正式重跑门禁 0.90。旧 360 项压力集在最终 shadow-11 下为 TP/FP/FN/TN=124/1/18/63，precision=0.9920、正例保留率=0.8732，但该集已参与开发，仅用于回归，不能替代 v2。

因此参数继续保持 accept=0.82、provisional=0.68、语义权重=0.20。本轮没有证据支持调高语义权重；直接提高 accept 也无法解决门禁导致的同义召回损失。正式更新前应先扩充全字段明确、可审计的真实同义正例，并针对 22 个 FN 做症状字段提取的窄修正，然后冻结新的 untouched 集复验；只有 precision≥0.95、recall≥0.90 且 P0 误合并为 0 时，才可进入 generation 全量重建演练。

v3 将这一结论提升为新的独立证据。其 450 项与此前所有真值 hash 交集为 0，抽样决策严格均衡；140/156/154 的标签在 shadow-12 试验前冻结且之后未替换。结果如下：

| 算法 | TP | FP | FN | TN | Precision | Recall | 判定 |
|---|---:|---:|---:|---:|---:|---:|---|
| shadow-11 | 110 | 5 | 30 | 151 | 0.9565 | 0.7857 | 误合并和召回均未过门禁 |
| shadow-12 窄试验 | 114 | 3 | 26 | 153 | 0.9744 | 0.8143 | 有改善但仍未过门禁，已撤回 |

shadow-12 同时把完整后校准策略接受组从 1,004 扩大到 1,643、跨当前簇接受组从 562 扩大到 957，风险暴露增长明显。由于 v3 已用于这次一次性判断，它后续只能作为开发回归证据；当前真实 REJECT 库存也已被 v3 的 150 项耗尽，不能再从同一窗口伪造一个“全新均衡集”。下一次正式门禁必须等待新增合规历史，或预先定义一个与现有窗口和 hash 全隔离的新时间/source 分区。

### 3.6 前瞻独立冻结门禁

新增 `BgeM3IndependentIncrementalValidationTest`，冻结游标为 33214。测试强制“冻结后记录只作 query、冻结前历史只作 candidate”，current cluster 仅用于风险排序；候选限定同 application、同 failureMode 和硬边界兼容，并独立审计错误签名冲突。持久化真值契约只允许 pair 哈希、标签与 reason code，歧义标签不计入 precision/recall。正式模式在新增记录为空或无可执行样本时失败关闭，discovery 模式只报告缺口。

2026-09-08 21:35（Asia/Shanghai）再次执行 discovery：冻结游标 33214，增量结束游标仍为 33214，支持渠道增量读取 0、可执行 0、兼容候选 0、accept 0、policy accepted 0、签名冲突 0；冻结前参考集有 4,537 条可执行记录，空候选摘要 digest 为 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。随后用同一只读账号做渠道聚合核验：游标后 115 条全部为 JIRA，SDK/STATION_FEEDBACK 为 0；应用身份影子入口只支持 `SDK/STATION_FEEDBACK`，最后一个支持渠道 source ID 仍为 22254。既有支持渠道历史已经全部处于此前评分窗口，v3 又已耗尽当前 REJECT 库存，因此当前不存在可诚实声明为 untouched 的新时间/source 分区。

本轮没有生成空壳冻结真值 TSV，没有从旧窗口重复抽样，没有绕过 application 边界或把 JIRA 项目键冒充应用身份。独立集 precision/recall 均为 NA；正式门槛状态为 `WAITING_FOR_NEW_SUPPORTED_CHANNEL_DATA`。在获得新增合规 SDK/STATION_FEEDBACK 历史、先冻结 hash-only 清单并完成独立裁决以前，不启动非 ACTIVE generation 全量演练。

### 3.7 扩大到 500 条的严格只读回放

2026-09-08 新增连续 5 批、每批最多 100 条的只读回放，两次从相同游标起点完整执行并逐批、逐条一致。shadow-11 最终 500 条结果为 166 条 `ACTIONABLE`、49 个建议簇、89 个新增合并 pair、19,412 个拆分 pair；规则版与语义版聚合关系一致。100 条冒烟同样双轮一致，结果为 50 条 `ACTIONABLE`、34 个建议簇、5 个合并 pair、753 个拆分 pair。回放日志不输出 sourceId、clusterId、正文或业务名称。

| 指标 | 500 条结果 |
|---|---:|
| 有效输入 | 500 |
| 当前 MAIN 去重簇数 | 57 |
| v3 建议业务簇数 | 49 |
| `ACTIONABLE` | 166 |
| 非业务终态 | 334 |
| 新增合并 pair | 89 |
| 拆分 pair | 19,412 |
| 下一游标 | 7565 |
| 可能还有下一批 | 是 |
| 两次完整重跑 | 逐条完全一致 |

终态分布为 `APPLICATION_UNRESOLVED=139`、`LEGACY_INFORMATION_LOSS=6`、`MULTI_PROBLEM_AMBIGUOUS=9`、`NOISE=55`、`NON_FAULT_INPUT=74`、`NOVEL_SYMPTOM=5`、`USER_INTENT_INCOMPLETE=46`。19,412 个拆分 pair 主要反映现有大簇中的非业务输入被移出，以及 application/症状硬边界拆分；没有逐 pair 独立真值，不能把该数直接解释为误拆率。

### 3.8 隔离 BUILDING generation 与 VERIFY 门禁

新增 V241 和独立的 `t_cluster_shadow_generation`、`t_cluster_shadow_candidate_cluster`、`t_cluster_shadow_candidate_member`。状态机只允许 `BUILDING/VERIFIED/FAILED`，数据库约束保证最多一个 BUILDING；没有 `ACTIVE` 状态、ACTIVATE 动作、Cluster/Link 外键或现有 Cluster/MAIN Mapper 依赖。迁移本身只建空表，不创建 generation。本轮没有在业务库执行迁移，也没有启动真实全量构建。

BUILD 显式要求真实 1024 维 BGE-M3 且拒绝 hashing provider，读取全部受支持渠道的安全历史快照，使用 shadow-11 分别按正序和反序计算。任何模型异常、超时、空向量、维度错误或余弦异常，在线影子仍可保持原有规则降级行为，但隔离 generation 会记录 typed failure 并关闭为 FAILED，不能把降级结果伪装为完整 BGE-M3 generation。构建产物不保存正文、展示名称或模型向量；候选成员仅保存 source 关联、状态、decisionCode、分数和 application/failureMode/target/action/errorSignature 的域隔离 SHA-256，候选簇只保存键/名称哈希及计数。

VERIFY 在独立动作中重新读取当前快照并重新执行一次 shadow-11，然后逐项校验：算法与模型版本、source fence、输入摘要、全量覆盖、成员唯一性、ACTIONABLE/终态一致性、正反序摘要、重放摘要、候选簇汇总、名称质量、provisional 计数，以及自动成员簇内五项硬边界零冲突。全部通过后只将该隔离 generation 标记为 `VERIFIED` 并记录验证人和 verification digest；不切换任何读取或写入链路。`VERIFIED` 只表示技术构建一致，不代表独立质量门禁通过；v3 的 5 FP 和 recall 0.7857 结论仍然有效，因此仍不可切 ACTIVE。

部署 V241 后，单动作 Runner 使用以下显式配置；BUILD 与 VERIFY 必须分两次启动，且入口刻意不接受 ACTIVATE：

```bash
APP_CLUSTER_SHADOW_GENERATION_ENABLED=true \
APP_CLUSTER_SHADOW_GENERATION_ACTION=BUILD \
APP_CLUSTER_SHADOW_GENERATION_GENERATION_KEY=shadow-11-full-YYYYMMDD \
APP_CLUSTER_SHADOW_GENERATION_ACTOR_ID=<audited-user-id> \
APP_CLUSTER_RECLASSIFICATION_WORKER_ENABLED=false \
BGE_M3_PATH=/absolute/path/to/bge-m3 \
./gradlew :backend:bootRun

APP_CLUSTER_SHADOW_GENERATION_ENABLED=true \
APP_CLUSTER_SHADOW_GENERATION_ACTION=VERIFY \
APP_CLUSTER_SHADOW_GENERATION_GENERATION_KEY=shadow-11-full-YYYYMMDD \
APP_CLUSTER_SHADOW_GENERATION_ACTOR_ID=<audited-user-id> \
APP_CLUSTER_RECLASSIFICATION_WORKER_ENABLED=false \
BGE_M3_PATH=/absolute/path/to/bge-m3 \
./gradlew :backend:bootRun
```

## 4. 典型纠错案例

| 类型 | 样例 | 基线风险 | v3 结果 |
|---|---|---|---|
| 跨应用误合并 | 学习机/课堂屏均“播放视频时卡住” | application 不在身份键中 | 两个 application 硬拆分 |
| 同义碎簇 | “卡住/一直很卡/video freezes” | 文本表达和首样本敏感 | 同应用归一为一个稳定身份 |
| 同应用误合并 | “视频卡顿”与“视频没有声音” | 共享对象、文本高度重叠 | 故障模式冲突，强制拆分 |
| 低信息污染 | “测试”“帮忙看看”“%%%%” | 进入统一低信息簇或新簇 | 非业务终态，无 cluster key |
| 跨子句拼接 | “视频卡顿，但是麦克风没有声音” | 任取最早故障并混用字段 | `MULTI_PROBLEM_AMBIGUOUS` |
| 因果句误拒绝 | “网络异常导致视频无法播放” | 同句命中两个模式即判多问题 | 提取结果症状 `PLAYBACK`，原因线索留待后续 RCA |
| 标题/正文重复 | 标题“视频卡顿”，正文“播放时一直很卡” | 可选对象/动作缺失造成误拒绝 | 兼容事实合并为一个原子问题 |
| 候选竞争 | 无签名样本同时接近两个不同签名簇 | 按排序猜测关联 | 分差不足输出 `DEFERRED` |
| 重跑漂移 | 输入顺序改变 | 首样本/在线顺序影响名称 | 稳定哈希与最小 source 锚点不变 |

## 5. 代码质量与事务边界

- 新内核通过 `AtomicProblemExtraction`、`ShadowCandidateRecall`、`SemanticEmbeddingPort`、`ClusterNamePolicy` 分离提取、规则召回、可选语义能力和命名；语义召回结果已进入影子决策。进程内最多缓存 5,000 个向量。
- 历史 `real-compatible-pair-ground-truth-v2.tsv` 与 `real-accept-hard-negative-ground-truth-v1.tsv` 已从字面 source pair 迁移为域隔离 SHA-256 `pair_case_hash`；测试内部按冻结 pair 重算哈希，保持原 60+60 个审阅单位并对缺失/碰撞失败关闭。未使用的 v1 source-ID 资产已删除。所有 `real-*.tsv` 只含哈希、标签、展开数和理由码等非正文审计字段。
- 新增 `post-calibration-unlabeled-risk-strata-v1.tsv` 冻结全部 885 个去重风险 pair，新增 `real-post-calibration-unlabeled-risk-ground-truth-v1.tsv` 冻结 26 个审阅结论。两者 schema 只允许 64 位 hash、枚举标签/reasonCode、五字段关系和 occurrence count；测试对行数、列数、hash、枚举、计数、库存包含关系及残留敏感格式失败关闭。正文只允许在显式 discovery 时短暂写入权限 0600 的 `/private/tmp`，不进入仓库。
- 新增三份独立分层抽样 manifest（178/102/80 项）和 `real-independent-post-calibration-ground-truth-v1.tsv`（360 项）。测试强制三份 manifest 互斥、与 26 项开发集无交集、真值完整覆盖、关系与 occurrence 不漂移，并固定 142/64/154 标签计数；这些资产同样不保存正文、业务 ID 或模型向量。
- 新增两份决策均衡 manifest 与配套真值。v1 为 450 项，查看后明确降级为开发集；v2 为 429 项，与此前 26+360+450 项 hash 完全无交集，且始终未参与 shadow-11 修改。四份 TSV 只含 hash、标签、reasonCode、五字段关系和 occurrence count；schema、64 位 hash、枚举、计数、集合覆盖及敏感格式扫描失败关闭。
- 新增 v3 决策均衡 manifest 与真值，各 450 项；与全部既有资产 hash 交集为 0，ACCEPT/PROVISIONAL/REJECT 各 150。真值固定 140 正/156 负/154 歧义，并在测试中锁定集合、关系、计数及 shadow-11 混淆矩阵；不保存正文、source/cluster ID 或业务名称。
- 新增 V241 隔离候选 generation、三个单表 Mapper/PO、Repository、shadow-11 BUILD/VERIFY 服务和默认关闭的单动作 Runner。候选表不保存正文、名称或向量；状态机无 ACTIVE，代码也没有 Cluster/Link 写依赖。VERIFY 独立重放并锁定全量覆盖、五项硬边界、正反序/持久化摘要及 typed failure reasonCode。
- 新增 `HistoricalApplicationFactPort` 和 `HistoricalClusterShadowInputPort` 分离应用身份事实、历史输入与纯计算协调；每个类的构造注入不超过 3 个。
- 新增注释和 OpenAPI Schema 全部使用中文，聚焦 application 硬隔离、人工优先、顺序无关、阈值依据、簇级传递冲突、稳定锚点与事务边界等非显然不变量。当前弱候选在有界影子批次内计算；正式大规模接入前仍需用持久化指纹/倒排索引保证召回复杂度。
- 真实输入适配器在单个 `@Transactional(readOnly = true)` 短事务内完成最多 100 条的批量读取；协调器无事务，事务提交后才运行 CPU 密集算法。没有网络、模型调用或 LLM 进入数据库事务。
- 只读归簇路径对应用 alias 一次批量读取并按 source 预计算作用域；它不自动建档。只有管理员显式批准的 application identity 治理批次可创建身份和 alias，并写入主问题影子事实与审计。人工 MAIN 对审核人、`CONFIRMED` 状态或 `MANUAL_*` 类型任一证据失败关闭为 `MANUAL_LOCKED`。
- 管理员 API 为 `POST /api/v1/admin/cluster-shadow-evaluation`，使用具名 Request/Response、Bean Validation 和中文 Schema；`/api/v1/admin/**` 继续由统一安全配置限定 ADMIN，不在 Controller 重复解析权限。
- 归簇重分类工作线程增加默认开启的 `app.cluster.reclassification-worker-enabled` 运行开关；现有部署行为不变，只读评测可显式隔离该独立线程。
- 本轮新增的 SQL/Mapper/Flyway 仅限 V241 和三个 `t_cluster_shadow_*` 隔离候选表；没有修改既有 Cluster/Link/MAIN/ACTIVE SQL。未修改附件、MinIO、SDK 接入代码。

## 6. 验证与已知阻塞

- `:backend:compileKotlin --rerun-tasks`：通过。
- 最终完整 `:backend:clusterShadowTest --no-daemon`：**74 项，62 项执行、12 项按真实库/BGE 环境门控跳过，0 failure/error**（2026-09-28 本机复测口径；本行原记 67/55/12，系本曲线写完之后又新增 7 项测试，跳过的 12 项不变）。覆盖五项硬边界、细分 failureMode、typed exception/reasonCode、高余弦自动接受资格门禁、模型异常/超时/空向量/错维降级、隔离 BUILD/VERIFY、乱序稳定性和真实性能门禁；本地 BGE-M3 冒烟及显式真实回放另行通过。
- `:backend:architectureTest --rerun-tasks --no-daemon`：25 项中 23 通过、2 失败。失败均来自本任务范围外的附件并行改动：`AttachmentMediaPreviewPo.kt` 违反一文件一 public PO 命名门禁；`SecureAttachmentRepository` 有 25 个 public method，超过已审阅上限 20。本轮未修改附件代码，也未放宽门禁或白名单。
- 显式独立增量 discovery：1 项通过；冻结后支持渠道新增 0 条，冻结前参考集 4,537 条可执行记录。只读渠道聚合确认游标后 115 条全部为 JIRA，故正式门禁状态为 `WAITING_FOR_NEW_SUPPORTED_CHANNEL_DATA`；未产生误导性的空真值指标，也未启动 generation 演练。
- 隔离 generation 定向测试：6/6 通过，覆盖正反序稳定、VERIFY 成功、application 硬冲突拒绝、BGE 降级失败关闭、无 ACTIVE/Cluster/Link 写路径，以及三个 MyBatis 映射独立解析。
- 显式真实库+BGE-M3 只读回放：`cluster-classification-v3-shadow-11` 的 100 条和 500 条回放均两次逐条一致。100 条产生 50 个 `ACTIONABLE`、34 个建议簇、5 个合并 pair、753 个拆分 pair；500 条产生 166 个 `ACTIONABLE`、49 个建议簇、89 个合并 pair、19,412 个拆分 pair。日志不输出 sourceId、clusterId、正文或业务名称。
- 正式非 discovery 后校准审计通过：885 个冻结风险组、1,114 次 occurrence 的 schema/分层/计数逐项一致；当前 shadow-10 风险池为 859。26 项开发风险真值为 TP/FP/FN/TN=10/4/0/5，7 项歧义排除不计指标。
- 新增的 360 项独立分层压力门禁通过：开发集 hash 交集 0，三份先冻结抽样清单分别为 178/102/80 项，真值为 142 正/64 负/154 歧义；当前接受条件 precision=0.6893，recall=NA。0.82–0.90 阈值扫描未发现同时满足 precision 和正例保留率的参数。
- v1 决策均衡集 450 项在参与调整后降级为开发集，shadow-11 为 TP/FP/FN/TN=59/0/22/53。真正 untouched 的 v2 共 429 项，80 正/47 负/302 歧义，TP/FP/FN/TN=58/0/22/47，precision=1.0000、recall=0.7250；未达到正式重跑 recall≥0.90 门禁。
- untouched v3 共 450 项，140 正/156 负/154 歧义。shadow-11 为 TP/FP/FN/TN=110/5/30/151，precision=0.9565、recall=0.7857；shadow-12 试验为 114/3/26/153、0.9744/0.8143，仍未通过误合并为 0 和 recall≥0.90，代码已撤回。
- 重新执行 2,000 条 BGE-M3 校准通过：637 个当前兼容真值中 635 个参与指标，选定策略 `ACCEPT/PROVISIONAL/REJECT=614/19/2`，TP/FP/FN=614/0/21；60 个高相似审阅组中 58 个确认负例全部硬拒绝，边界误合并 0。
- 四组既有回溯冻结审计继续通过：missing-action pair TP/FP/FN/TN=11/0/1/3，missing-field 唯一确认负例 true reject，原后校准开发回归 TP/FP/FN/TN=8/0/0/4，真实 errorSignature 冲突样本仍为 0。
- 应用身份治理批次具有写入能力，本轮为遵守不写业务库约束未执行。
- 标准 `:backend:test --no-daemon`：在运行测试前被仓库既有测试源码断裂阻塞；本次复核包括飞书认证、Jira 和 SDK 旧接口/构造参数错误。本任务未修改这些文件，也未修改并发的附件/MinIO 代码。
- 文档所述 `tools/dev-check.sh --static/--quick` 在当前候选不存在，无法执行；不能声明治理总门禁通过。
- 原 595 个未标注兼容 pair 已全部解决，但仅代表 17 个独立表述组。shadow-11 在 untouched v2 中没有误接受 47 个确认负例，但漏掉 22 个同义正例；独立增量支持渠道样本仍为 0。因此不调整 0.82/0.68/0.20，不宣称生产误合并率或 precision 已达标，也不启动正式全量重建。

### 6.1 复现状态说明（2026-09-28 补记）

本节以上各项回放数字来自当时持有历史数据的 `feedback_platform_local_deploy`。**该库当前已不含那批历史数据**，
因此上面 100 条 / 500 条 / 2,000 条校准和独立增量 discovery 那几组数字**无法在本机原样重跑**——
它们记录的是当时的结果，不是今天可复现的结果。

不过，**回放链路本身可以脱离原始历史数据复现**。仓库自带一套确定性合成跨渠道夹具
`data/test-fixtures/full-system-20260805/`（1,180 条，`synthetic_data_only: true`，
每个文件带官方 sha256），且 `t_feedback_source` 上的 `chk_source_fixture_tag` 约束会强制
`TEST`/`VERIFICATION` 行带非空 `fixture_tag`，使夹具数据在 schema 层面就被标记、不会被误当生产数据。
2026-09-28 用该夹具向 `feedback_platform_local_deploy` 灌入 753 条
（`t_feedback_source` / `t_feedback_content` / `t_feedback_primary_problem_fact` 各 753，
渠道表 JIRA 293 / QUALITY 239 / REDBLACK 221），显式开启 `CLUSTER_REAL_SHADOW_TEST_ENABLED=true`
重跑真库只读回放，得到：

| 指标 | 首批 | 扩展 |
|---|---:|---:|
| 有效输入 | 100 | 500（5 批）|
| 当前 MAIN 去重簇数 | 0 | 0 |
| v3 建议业务簇数 | 7 | 7 |
| 终态聚簇 | 51 | 252 |
| 合并 pair | 195 | 5,303 |
| 拆分 pair | 0 | 0 |

| `statusCounts` | 首批 | 扩展 |
|---|---:|---:|
| `ACTIONABLE` | 49 | 248 |
| `NON_FAULT_INPUT` | 31 | 152 |
| `NOVEL_SYMPTOM` | 20 | 93 |
| `CONTENT_REVIEW_NEEDED` | 0 | 6 |
| `NOISE` | 0 | 1 |

**这批数字只证明链路在真库上跑得通，不是效果指标**，引用时必须带上两条限定：

1. **`当前 MAIN 去重簇数 = 0`**，因为灌入的只有来源/内容/主问题事实表，`t_cluster` 为空。
   这是**冷启动回放**，不是「v3 建议 vs 既有生产簇集」的差分回放——正是这个差别导致它
   与 3.1 节那组（当前簇 25、建议 34、`ACTIONABLE` 50）不可直接比较。
2. **`合并 pair` 是合并对的数量，不是准确率**；`拆分 pair = 0` 是冷启动下无既有簇可拆，
   不代表拆分能力。两者都不能当质量结论讲。

夹具为合成数据，本任务仍未在真实人工真值上完成校准；7 节的更新门禁与「未切换 ACTIVE」结论不变。

## 7. 回滚与开关

当前 v3 未注入 `ClusteringEngine`、未绑定调度器；沿用只读影子总开关 `app.cluster.classification-v2-shadow-enabled=false`，语义开关 `app.cluster.semantic-shadow-enabled=false` 也默认关闭。hashing provider 不可作为正式语义判定器；关闭语义开关即回退确定性规则召回。运行态无数据回滚，因为 ACTIVE generation、MAIN、Cluster、派生索引和数据库 schema 均未变化。

正式更新门禁保持为：至少 100 条可判定且 untouched 的人工真值历史样本、跨应用污染 0、低信息 MAIN 0、P0 误合并 0、pairwise precision 不低于 0.95、recall 不低于 0.90、P95 相对现状不恶化超过 20%、两次相同快照重跑结果逐条一致。v3 有 296 条可判定样本，但 shadow-11 为 5 FP/recall 0.7857，shadow-12 试验仍为 3 FP/recall 0.8143，门禁未通过。下一步必须取得与当前库存隔离的新增真值后再做窄修正；通过后才可先做可回滚的非 ACTIVE generation 全量演练和差异审计，再由明确审批切换 ACTIVE。真实回放测试只能在安全注入数据库配置后，显式设置 `CLUSTER_REAL_SHADOW_TEST_ENABLED=true` 运行；该测试自身禁用调度和 Flyway，并要求只读连接池。
