package com.feedback.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 候选重排（`app.ai.rerank`）的开关与两个成本旋钮。
 *
 * ## 为什么默认关闭，以及关闭时也必须有这一层
 *
 * 开启意味着**每次归簇召回多一次同步模型调用**，且这次调用发生在
 * `ClusteringEngine.cluster` 的数据库事务持有期间。默认关闭不是保守：
 * 是在没有实测过这个延迟代价可接受之前，不该让归簇路径多一个外部依赖。
 *
 * 但配置类本身**必须存在且随时可读**，理由与 `Reranker.NONE` 相同——
 * 「没有打开」与「没有实现」必须在日志里分辨得出来。
 * 所以启动日志无论开关如何都会打印本行。
 *
 * ## 两个旋钮的边界不是随手写的
 *
 * - `window` 的上界 200 来自 `HybridCandidateRetriever` 的融合结果规模：
 *   两个引擎各取 `fetchSize = (topK*2).coerceAtMost(100)` 条，去重后最多 200 条。
 *   窗口设得比这更大不会有任何效果，所以宁可判它配错，也不让一个无效配置静默通过。
 * - `maxDocumentChars` 直接决定输入 token。窗口 20 条 × 400 字符，是当前口径下
 *   一次调用输入量的主要来源；调它等于调钱。
 */
@Component
@ConfigurationProperties(prefix = "app.ai.rerank")
class RerankProperties {

    /** 总开关。关闭时 `LlmCandidateReranker` 直接返回入参，不产生任何调用。 */
    var enabled: Boolean = false

    /** 进入模型重排的头部候选条数。见类注释里的 200 上界。 */
    var window: Int = 20

    /** 单条候选正文进上下文的上限（字符）。 */
    var maxDocumentChars: Int = 400

    /**
     * 与 `LlmClient` 同源的模型名，只用于日志。
     *
     * 从 `app.ai.llm.model` 读而不是在这里另设一个键：重排与分类必须打同一个模型，
     * 否则日志会出现两个不同的模型名，而真正计费的那个只有一个。
     */
    @Value("\${app.ai.llm.model:}")
    var configuredModel: String = ""

    /**
     * 与 `LlmClient` 同源的超时。**只读，不参与调用**——真实超时由
     * `OpenAiCompatibleLlmClient` 施加。读它是为了把「事务最坏持有多久」写进启动日志：
     * 这个数字只有把开关和超时放在一起看才有意义。
     */
    @Value("\${app.ai.llm.timeout-seconds:120}")
    var llmTimeoutSeconds: Long = 120

    /**
     * 返回开闸前还缺什么，内容只包含稳定错误码。
     *
     * 刻意**不检查模型连通性**：那需要一次真实调用，而配置类不该在启动时产生费用。
     * 「模型配没配」用 `configuredModel` 是否为空判断，这是一个本地事实。
     */
    fun readinessErrors(): List<String> = buildList {
        if (!enabled) add("AI_RERANK_DISABLED")
        if (window !in MIN_WINDOW..MAX_WINDOW) add("AI_RERANK_WINDOW_INVALID")
        if (maxDocumentChars !in 1..MAX_DOCUMENT_CHARS_LIMIT) add("AI_RERANK_MAX_DOCUMENT_CHARS_INVALID")
        // 只在开启时才要求模型名：关闭状态下模型为空是正常部署形态
        // （分类链路也可能是关的），不该在启动日志里报一个不存在的缺口。
        if (enabled && configuredModel.isBlank()) add("AI_RERANK_LLM_MODEL_MISSING")
    }

    /** 是否满足「重排真正会生效」的全部条件。关闭时恒为 false，这是刻意的。 */
    fun ready() = readinessErrors().isEmpty()

    /**
     * 生效窗口。越界值**夹紧而不是抛异常**：重排整条链路的口径是「绝不因为自己让归簇失败」，
     * 一个配错的窗口应该退化成一个小窗口并留下日志，而不是让每次召回都炸。
     * 判它配错的地方是 [readinessErrors]，那是给人看的，不是给运行路径看的。
     */
    fun effectiveWindow(): Int = window.coerceIn(MIN_WINDOW, MAX_WINDOW)

    /** 生效正文上限，越界时夹紧，理由同 [effectiveWindow]。 */
    fun effectiveMaxDocumentChars(): Int = maxDocumentChars.coerceIn(1, MAX_DOCUMENT_CHARS_LIMIT)

    /**
     * 启动时记录就绪状态与**锁内模型调用的最坏时长**。
     *
     * `llmTimeoutSeconds` 出现在这一行是刻意的：只看 `enabled=true` 无法判断代价，
     * 而代价恰恰是本改造最需要被知道的一件事。日志不含任何密钥。
     */
    @PostConstruct
    fun logReadiness() {
        logger.info(
            "Candidate rerank configuration: enabled={}, ready={}, window={}, maxDocumentChars={}, "
                + "model={}, llmTimeoutSeconds={}, errors={}",
            enabled, ready(), effectiveWindow(), effectiveMaxDocumentChars(),
            configuredModel.ifBlank { "UNCONFIGURED" }, llmTimeoutSeconds, readinessErrors()
        )
    }

    companion object {
        /** 少于两条候选没有顺序可言，所以下界是 2 而不是 1。 */
        private const val MIN_WINDOW = 2

        /** 融合结果的上界：`(topK*2).coerceAtMost(100)` × 两个引擎。 */
        private const val MAX_WINDOW = 200

        private const val MAX_DOCUMENT_CHARS_LIMIT = 10_000

        private val logger = LoggerFactory.getLogger(RerankProperties::class.java)
    }
}
