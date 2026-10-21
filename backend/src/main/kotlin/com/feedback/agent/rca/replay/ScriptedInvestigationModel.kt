package com.feedback.agent.rca.replay

import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.StopProposal
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.graph.AgentDecision
import com.feedback.agent.rca.graph.HypothesisUpdate
import com.feedback.agent.rca.graph.HypothesisUpdateAction
import com.feedback.agent.rca.graph.InvestigateAction
import com.feedback.agent.rca.graph.InvestigationModel
import com.feedback.agent.rca.graph.InvestigationPrompt
import com.feedback.agent.rca.tools.RcaCanonicalDigest
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

/**
 * 脚本化的调查模型：按固定剧本走完 414 场景的取证步骤，不调用任何真实模型。
 *
 * 为什么要脚本而不是录一份真实模型的轨迹：固定回放要回答的是
 * 「改了编排逻辑之后，取证路径与结论是否变化」。如果剧本本身来自会漂移的模型，
 * 两次运行的差异就分不清是编排改动造成的还是模型随机性造成的。所以剧本写死在代码里，
 * 每一步的工具、参数、检验意图都可审计。
 *
 * **它替换掉的只是「模型」这一层**，工具层、守卫、假设更新、停止判定、仲裁全部是真实代码。
 * 这也是回放能当回归测试用的原因：被替换的部分是唯一不确定的部分。
 *
 * 剧本推进的判据是**调用历史**，不是轮次计数器。每一步先算出「如果现在发起这次调用，
 * 参数摘要会是多少」，再看历史上有没有它。用轮次计数做判据的话，
 * 编排一旦在某一步之后回退补证，后面每一步都会错位一格，于是重复取证或跳过取证。
 */
class ScriptedInvestigationModel(
    /** 取证时间窗。它是回放的输入条件，改这里就可以换一个窗口重跑，不必改剧本。 */
    private val window: Window = Window()
) : InvestigationModel {

    override val modelVersion: String = MODEL_VERSION

    override val promptVersion: String = PROMPT_VERSION

    override fun decide(prompt: InvestigationPrompt): AgentDecision {
        val pending = ROUNDS.firstOrNull { !alreadyAttempted(it, prompt) }
            ?: return stopDecision(prompt)
        return pending.toDecision()
    }

    // -----------------------------------------------------------------------
    // 剧本推进
    // -----------------------------------------------------------------------

    /**
     * 这一步是否已经发起过。
     *
     * 判据是「工具 + 参数摘要」是否已出现在调用历史里。连参数摘要一起比而不是只比工具名：
     * 同一类工具可能被合法地调用多次（例如换时间窗重查日志），
     * 只比工具名会让第二次合法调用被误判成已完成。
     */
    private fun alreadyAttempted(round: ScriptedRound, prompt: InvestigationPrompt): Boolean =
        prompt.attemptedReuseKeys.contains(round.reuseKey())

    /**
     * 这一步发起后，编排器会为它算出的去重键。
     *
     * 与 `AgentInvestigateNode` 构造参数的方式必须完全一致：编排器会把检验意图并进参数
     * 再算摘要，所以这里也必须并进去。两处不一致会让「已发起过」永远判 false，
     * 剧本就会在同一轮里无限重放。
     */
    private fun ScriptedRound.reuseKey(): String = "${tool.storageCode}@" + RcaCanonicalDigest.ofParams(
        params + (ToolRequest.PARAM_TESTS_HYPOTHESIS to testsHypothesis)
    )

    private fun ScriptedRound.toDecision(): AgentDecision = AgentDecision(
        investigate = InvestigateAction(
            testsHypothesis = testsHypothesis,
            toolRequest = ToolRequest(
                tool = tool,
                params = params,
                // 轮次由编排器按调用历史推导后覆盖，这里是占位值。
                // 参数摘要与轮次无关，所以占位不会影响去重判据。
                decisionRound = 0,
                selectionReason = reasoning,
                paramsDigest = ""
            )
        ),
        reasoning = reasoning,
        hypothesisUpdates = hypothesisUpdates,
        modelVersion = modelVersion,
        promptVersion = promptVersion,
        inputTokens = SIMULATED_INPUT_TOKENS,
        outputTokens = SIMULATED_OUTPUT_TOKENS,
        costUsd = BigDecimal.ZERO
    )

    /**
     * 提交停止提案。
     *
     * 提案只是**建议**：能否停由 [com.feedback.agent.rca.graph.StopEvaluator] 四项检查裁决，
     * 仲裁节点还会再做四项检查。这里给出的是「按当前证据我认为可以停了」，不是「停」这个动作。
     */
    private fun stopDecision(prompt: InvestigationPrompt): AgentDecision = AgentDecision(
        stop = StopProposal(
            reason = RcaStopReason.EVIDENCE_SUFFICIENT,
            rationale = "首错点、机制链、影响范围三类证据齐备：" +
                "日志给出资源鉴权服务 HTTP 400，发布记录给出参数改名且未开兼容开关，" +
                "版本对照给出分母与对照组。",
            proposedConfidence = HypothesisConfidence.STRONG,
            leadingHypothesisCode = AUTH_CONTRACT_CODE,
            knownGaps = listOf(
                // 缺口必须显式列出来，否则「没查」会被读成「查了没问题」。
                // 这两条都不影响当前结论成立，所以不构成继续取证的理由——
                // 但它们决定人工接手时先看什么，所以必须写在提案里。
                "服务端是否保留旧参数名兼容未取证：变更明细只给出客户端侧的 compatSwitch=false，" +
                    "服务端侧的兼容策略没有对应证据",
                "内容状态未取证：本 Episode 的事实里没有资源 token，无法确认资源可见性；" +
                    "这一条只影响能否一并排除内容侧原因"
            )
        ),
        reasoning = "已入账 ${prompt.evidenceBriefs.size} 条证据，" +
            "剩余缺口不影响当前结论成立，提交停止提案交由编排器裁决。",
        modelVersion = modelVersion,
        promptVersion = promptVersion,
        inputTokens = SIMULATED_INPUT_TOKENS,
        outputTokens = SIMULATED_OUTPUT_TOKENS,
        costUsd = BigDecimal.ZERO
    )

    // -----------------------------------------------------------------------
    // 剧本
    // -----------------------------------------------------------------------

    /**
     * 取证顺序不是随意的，每一步都在回答上一步留下的问题：
     *
     * 1. **日志**：现象是什么、最早在哪失败。它同时给出「失败发生在服务端」这个方向。
     * 2. **崩溃聚合**：关掉客户端缺陷这个方向。黑屏最容易被归因到客户端自己，
     *    所以必须先把这个方向排除掉，否则后面所有推理都会被它牵着走。
     * 3. **发布变更**：解释「为什么服务端会拒绝」。日志只说失败在哪，不说为什么失败；
     *    机制链必须由发布记录给出，否则「参数不兼容」就只是猜测。
     * 4. **服务健康**：探索性取证，不指向任何假设。它回答「是不是服务整体挂了」，
     *    结论是「有错误但没有异常窗口」——不能作为归因依据（它按服务组织，覆盖不到本对象），
     *    但它是解释「为什么失败是有选择的」的依据。
     * 5. **版本对照**：给出分母与对照组。前几步都只能证明**这一个** Episode，
     *    要讲「集中在新版本」必须有队列级证据——这正是失败复盘一封堵的那条路。
     */
    private fun scriptRounds(): List<ScriptedRound> {
        val appId = ReplayScenario.APP_ID
        return listOf(
            // 第 1 步：日志 Trace。强制证据计划已经按合法日志 ID 查过一次，
            // 但那次调用没有声明检验意图，因此它不会把证据挂到任何假设上。
            // 这里重新查一次并声明「检验 H2」：代价是多一次调用，
            // 收益是首错点这条最关键的现场证据真正进入了结论的依据集合。
            ScriptedRound(
                tool = RcaToolName.LOG_TRACE,
                params = mapOf(
                    "logId" to ReplayScenario.LOG_ID,
                    "windowFrom" to window.windowFrom,
                    "windowTo" to window.windowTo
                ),
                testsHypothesis = AUTH_CONTRACT_CODE,
                reasoning = "按合法日志 ID 取首错点：先确认现象发生在链路哪一段，再谈原因。" +
                    "声明检验 H2，因为这条证据的作用是判断失败是否发生在资源鉴权环节。",
                hypothesisUpdates = listOf(
                    HypothesisUpdate(
                        code = CLIENT_DECODE_CODE,
                        statement = "客户端播放器解码缺陷导致黑屏",
                        action = HypothesisUpdateAction.PROPOSE,
                        mechanismNote = "播放器拿到地址后解码失败，链路其余环节正常"
                    ),
                    HypothesisUpdate(
                        code = AUTH_CONTRACT_CODE,
                        statement = "资源鉴权服务参数改名不兼容导致鉴权失败，失败集中在新版本",
                        action = HypothesisUpdateAction.PROPOSE,
                        mechanismNote = "发布把鉴权参数改名且未开兼容开关，旧参数名请求被拒，" +
                            "客户端拿不到播放地址，播放器初始化失败后黑屏"
                    )
                )
            ),

            // 第 2 步：崩溃聚合。声明检验 H1，结果却是「该应用窗口内没有崩溃记录」——
            // 一条合法空结果构成的反证。H1 因此被标 REFUTED 并保留记录，而不是被删掉：
            // 「我们排除了纯客户端缺陷」是结论文档的一部分。
            ScriptedRound(
                tool = RcaToolName.CRASH_ANR,
                params = mapOf(
                    "appId" to appId,
                    // 不限定版本：限定版本会把「所有版本都不崩」弱化成「某个版本不崩」，
                    // 而这一步要排除的是整个客户端缺陷方向。
                    "windowFrom" to window.windowFrom,
                    "windowTo" to window.windowTo
                ),
                testsHypothesis = CLIENT_DECODE_CODE,
                reasoning = "黑屏最容易被先怀疑到客户端。若真是解码缺陷，崩溃平台应当有对应记录；" +
                    "这一步的目的是把这个方向关掉，而不是为了支持某个结论。"
            ),

            // 第 3 步：发布变更。不限定组件去查，避免确认偏误——
            // 一旦指定 component，就只会看到自己想看到的那个服务的变更。
            ScriptedRound(
                tool = RcaToolName.RELEASE_CHANGE,
                params = mapOf(
                    "appId" to appId,
                    "windowFrom" to window.windowFrom,
                    "windowTo" to window.windowTo
                ),
                testsHypothesis = AUTH_CONTRACT_CODE,
                reasoning = "日志只说失败在哪，不说为什么失败。机制链要由发布记录补齐：" +
                    "窗口内有没有影响接口契约的变更，变更明细里改了什么。"
            ),

            // 第 4 步：服务健康。**探索性取证**：testsHypothesis 为空，表示这一步不指向任何假设。
            // 这个表达是必要的——有些取证是为了看清现象，不是为了检验某个假设；
            // 若强行声明一个假设，工具的支持/反驳回填会把「取到了数据」记成「支持该假设」，
            // 于是「服务整体正常」会被写成「支持服务整体故障」，账本就写反了。
            ScriptedRound(
                tool = RcaToolName.SERVICE_HEALTH,
                params = mapOf(
                    "services" to listOf("resource-auth-service", "video-cdn"),
                    "windowFrom" to window.windowFrom,
                    "windowTo" to window.windowTo
                ),
                testsHypothesis = null,
                reasoning = "排除「服务整体故障」这个方向：若既有错误请求又存在服务级异常窗口，" +
                    "说明是服务本身的问题；若错误是选择性的，方向就要转向这些请求为何不被接受。"
            ),

            // 第 5 步：版本对照。前几步都只能证明这一个 Episode，
            // 这一步才给出分母与对照组，「集中在新版本」这句话才有依据。
            ScriptedRound(
                tool = RcaToolName.VERSION_COHORT,
                params = mapOf(
                    "appId" to appId,
                    "metricKey" to COHORT_METRIC_KEY,
                    // 对照组版本取自 Episode 事实，不由模型挑：
                    // 让下结论的一方自己挑比较对象，集中度结论就不可信了。
                    "controlVersion" to ReplayScenario.GOOD_VERSION,
                    "windowFrom" to window.windowFrom,
                    "windowTo" to window.windowTo
                ),
                testsHypothesis = AUTH_CONTRACT_CODE,
                reasoning = "「失败集中在新版本」是范围性结论，必须有分母和对照组。" +
                    "单条新版本日志支撑不了这句话，这是本系统明确封堵的一条捷径。"
            )
        )
    }

    /** 剧本。构造时确定，运行期不变。 */
    private val ROUNDS: List<ScriptedRound> = scriptRounds()

    /**
     * 剧本中的一步。
     *
     * [testsHypothesis] 为空表示探索性取证：这次调用不指向任何假设。
     * 它直接对应可空的检验意图，这里不再包一层含义相同的概念。
     */
    private data class ScriptedRound(
        val tool: RcaToolName,
        val params: Map<String, Any?>,
        val testsHypothesis: String?,
        val reasoning: String,
        val hypothesisUpdates: List<HypothesisUpdate> = emptyList()
    )

    /**
     * 取证时间窗。
     *
     * 单独成类而不是散在各处写常量，是为了让「换窗口重跑」只需要换一个对象：
     * 时间窗是回放的输入条件，不是剧本的一部分。
     */
    data class Window(
        val beforeMinutes: Long = 30,
        val afterMinutes: Long = 30
    ) {
        val windowFrom: String = ReplayScenario.FAULT_AT.minusMinutes(beforeMinutes).format(TIME_FORMAT)
        val windowTo: String = ReplayScenario.FAULT_AT.plusMinutes(afterMinutes).format(TIME_FORMAT)

        private companion object {
            /** 与 `RcaCanonicalDigest` 的时间精度一致，避免同一窗口因格式不同算出两个摘要。 */
            val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        }
    }

    companion object {
        /**
         * 被排除的假设：客户端播放器自身的解码缺陷。
         *
         * 公开它是因为回放断言要引用同一个编码：断言里再写一遍字面量，
         * 剧本改名之后断言会「继续通过」——它比的是一个已经不存在的假设。
         */
        const val CLIENT_DECODE_CODE: String = "H1-CLIENT-DECODE"

        /** 最终成立的假设：资源鉴权请求参数改名导致不兼容。 */
        const val AUTH_CONTRACT_CODE: String = "H2-AUTH-CONTRACT"

        private const val MODEL_VERSION: String = "replay-scripted/1.0.0"

        private const val PROMPT_VERSION: String = "rca-investigate/replay-1.0.0"

        /** 模拟的 token 消耗量级；只为让预算记账路径被执行，不代表真实用量。 */
        private const val SIMULATED_INPUT_TOKENS: Int = 1_800

        private const val SIMULATED_OUTPUT_TOKENS: Int = 260

        /** 版本对照使用的指标键；与强制证据计划里的口径保持一致。 */
        private const val COHORT_METRIC_KEY: String = "video_play_failure_rate"
    }
}
