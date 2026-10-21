package com.feedback.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * [RerankProperties] 与 `application.yml` 必须对得上。
 *
 * ## 为什么值得为两个旋钮写一个绑定测试
 *
 * 三个字段全是**静默失败**的形状：
 *
 * - `max-document-chars` 拼错成 `max-doc-chars`：绑定器默认忽略未知键，
 *   字段静默取默认值 400，线上永远按 400 跑而没人知道配置没生效。
 * - `window` 写成字符串或越界：夹紧逻辑会把它变成一个**看起来正常**的小窗口。
 * - `configuredModel` 走的不是绑定而是 `@Value("\${app.ai.llm.model:}")`：
 *   键名拼错时表达式仍然能解析（有默认值），结果是 `UNCONFIGURED` 出现在每一行
 *   重排日志里，而启动不会报任何错。
 *
 * 这三条都不能靠「看一眼 YAML」发现，所以用真正的绑定器读真正的 `application.yml`。
 *
 * ## 为什么不需要 Spring 容器
 *
 * 本类只验「键与字段对不对得上」，验不了「Spring 会不会把它装配进
 * `LlmCandidateReranker`」——那需要完整上下文（要数据库），
 * 属于另一件事。这里刻意不启动容器：绑定错误的代价是**静默**，
 * 而容器起不来是**响亮**的，后者不需要测试来发现。
 */
class RerankPropertiesTest {

    // ---------------------------------------------------------------------------------------
    // 真 YAML × 真绑定器
    // ---------------------------------------------------------------------------------------

    /**
     * 全部环境变量都不设时，绑出来的必须是三个默认值。
     *
     * 断言具体数值而不是「不等于零」：这条用例的价值就在于把
     * `application.yml` 里的字面量与字段名钉在一起，两者任意一边改动都会被它拦下。
     */
    @Test
    fun `application yml 的三个键能绑到字段上且默认值一致`() {
        val environment = environment()

        val bound = binder(environment).bind("app.ai.rerank", RerankProperties::class.java).get()

        assertFalse(bound.enabled, "开关默认值不是 false：重排会在没有实测过延迟代价的情况下先进生产")
        assertEquals(20, bound.window, "app.ai.rerank.window 没有绑上（键名拼写或类型不符）")
        assertEquals(400, bound.maxDocumentChars, "app.ai.rerank.max-document-chars 没有绑上")
    }

    /**
     * `@Value` 用的那两个键必须在同一个文件里存在，且 `timeout-seconds` 与
     * `app.ai.llm.timeout-seconds` 的实际值一致。
     *
     * 这一条防的是「两处各写一个默认值」：`@Value` 写 120、YAML 写 60 时，
     * 启动日志里的「事务最坏持有时长」会是一个与真实超时无关的数字——
     * 而那一行日志的全部用途就是让人相信这个数字。
     */
    @Test
    fun `重排复用的模型名与超时键真实存在`() {
        val environment = environment()

        assertNotNull(environment.getProperty("app.ai.llm.model"), "app.ai.llm.model 不存在：重排日志里的模型名会永远是 UNCONFIGURED")
        assertEquals(
            "120", environment.getProperty("app.ai.llm.timeout-seconds"),
            "app.ai.llm.timeout-seconds 变了：RerankProperties.llmTimeoutSeconds 的 @Value 默认值要跟着改，否则启动日志会报一个假的持有时长"
        )
        // 同一棵子树下不能出现第二个「重排自己的超时」键：一旦有人加一个，
        // 就会有两个数字都声称自己是超时，而真正生效的只有 OpenAiCompatibleLlmClient 那个。
        assertNull(
            environment.getProperty("app.ai.rerank.timeout-seconds"),
            "app.ai.rerank 下出现了 timeout-seconds：重排必须与分类走同一个超时，否则日志与账单会对不上"
        )
    }

    // ---------------------------------------------------------------------------------------
    // 开关与夹紧
    // ---------------------------------------------------------------------------------------

    @Test
    fun `关闭时 ready 恒为 false 且只报一个缺口`() {
        val properties = RerankProperties()

        assertEquals(listOf("AI_RERANK_DISABLED"), properties.readinessErrors())
        assertFalse(properties.ready(), "关闭状态下 ready 为真会让启动日志失去分辨能力")
    }

    @Test
    fun `开启但模型名为空时报缺口`() {
        val properties = RerankProperties().apply { enabled = true; configuredModel = "" }

        assertTrue(properties.readinessErrors().contains("AI_RERANK_LLM_MODEL_MISSING"))
        // 关闭时不报这一条：分类链路也可能是关的，那种部署形态下模型为空是正常的，
        // 在启动日志里报一个不存在的缺口会训练人忽略这行日志。
        assertFalse(
            RerankProperties().readinessErrors().contains("AI_RERANK_LLM_MODEL_MISSING"),
            "关闭状态下仍然报了模型缺口"
        )
    }

    /**
     * 越界值**夹紧而不是抛异常**。
     *
     * 重排整条链路的口径是「绝不因为自己让归簇失败」，所以一个配错的窗口应当退化成
     * 一个可用的窗口。判它配错的地方是 `readinessErrors()`——那是给人看的，
     * 不是给运行路径看的。两条路径都要有：只夹紧不报告，配错就永远没人知道。
     */
    @Test
    fun `越界值被夹紧但 readiness 仍然报缺口`() {
        val tooSmall = RerankProperties().apply { window = 1 }
        val tooLarge = RerankProperties().apply { window = 10_000 }
        val badChars = RerankProperties().apply { maxDocumentChars = 0 }

        assertEquals(2, tooSmall.effectiveWindow(), "下界夹紧失效：一条候选重排没有意义，应当退化成不重排")
        assertEquals(200, tooLarge.effectiveWindow(), "上界夹紧失效：融合结果最多 200 条，超出部分不会有任何效果")
        assertEquals(1, badChars.effectiveMaxDocumentChars(), "正文上限夹到 0 会让模型看到的全是空串")

        listOf(tooSmall, tooLarge).forEach {
            assertTrue(it.readinessErrors().contains("AI_RERANK_WINDOW_INVALID"), "夹紧了却没有报缺口")
        }
        assertTrue(badChars.readinessErrors().contains("AI_RERANK_MAX_DOCUMENT_CHARS_INVALID"))
    }

    // ---------------------------------------------------------------------------------------
    // 装配
    // ---------------------------------------------------------------------------------------

    /**
     * 用真 `application.yml` 建一个环境，**不激活任何 profile**。
     *
     * 因此读到的全部是键里的默认值（`${XXX:默认}` 里的那一半），
     * 也正是部署时什么都不设会拿到的东西——本类要钉住的就是这个形态。
     */
    private fun environment(): StandardEnvironment {
        val sources = YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yml"))
        val environment = StandardEnvironment()
        sources.asReversed().forEach { environment.propertySources.addFirst(it) }
        return environment
    }

    /**
     * 绑定器必须带**占位符解析器**，否则读到的是字面量 `${AI_RERANK_ENABLED:false}`
     * 而不是 `false`——那会让本类变成一条「YAML 里确实写着这几个键」的弱断言，
     * 而真正要验的「默认值解析出来是什么」就漏掉了。
     *
     * 这也是 Spring Boot 自己装配 `@ConfigurationProperties` 时的两件套。
     */
    private fun binder(environment: StandardEnvironment): Binder = Binder(
        ConfigurationPropertySources.get(environment),
        PropertySourcesPlaceholdersResolver(environment)
    )
}
