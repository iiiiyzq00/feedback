package com.feedback.config

import com.feedback.service.cluster.CandidateRerankDocuments
import com.feedback.service.cluster.LlmCandidateReranker
import com.feedback.service.sync.GuardedReranker
import com.feedback.service.sync.HybridCandidateRetriever
import com.feedback.service.sync.Reranker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 重排的**装配结果**，在真实 Spring 上下文里测一次。
 *
 * ## 为什么这件事不能用单元测试代替
 *
 * `GuardedRerankerTest` 验的是「守卫能拦住乱来的实现」，`LlmCandidateRerankerTest`
 * 验的是「本类会产出什么」——两者都手工 `new` 对象，因此都**不碰装配**。
 * 而本改造新加的三样东西（`RerankProperties`、`ShadowSafeCandidateRerankDocuments`、
 * `LlmCandidateReranker`）全部靠构造型注解进容器，装配失败的形态是
 * 「上下文起不来」或者「`HybridCandidateRetriever` 悄悄拿到默认的 `Reranker.NONE`」。
 * 后者尤其危险：**所有单元测试仍然是绿的，只是重排永远不生效**。
 *
 * 所以这里断言的是三件只有真上下文才能回答的事：
 *
 * 1. `Reranker` 这个类型在容器里**恰好一个**实现——多个会让注入变成歧义，
 *    零个则说明生产装配根本没发生。这一条同时排除了 `GuardedReranker` 被误注册成 Bean
 *    的可能（它是被 `HybridCandidateRetriever` 在构造时套上的，不是容器里的一个组件）。
 * 2. 拿到的是 [LlmCandidateReranker] 而不是别的什么。
 * 3. `HybridCandidateRetriever` 内部持有的确实是**套过守卫的**那个，
 *    而不是直接持有实现。这一条用反射读私有字段——它问的本来就是
 *    「Spring 把谁注到了哪里」，而那个位置没有别的观测面。
 *
 * ## 为什么在 `rcaEntryTest` 源集里
 *
 * 本仓唯一会启动完整上下文的地方就是这里（`@SpringBootTest` 且不覆盖数据源，
 * 靠 `init-datasource.gradle.kts` 连本机 3307）。放进 `src/test` 会让每次
 * `:backend:test` 都要求一个活着的 MySQL——那会改变那个任务的契约，
 * 而它现在是不依赖数据库的。所以本类跟着已有的上下文启动套件走，
 * 门控与它们一致（`RCA_ENTRY_TEST_ENABLED`）。
 *
 * ## 默认必须是不生效
 *
 * 最后一条断言盯的是**默认姿态**：没有显式配置时 `enabled` 必须是 false。
 * 这条一旦被谁改成默认 true，归簇链路上就会凭空多出一次同步模型调用，
 * 而那正是本次改造里最需要人先确认代价的一件事。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        // 本类只读上下文里的装配关系，不碰表。关掉 Flyway 让它可以跑在一个
        // 已经迁移过的库上而不产生任何副作用。
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        // RCA 两侧都关：本类不验 RCA，而 worker 打开会要求一个非空的 worker-id，
        // 那会把「上下文起不起得来」与「RCA 配置对不对」两件事混在一起。
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false",
        // 与持久化契约测试同一理由：万一将来有人在这里加了需要模型的断言，
        // 应当是显式失败，而不是悄悄用本机密钥发起一次真实调用。
        "app.ai.llm.api-key=",
        "app.ai.llm.model=",
        // **本类必须覆盖这一项才能在本机启动。**
        // 本类要断言的正是 `HybridCandidateRetriever` 被注入了什么，所以必须真的把它建出来；
        // 而它的构造依赖 `EmbeddingProvider`。默认的 `onnx-bge-m3` 会在类初始化时加载
        // DJL 的 `libtokenizers.so`，那个 .so 要求 GLIBC_2.32，本机是 2.31，直接
        // UnsatisfiedLinkError 把上下文炸掉（见 memory: 本机 glibc 太旧跑不了 DJL 分词）。
        // 换成 `hashing` 是**测试专用的替身**：本类不验向量质量，只验装配关系。
        // `ClusteringMysqlIntegrationTest` 出于同一原因做了同样的事。
        "app.ai.embedding.provider=hashing"
        // **刻意不设 app.ai.rerank.enabled**：`未显式配置时重排默认不生效` 那条断言
        // 测的就是「什么都不设」时的姿态。在这里写一个 false 会让它变成同义反复。
    ]
)
class RerankWiringContractTest {

    @Autowired
    lateinit var rerankers: List<Reranker>

    @Autowired
    lateinit var properties: RerankProperties

    @Autowired
    lateinit var documents: CandidateRerankDocuments

    @Autowired
    lateinit var retriever: HybridCandidateRetriever

    @Test
    fun `容器里恰好一个重排实现且就是 LLM 重排器`() {
        assertEquals(
            1, rerankers.size,
            "Reranker 在容器里有 ${rerankers.size} 个实现：" +
                "多个会让 HybridCandidateRetriever 的构造注入变成歧义，零个说明装配没发生"
        )
        assertInstanceOf(
            LlmCandidateReranker::class.java, rerankers[0],
            "容器里的 Reranker 不是 LlmCandidateReranker：生产装配换成了别的实现"
        )
    }

    @Test
    fun `候选正文端口接的是脱敏安全实现`() {
        // 这个端口存在的**唯一**理由就是「只能走 loadShadowSafe」。
        // 若哪天真有人换成一个走 load() 的实现，未脱敏正文会被送进模型，
        // 而那条路径在结构上完全合法、所有单元测试也都会通过。
        assertEquals(
            "ShadowSafeCandidateRerankDocuments", documents.javaClass.simpleName,
            "候选正文端口被换成了 ${documents.javaClass.simpleName}：脱敏边界可能已经不在链路上"
        )
    }

    /**
     * 反射读 `HybridCandidateRetriever.reranker`。
     *
     * 断言的是「**本类**套的守卫」而不是「Spring 套的」：守卫在构造函数里施加，
     * 所以装配者无法绕过。若哪天守卫被移到装配层，这条会变红——
     * 那正是需要有人重新审视「谁负责保证集合不被改」的时刻。
     */
    @Test
    fun `召回器内部持有的是套过守卫的重排器`() {
        val field = HybridCandidateRetriever::class.java.getDeclaredField("reranker")
        field.isAccessible = true

        assertInstanceOf(
            GuardedReranker::class.java, field.get(retriever),
            "召回器直接持有了未套守卫的重排器：集合的完整性不再由本类保证"
        )
    }

    @Test
    fun `未显式配置时重排默认不生效`() {
        assertFalse(properties.enabled, "默认姿态被改成了生效：归簇链路会凭空多出一次同步模型调用")
        assertTrue(
            properties.readinessErrors().contains("AI_RERANK_DISABLED"),
            "关闭状态没有被报成缺口，启动日志会失去分辨能力：${properties.readinessErrors()}"
        )
        // 生效窗口在关闭状态也必须是可算的：日志要打它，而日志不能因为开关关着就少一个字段。
        assertEquals(20, properties.effectiveWindow())
        assertEquals(400, properties.effectiveMaxDocumentChars())
    }

    companion object {
        /**
         * 与 `rcaEntryTest` 里既有的五个上下文启动套件逐字相同的三行。
         *
         * **不补数据源地址**：它必须来自 `DB_URL`（由 `init-datasource.gradle.kts`
         * 从 600 权限文件读入）。在这里给一个 `localhost:3306` 的兜底会让本类在没有
         * init 脚本时静默连上别的项目的库——缺 `DB_URL` 时本类应当**起不来**，
         * 而不是连错库。
         *
         * 前两个密钥是**测试常量**（与 `src/test` 下既有 MySQL 集成测试同值），
         * 不是任何环境在用的凭据。
         */
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.masking.fingerprint-secret") { "test-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "test-contact-secret-at-least-32-characters" }
            registry.add("dubbo.enabled") { "false" }
        }
    }
}
