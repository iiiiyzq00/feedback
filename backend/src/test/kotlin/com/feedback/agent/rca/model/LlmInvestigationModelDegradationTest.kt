package com.feedback.agent.rca.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.MandatoryEvidencePlan
import com.feedback.agent.rca.domain.MandatoryRequirement
import com.feedback.agent.rca.domain.MandatoryRequirementType
import com.feedback.agent.rca.domain.MandatoryCompletion
import com.feedback.agent.rca.domain.ProblemProfile
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.graph.AgentDecision
import com.feedback.agent.rca.graph.EvidenceBrief
import com.feedback.agent.rca.graph.InvestigationPrompt
import com.feedback.repository.ai.AiQuotaRepository
import com.feedback.service.ai.LlmQuotaGuard
import com.feedback.service.ai.OpenAiCompatibleLlmClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * `LlmInvestigationModel` 的三条降级出口。
 *
 * ## 端口契约要求「不许抛异常」
 *
 * `InvestigationModel` 写明：实现方不得用异常表达「模型不可用」，必须返回带
 * [RcaStopReason.HUMAN_REQUIRED] 的停止提案。异常穿透到编排层会丢掉失败语义，
 * 也会让这次调用的预算记账整个消失——于是「模型不可用」在账本上看起来与
 * 「这一轮没有决策」完全一样。所以本类断言的第一件事是**调用没有抛**。
 *
 * ## 三条出口都真跑一遍，用 MockWebServer 而不是打桩 LlmClient
 *
 * 打桩 `LlmClient` 只能验证本类的 catch 分支，验证不了「异常到底长什么样」——
 * 而未配置是 `requireConfigured()` 抛的 `AiTransientException`、超时是 OkHttp 的
 * `SocketTimeoutException` 被 `decideStructured` 包过一层、结构不合规则根本不抛异常
 * （它是一条 200 响应）。三者在真实链路里的形态完全不同，所以本类装配**真实的**
 * [OpenAiCompatibleLlmClient]，只把它的 base-url 指向 MockWebServer。
 *
 * ## 超时这一条没有等满 120 秒
 *
 * 生产超时是 `app.ai.llm.timeout-seconds=120`。等满两分钟既不可能作为回归测试，
 * 也没有额外的信息量——要验的是**超时这条路径**，不是这个数字。
 * 因此本类把超时配成 1 秒、让服务端延迟 5 秒，并额外断言「耗时明显小于服务端延迟」：
 * 这既证明超时真的触发了，也证明结果不是「等延迟跑完后解析一个畸形响应」得来的。
 *
 * ## 放在 `src/test` 而不是 `rcaEntryTest`
 *
 * 本类不碰数据库，因此没有理由挂在需要 3307 的门控任务下——放进常规测试源集，
 * 它才会随每次 `:backend:test` 一起跑，而不是只在手工打开门控时跑。
 */
class LlmInvestigationModelDegradationTest {

    private val json = ObjectMapper()

    // ---------------------------------------------------------------------------------------
    // 出口一：未配置
    // ---------------------------------------------------------------------------------------

    @Test
    fun `未配置密钥时转人工，且一次网络请求都没有发出`() {
        MockWebServer().use { server ->
            server.start()
            val model = LlmInvestigationModel(
                llm = client(baseUrl = server.url("/").toString(), apiKey = ""),
                configuredModel = ""
            )

            val decision = model.decide(prompt())

            assertHumanRequired(decision)
            assertTrue(
                decision.reasoning.contains("LLM_NOT_CONFIGURED"),
                "理由码没有带出来，事后无法把「没配模型」与「模型挂了」区分开：${decision.reasoning}"
            )
            // 模型名未配置时落库值必须是 UNCONFIGURED 而不是空串：
            // 空串在 t_rca_agent_task.model_version 上看起来像「这一列没写」。
            assertEquals("UNCONFIGURED", decision.modelVersion, "未配置模型时没有落一个可辨认的哨兵值")
            // 未配置必须在本地短路。真发出去的话会是一个没有 Authorization 的请求，
            // 上游回 401，于是「没配」被记成「上游拒绝」。
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS), "未配置密钥却仍然发了请求")
        }
    }

    // ---------------------------------------------------------------------------------------
    // 出口二：超时
    // ---------------------------------------------------------------------------------------

    @Test
    fun `上游超时转人工，且不等上游把延迟走完`() {
        val serverDelaySeconds = 5L
        val timeoutSeconds = 1L

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(openAiResponse("""{"investigate":null,"stop":null}"""))
                    .setBodyDelay(serverDelaySeconds, TimeUnit.SECONDS)
            )
            val model = LlmInvestigationModel(
                llm = client(baseUrl = server.url("/").toString(), timeoutSeconds = timeoutSeconds),
                configuredModel = "test-model"
            )

            val startedAt = System.nanoTime()
            val decision = model.decide(prompt())
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertHumanRequired(decision)
            assertTrue(
                decision.reasoning.contains("LLM_CALL_FAILED"),
                "超时没有被归到调用失败这条理由码上：${decision.reasoning}"
            )
            // 上下界都要卡：下界证明失败**是**超时造成的（不是某个立刻发生的错误，
            // 那种情况耗时会是毫秒级），上界证明没有等上游把延迟走完。
            // 只卡上界的话，一个「连接被立刻拒绝」的失败也能让断言通过。
            assertTrue(
                elapsedMs >= 900,
                "耗时仅 ${elapsedMs}ms：失败发生在超时到点之前，说明它不是超时造成的"
            )
            assertTrue(
                elapsedMs < 2_500,
                "耗时 ${elapsedMs}ms 接近服务端延迟 ${serverDelaySeconds}s：这说明结果是等延迟走完后" +
                    "解析出来的，而不是被超时截断的"
            )
            println("RCA_MODEL_DEGRADATION timeout elapsedMs=$elapsedMs serverDelaySeconds=$serverDelaySeconds timeoutSeconds=$timeoutSeconds")
        }
    }

    // ---------------------------------------------------------------------------------------
    // 出口三：结构不合规
    // ---------------------------------------------------------------------------------------

    /**
     * 两种不合规各跑一次，都是 **HTTP 200**。
     *
     * 这一点是刻意的：上游健康、响应可解析、字段也都在，只是**语义**不合约定。
     * 把它与超时混成一条路径，会让「模型返回了但不可用」这类故障在监控里彻底消失。
     */
    @Test
    fun `模型输出不合约定结构时转人工，且不抛异常`() {
        MockWebServer().use { server ->
            server.start()
            val model = LlmInvestigationModel(
                llm = client(baseUrl = server.url("/").toString()),
                configuredModel = "test-model"
            )

            // (a) investigate 与 stop 都没给；决策必须且只能二选一。
            server.enqueue(MockResponse().setResponseCode(200).setBody(openAiResponse("""{"reasoning":"x","hypothesis_updates":[]}""")))
            // (b) 给了一个 schema 允许之外的原因码；strict 模式下已被上游 schema 拒绝，
            //     但本地解析仍必须自己兜住，不能假设上游一定守约。
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    openAiResponse("""{"reasoning":"x","hypothesis_updates":[],"stop":{"reason":"TOTALLY_MADE_UP","rationale":"r","proposed_confidence":"CANDIDATE","known_gaps":[]}}""")
                )
            )

            repeat(2) { attempt ->
                val decision = model.decide(prompt())
                assertHumanRequired(decision)
                assertTrue(
                    decision.reasoning.contains("LLM_DECISION_SCHEMA_INVALID"),
                    "第 ${attempt + 1} 次的不合规输出没有被归到结构不合规这条理由码上：${decision.reasoning}"
                )
            }

            // 两次都真的发到了上游：断言成立不是因为在本地就短路了。
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS), "第 1 次调用没有到达上游")
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS), "第 2 次调用没有到达上游")
        }
    }

    // ---------------------------------------------------------------------------------------
    // 三条出口共同必须成立的性质
    // ---------------------------------------------------------------------------------------

    /**
     * 降级不得丢掉已知缺口。
     *
     * 缺口是「没查到」，与「查了没有」在结论强度上完全不同。降级路径如果把 `gaps` 清空，
     * 下游（仲裁节点与人工复核）看到的就是一次干净的、什么都没缺的停止。
     * 这条断言是本类里唯一一条与具体出口无关的性质，因此单列。
     */
    @Test
    fun `降级时已知缺口原样带到停止提案上`() {
        val gaps = listOf("兼容开关状态尚未确认", "版本对照缺少对照组")
        MockWebServer().use { server ->
            server.start()
            val model = LlmInvestigationModel(
                llm = client(baseUrl = server.url("/").toString(), apiKey = ""),
                configuredModel = ""
            )

            val decision = model.decide(prompt(gaps = gaps))

            assertEquals(gaps, decision.stop?.knownGaps, "降级路径丢掉了已知缺口")
        }
    }

    // ---------------------------------------------------------------------------------------
    // 装配与夹具
    // ---------------------------------------------------------------------------------------

    /**
     * 装配**真实**的传输层实现，只把 base-url 指向 MockWebServer。
     *
     * 配额守卫给 0 配额：`LlmQuotaGuard.requireAvailable()` 在 `dailyQuota <= 0` 时
     * 直接返回，不会去碰仓储，所以这里那个 mock 永远不会被调用——
     * 用 `relaxed` 只是为了让构造可读，不代表它在参与本测试的判定。
     */
    private fun client(
        baseUrl: String,
        apiKey: String = "test-api-key",
        timeoutSeconds: Long = 30
    ): OpenAiCompatibleLlmClient = OpenAiCompatibleLlmClient(
        mapper = ObjectMapper(),
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = "test-model",
        defaultTimeoutSeconds = timeoutSeconds,
        inputCostPerMillion = BigDecimal.ZERO,
        outputCostPerMillion = BigDecimal.ZERO,
        meters = SimpleMeterRegistry(),
        quota = LlmQuotaGuard(mockk<AiQuotaRepository>(relaxed = true), dailyQuota = 0L),
        severityPolicy = null,
        runtimeConfig = null
    )

    /** 造一个 `/responses` 的 200 响应体；`output_text` 里放结构化结果。 */
    private fun openAiResponse(structured: String): String = json.writeValueAsString(
        mapOf(
            "id" to "resp_test",
            "output_text" to structured,
            "usage" to mapOf("input_tokens" to 10, "output_tokens" to 5)
        )
    )

    /**
     * 一次断言降级结果的全部形状。
     *
     * `AgentDecision.init` 自己要求「investigate 与 stop 恰好给一个」，
     * 所以 `stop != null` 同时排除了「两个都给了」。这里再显式断言一次，
     * 是为了让失败信息说清是哪一侧的问题。
     */
    private fun assertHumanRequired(decision: AgentDecision) {
        assertNull(decision.investigate, "降级路径不得给出调查动作")
        assertNotNull(decision.stop, "降级路径必须给出停止提案，而不是什么都不给")
        assertEquals(
            RcaStopReason.HUMAN_REQUIRED,
            decision.stop?.reason,
            "降级必须表达成转人工；给出别的停止原因会让「模型不可用」被当成一个真实结论"
        )
        // 没有模型结论时不能给出比最低档更强的东西：这个字段会被下游用作先验。
        assertEquals(
            HypothesisConfidence.CANDIDATE,
            decision.stop?.proposedConfidence,
            "降级路径给出了高于最低档的置信等级"
        )
        assertNull(decision.stop?.leadingHypothesisCode, "降级路径不得指定主导假设")
        assertTrue(decision.hypothesisUpdates.isEmpty(), "降级路径不得修改假设")
    }

    /**
     * 调查上下文夹具。
     *
     * 刻意取 `hasValidLogId = false`：本平台没有任何 log_id / trace_id 列，
     * 真实事实层给的就是这个值（见 `RcaEpisodeFactsRepository`）。
     * 夹具照抄真实形态，才能保证「降级是在真实输入上发生的」而不是在一个不可能出现的输入上。
     */
    private fun prompt(gaps: List<String> = emptyList()): InvestigationPrompt {
        val occurredFrom = LocalDateTime.of(2026, 9, 28, 10, 0)
        val occurredTo = LocalDateTime.of(2026, 9, 28, 10, 30)
        return InvestigationPrompt(
            problemProfile = ProblemProfile(
                tenantKey = "DEFAULT",
                appId = "app-store",
                problemKey = "RESOURCE_LOAD_FAILED",
                episodeGeneration = 1,
                errorSignature = "resource_load_failed",
                occurredFrom = occurredFrom,
                occurredTo = occurredTo,
                distinctDeviceCount = 12,
                distinctUserCount = 3,
                repeatCount = 4,
                crashOrAnr = false,
                hasValidLogId = false,
                logId = null,
                clusterMemberCount = 1,
                clusterId = null,
                affectedVersions = listOf("4.1.4"),
                controlVersion = null,
                symptomSummary = "课程视频点击后黑屏，重进恢复"
            ),
            mandatoryPlan = MandatoryEvidencePlan(
                listOf(
                    MandatoryRequirement(
                        code = "CRASH_STACK",
                        type = MandatoryRequirementType.CRASH_STACK,
                        triggerRule = "现象含 Crash/ANR",
                        requiredReason = "崩溃类现象必须有堆栈才能定位",
                        completion = MandatoryCompletion.PENDING
                    )
                )
            ),
            hypotheses = listOf(
                Hypothesis(
                    code = "H1",
                    statement = "资源加载超时",
                    confidence = HypothesisConfidence.CANDIDATE
                )
            ),
            evidenceBriefs = listOf(
                EvidenceBrief(
                    evidenceId = "evt-1",
                    type = "RELEASE_CHANGE",
                    coverageType = "EPISODE",
                    coverageScope = "episode:1",
                    summary = "4.1.4 于 10:05 发布资源包",
                    cohortComplete = false
                )
            ),
            gaps = gaps,
            budget = GapsAndBudget(
                stepBudget = 12,
                stepsUsed = 1,
                tokenBudget = 60_000,
                tokensUsed = 1_200,
                costBudgetUsd = BigDecimal("0.5"),
                costUsedUsd = BigDecimal("0.01"),
                deadlineAt = occurredTo.plusHours(1),
                now = occurredTo,
                knownGaps = gaps
            ),
            round = 2,
            attemptedReuseKeys = emptyList()
        )
    }
}
