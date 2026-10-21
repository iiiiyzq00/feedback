package com.feedback.agent.rca.persistence

import com.feedback.agent.rca.domain.ClaimEvidencePair
import com.feedback.agent.rca.domain.ClaimSupportVerdict
import com.feedback.agent.rca.domain.ClaimType
import com.feedback.agent.rca.domain.EpisodeAttribution
import com.feedback.agent.rca.domain.EpisodeAttributionLevel
import com.feedback.agent.rca.domain.EvidenceCoverage
import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.MandatoryCompletion
import com.feedback.agent.rca.domain.MandatoryRequirement
import com.feedback.agent.rca.domain.MandatoryRequirementType
import com.feedback.agent.rca.domain.PermissionScope
import com.feedback.agent.rca.domain.RcaArbitrationOutcome
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.domain.RootCauseSignature
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.graph.KnowledgeCandidateRequest
import com.feedback.agent.rca.graph.RcaPersistencePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 持久化层的契约验证：六个写方法的幂等语义、库约束的边界行为、以及落库列值与领域对象的一致性。
 *
 * ## 为什么需要独立一个测试，而不是靠执行入口端到端
 *
 * 执行入口那个端到端（[com.feedback.agent.rca.runtime.RcaWorkerEndToEndTest]）跑完后的实测结果是
 * 四张落库目标表**全为 0 行**：五个上游端口降级后图停在 `AGENT_INVESTIGATE`，
 * 根本走不到 `ROOT_CAUSE_ARBITRATE` / `RCA_PERSIST`。也就是说那一轮 e2e 对
 * [MyBatisRcaPersistenceRepositoryImpl] 一行代码都没有覆盖到。
 * 「入口通了」与「落库对了」是两件事，必须各自有证据。
 *
 * ## 整个类在一个会回滚的事务里跑
 *
 * 这一点决定了本测试的形状：
 *
 * 1. **不需要清理**。计划里第 8 步要求清理只能按批次前缀做（`docs/delivery/13-demo-runbook.md:70,72`
 *    禁止无前缀的 DELETE），而回滚让这条约束根本不成立——一行都不会留下。
 * 2. **不需要自己造父行**。`t_rca_evidence` / `t_rca_hypothesis` / `t_rca_mandatory_evidence` /
 *    `t_rca_claim_evidence` 都对 `t_rca_agent_task` 有外键（前四张 `ON DELETE CASCADE`），
 *    `t_feedback_episode_attribution` 还对 `t_feedback_episode` 有外键。
 *    本类因此**借用一行真实任务**的 `(id, episode_id)`，而不是插一行测试任务——
 *    于是本类对数据库的**写入全部经由被测的生产代码路径**，没有一处原始 SQL 写。
 * 3. **可重复运行**。断言的是「存在 / 唯一 / 列值」，不是「新增了几行」；
 *    差值断言在库已有前序数据时会红，那是报告前序状态而不是报告本次行为。
 *
 * ## 不依赖枚举的字面值
 *
 * 落库编码一律取 `storageCode`，断言里也用它。手写字符串会让「枚举与 DDL 的 CHECK 漂移」
 * 表现为测试通过而库拒绝，或者反过来。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false",
        // 本类只碰持久化端口，但仍然清空模型凭据：万一将来有人在这里加了需要模型的断言，
        // 应当是显式失败，而不是悄悄用本机密钥发起一次真实调用。
        "app.ai.llm.api-key=",
        "app.ai.llm.model="
    ]
)
@Transactional
class RcaPersistenceContractTest @Autowired constructor(
    private val port: RcaPersistencePort,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    /**
     * 本次运行的唯一标记。
     *
     * 用在 Incident 标题所依赖的错误签名上：`linkIncident` 是按**标题**找已有 Incident 的，
     * 固定签名在有历史数据的环境里会命中别人开的 Incident，断言随即变成「上一轮碰巧没留下」。
     * 带一个每次随机的标记，本类就只可能命中自己刚建的那一条。
     */
    private val marker: String = "itest-" + UUID.randomUUID().toString().substring(0, 8)

    @Test
    fun `证据与假设按确定性键幂等，且重复写入更新同一行而不是新增`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        // --- 证据：同一个 evidence_id 写两次，第二次内容不同 ---------------------------------
        val evidenceId = "$marker-evt-1"
        port.saveEvidence(taskId, listOf(episodeEvidence(evidenceId, digest = DIGEST_A, revision = 0, summary = "第一次")))
        port.saveEvidence(taskId, listOf(episodeEvidence(evidenceId, digest = DIGEST_B, revision = 2, summary = "第二次")))

        val evidenceRows = rows(
            "SELECT * FROM t_rca_evidence WHERE task_id = ? AND evidence_id = ?",
            taskId, evidenceId
        )
        assertEquals(1, evidenceRows.size, "同一 evidence_id 写两次产生了多行，唯一键没有挡住")
        val evidence = evidenceRows.single()
        assertEquals("第二次", evidence["summary"], "重放时没有更新可变列，账本停在旧水位上")
        assertEquals(DIGEST_B, evidence["content_digest"], "content_digest 没有被后续写入更新")
        assertEquals(2L, (evidence["revision"] as Number).toLong(), "revision 没有被更新")
        // 领域层没有对应字段，实现刻意留空。写死一个值会让「按哪个回源」出现两个答案。
        assertNull(evidence["raw_reference"], "raw_reference 应当恒为 NULL")

        // --- 库约束的两条边界：实现必须在写入前拦掉，而不是让语句报错 -----------------------
        port.saveEvidence(taskId, listOf(episodeEvidence("$marker-evt-bad-digest", digest = "NOT-A-DIGEST")))
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM t_rca_evidence WHERE task_id = ? AND evidence_id = ?", taskId, "$marker-evt-bad-digest"),
            "摘要不是小写 64 位十六进制却被写进去了：chk_rca_evidence_digest 会在别的路径上炸"
        )
        port.saveEvidence(
            taskId,
            listOf(
                episodeEvidence("$marker-evt-cohort-incomplete").copy(
                    coverageType = EvidenceCoverage.COHORT,
                    denominator = null,
                    controlGroup = null
                )
            )
        )
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM t_rca_evidence WHERE task_id = ? AND evidence_id = ?", taskId, "$marker-evt-cohort-incomplete"),
            "缺分母/对照组的 COHORT 证据被写进去了：「故障集中在新版本」这类结论会失去分母"
        )

        // --- 假设：同 code 的新 revision 追加，历史修订保留 ---------------------------------
        port.saveHypotheses(
            taskId,
            listOf(
                hypothesis("H1", revision = 0, statement = "初版"),
                hypothesis("H1", revision = 1, statement = "修正版"),
                hypothesis("H2", revision = 0, mergedIntoCode = "H1")
            )
        )
        val h1Rows = rows(
            "SELECT id, revision, statement, merged_into_id FROM t_rca_hypothesis " +
                "WHERE task_id = ? AND hypothesis_code = 'H1' ORDER BY revision",
            taskId
        )
        assertEquals(2, h1Rows.size, "同一假设的两个 revision 应当各占一行，历史修订不许被覆盖")
        assertEquals("初版", h1Rows[0]["statement"], "旧 revision 被改写了：历史修订必须保留")
        assertEquals("修正版", h1Rows[1]["statement"], "新 revision 的内容没有落库")

        // 合并链接指向**最新** revision：指向旧修订会让复核读到的机制链是过期的。
        val h2 = rows("SELECT merged_into_id FROM t_rca_hypothesis WHERE task_id = ? AND hypothesis_code = 'H2'", taskId).single()
        assertNotNull(h2["merged_into_id"], "合并链接没有回填：被合并方成了孤儿记录")
        assertEquals(
            (h1Rows[1]["id"] as Number).toLong(),
            (h2["merged_into_id"] as Number).toLong(),
            "合并链接指向的不是最新 revision"
        )

        // --- 整段重放：行数不变 -------------------------------------------------------------
        val before = count("SELECT COUNT(*) FROM t_rca_hypothesis WHERE task_id = ?", taskId)
        port.saveHypotheses(
            taskId,
            listOf(
                hypothesis("H1", revision = 0, statement = "初版"),
                hypothesis("H1", revision = 1, statement = "修正版"),
                hypothesis("H2", revision = 0, mergedIntoCode = "H1")
            )
        )
        assertEquals(before, count("SELECT COUNT(*) FROM t_rca_hypothesis WHERE task_id = ?", taskId), "重放写出了新行")
    }

    @Test
    fun `强制证据与引用校验按确定性键幂等，且缺口态保留失败语义`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        val satisfiedCode = "$marker-LOG_TRACE"
        val gapCode = "$marker-CRASH_STACK"
        val waivedCode = "$marker-COHORT_COMPARISON"
        val unbackedCode = "$marker-guard-unbacked"
        val semanticLostCode = "$marker-guard-semantic-lost"

        port.saveMandatoryEvidence(
            taskId,
            listOf(
                requirement(satisfiedCode, MandatoryRequirementType.LOG_TRACE, MandatoryCompletion.SATISFIED)
                    .copy(satisfiedByEvidenceId = "$marker-evt-1"),
                requirement(gapCode, MandatoryRequirementType.CRASH_STACK, MandatoryCompletion.FAILED_GAP)
                    .copy(failureSemantic = ToolResultStatus.UPSTREAM_TIMEOUT),
                requirement(waivedCode, MandatoryRequirementType.COHORT_COMPARISON, MandatoryCompletion.WAIVED)
                    .copy(waiverReason = "本问题只涉及单一版本，无需版本对照"),
                // 两条半成品状态：完成态无回源证据、缺口态无失败语义。
                requirement(unbackedCode, MandatoryRequirementType.LOG_TRACE, MandatoryCompletion.SATISFIED),
                requirement(semanticLostCode, MandatoryRequirementType.CRASH_STACK, MandatoryCompletion.FAILED_GAP)
            )
        )

        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM t_rca_mandatory_evidence WHERE task_id = ? AND requirement_code = ?", taskId, unbackedCode),
            "「已完成但没有回源证据」被写进去了：强制证据项成了无法回源的结论"
        )
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM t_rca_mandatory_evidence WHERE task_id = ? AND requirement_code = ?", taskId, semanticLostCode),
            "「缺口态没有失败语义」被写进去了：失败会被下游读成「查过没问题」"
        )

        val gap = rows(
            "SELECT completion_status, failure_semantic, required_reason, trigger_rule " +
                "FROM t_rca_mandatory_evidence WHERE task_id = ? AND requirement_code = ?",
            taskId, gapCode
        ).single()
        assertEquals(
            ToolResultStatus.UPSTREAM_TIMEOUT.storageCode,
            gap["failure_semantic"],
            "失败语义没有按 storageCode 落库：超时与「确认不存在」的分界线丢了"
        )

        // --- 同一 requirement_code 重放：更新完成状态而不是插第二行 -------------------------
        port.saveMandatoryEvidence(
            taskId,
            listOf(
                requirement(satisfiedCode, MandatoryRequirementType.LOG_TRACE, MandatoryCompletion.SATISFIED)
                    .copy(satisfiedByEvidenceId = "$marker-evt-1"),
                requirement(gapCode, MandatoryRequirementType.CRASH_STACK, MandatoryCompletion.FAILED_GAP)
                    .copy(failureSemantic = ToolResultStatus.NOT_FOUND),
                requirement(waivedCode, MandatoryRequirementType.COHORT_COMPARISON, MandatoryCompletion.WAIVED)
                    .copy(waiverReason = "本问题只涉及单一版本，无需版本对照"),
                requirement(unbackedCode, MandatoryRequirementType.LOG_TRACE, MandatoryCompletion.SATISFIED),
                requirement(semanticLostCode, MandatoryRequirementType.CRASH_STACK, MandatoryCompletion.FAILED_GAP)
            )
        )
        val mandatoryRows = rows(
            "SELECT requirement_code, failure_semantic FROM t_rca_mandatory_evidence " +
                "WHERE task_id = ? AND requirement_code IN (?, ?, ?)",
            taskId, satisfiedCode, gapCode, waivedCode
        )
        // 三行而不是六行：「一项门禁一行」是 MandatoryEvidencePlan.blocking 按行数计数的前提。
        assertEquals(3, mandatoryRows.size, "同一 requirement_code 出现了多行，门禁会被重复计数")
        assertEquals(
            ToolResultStatus.NOT_FOUND.storageCode,
            mandatoryRows.single { it["requirement_code"] == gapCode }["failure_semantic"],
            "重放没有更新完成状态"
        )

        // --- 引用校验：这张表没有唯一键，幂等只能靠判存在 -----------------------------------
        port.saveHypotheses(taskId, listOf(hypothesis("H1", revision = 0)))
        val claimCode = "H1#$marker-evt-1"
        val outcome = arbitration(
            taskId = taskId,
            revision = nextAttributionRevision(taskId),
            level = EpisodeAttributionLevel.AMBIGUOUS,
            claimPairs = listOf(
                ClaimEvidencePair(
                    claimCode = claimCode,
                    claimType = ClaimType.RANGE,
                    claimText = "失败率显著集中在新版本",
                    evidenceId = "$marker-evt-1",
                    // 引用存在但粒度不足：这正是「引用支持正确率」要抓的那一类。
                    evidenceCoverage = EvidenceCoverage.EPISODE,
                    verdict = ClaimSupportVerdict.PARTIAL_OVERSCOPED,
                    verdictReason = "范围性结论引用了单点级证据"
                )
            )
        )
        port.saveArbitration(outcome)
        port.saveArbitration(outcome)

        val claimRows = rows(
            "SELECT claim_code, claim_type, evidence_coverage_type, required_coverage_type, " +
                "support_verdict, review_status, hypothesis_id FROM t_rca_claim_evidence " +
                "WHERE task_id = ? AND claim_code = ?",
            taskId, claimCode
        )
        assertEquals(1, claimRows.size, "没有唯一键的表必须靠判存在去重，重放写出了重复行")
        val claim = claimRows.single()
        assertEquals(ClaimType.RANGE.requiredCoverage.storageCode, claim["required_coverage_type"], "要求粒度没有按 claim 类型推导")
        assertEquals(ClaimSupportVerdict.PARTIAL_OVERSCOPED.storageCode, claim["support_verdict"], "判定结果没有落库")
        // review_status 由语句固定 UNREVIEWED：chk_rca_claim_dual_review 要求 AGREED 必须带两个复核人，
        // 机器不得自行置位。
        assertEquals("UNREVIEWED", claim["review_status"], "机器把 review_status 置成了已复核")
        assertNotNull(claim["hypothesis_id"], "claim 的假设编码没有解析成行主键")
    }

    @Test
    fun `归因追加式落库，Incident 只在前两档关联，知识候选只沉淀可执行归因且状态为 DRAFT`() {
        assertLocalPort()
        val taskId = fixtureTaskId()
        val episodeId = episodeIdOf(taskId)
        val signature = RootCauseSignature(
            scope = "DEFAULT",
            component = "resource-auth-service",
            faultType = "PARAMETER_INCOMPATIBILITY",
            errorSignature = marker,
            endpointType = "HTTP_400"
        )

        // --- 直接确认：可关联 Incident、可沉淀知识 ------------------------------------------
        val directRevision = nextAttributionRevision(taskId)
        val direct = arbitration(
            taskId = taskId,
            revision = directRevision,
            level = EpisodeAttributionLevel.DIRECT_CONFIRMED,
            signature = signature
        )
        val rowId = port.saveArbitration(direct)
        assertTrue(rowId > 0, "归因行主键没有被 useGeneratedKeys 回填：linkIncident 拿不到目标行")

        val incidentId = port.linkIncident(rowId, direct)
        assertTrue(incidentId > 0, "DIRECT_CONFIRMED 应当允许自动关联 Incident")

        val linked = rows("SELECT incident_id, auto_linked, attribution_level FROM t_feedback_episode_attribution WHERE id = ?", rowId).single()
        assertEquals(
            incidentId, (linked["incident_id"] as Number).toLong(),
            "Incident 没有回填到归因行：影响面挂在了一个连不上的 Incident 上"
        )
        assertEquals(1L, asLong(linked["auto_linked"]), "auto_linked 与归因等级不一致")

        val incident = rows("SELECT incident_title, incident_severity, incident_status FROM t_incident WHERE id = ?", incidentId).single()
        assertEquals(
            "RCA:${signature.component}:${signature.faultType}:${signature.errorSignature}",
            incident["incident_title"],
            "Incident 标题不由稳定签名拼出：同一条根因的后续归因会各开一个 Incident"
        )
        // RCA 的证据模型里没有定级依据，一律开最低档交人工重定级。
        assertEquals("P3", incident["incident_severity"], "RCA 不得自行把严重度开到 P3 以上")

        // 再关联一次：按标题找回同一个 Incident，且第二次关联影响 0 行（已关联过）。
        assertEquals(incidentId, port.linkIncident(rowId, direct), "按标题没有找回已有 Incident，影响面会分裂")
        assertEquals(
            1L,
            count("SELECT COUNT(*) FROM t_incident WHERE incident_title = ?", incident["incident_title"]),
            "同一标题下出现了多个 Incident"
        )

        // --- 知识候选：DRAFT，且按 (source_rca_id, knowledge_source) 幂等 --------------------
        val candidateId = port.saveKnowledgeCandidate(
            KnowledgeCandidateRequest(taskId, episodeId, signature.canonical, direct.attribution.level.storageCode, listOf("$marker-evt-1"), "组件与现象摘要")
        )
        assertNotNull(candidateId, "可执行归因没有沉淀知识候选")
        val candidate = rows(
            "SELECT knowledge_status, knowledge_source, source_rca_id, knowledge_title FROM t_ai_knowledge WHERE id = ?",
            candidateId!!
        ).single()
        // 写成 PUBLISHED 会让模型用自己上一轮的结论支持自己下一轮的假设，而且账本上每条引用都「有出处」。
        assertEquals("DRAFT", candidate["knowledge_status"], "知识候选不是 DRAFT：会被 KnowledgeSearchMapper 当成历史证据召回")
        assertEquals("AGENT_RCA", candidate["knowledge_source"], "knowledge_source 与幂等查询的过滤条件必须一致")
        assertEquals(taskId, (candidate["source_rca_id"] as Number).toLong())
        assertEquals(candidateId, port.saveKnowledgeCandidate(
            KnowledgeCandidateRequest(taskId, episodeId, signature.canonical, direct.attribution.level.storageCode, listOf("$marker-evt-1"), "组件与现象摘要")
        ), "重放又插了一条知识候选：唯一键是随机 public_id，挡不住重放")

        // --- 歧义：不得关联 Incident、不得沉淀知识，且以新 revision 追加而不是覆盖 ----------
        val ambiguousRevision = nextAttributionRevision(taskId)
        assertTrue(ambiguousRevision > directRevision, "归因 revision 必须递增")
        val ambiguous = arbitration(
            taskId = taskId,
            revision = ambiguousRevision,
            level = EpisodeAttributionLevel.AMBIGUOUS,
            signature = signature
        )
        val ambiguousRowId = port.saveArbitration(ambiguous)
        assertTrue(ambiguousRowId != rowId, "新 revision 打到了同一行上：归因是追加式的，历史行必须保留")

        // 调用方本来就不该调它，但实现必须独立再判一次——将来任何新调用点都不能绕过去。
        assertEquals(0L, port.linkIncident(ambiguousRowId, ambiguous), "AMBIGUOUS 归因被关联了 Incident")
        assertNull(
            rows("SELECT incident_id FROM t_feedback_episode_attribution WHERE id = ?", ambiguousRowId).single()["incident_id"],
            "AMBIGUOUS 归因的 incident_id 被回填了"
        )
        assertNull(
            port.saveKnowledgeCandidate(
                KnowledgeCandidateRequest(taskId, episodeId, signature.canonical, ambiguous.attribution.level.storageCode, listOf("$marker-evt-1"), "组件与现象摘要")
            ),
            "歧义结论被沉淀成了知识候选：会污染后续检索"
        )

        val revisions = rows(
            "SELECT id, attribution_revision FROM t_feedback_episode_attribution WHERE episode_id = ? AND rca_task_id = ?",
            episodeId, taskId
        ).map { (it["id"] as Number).toLong() to (it["attribution_revision"] as Number).toLong() }
        assertTrue(rowId in revisions.map { it.first }, "直接确认那一行在追加新 revision 后消失了")
        assertTrue(ambiguousRowId in revisions.map { it.first }, "歧义那一行没有落库")
    }

    // -----------------------------------------------------------------------------------------
    // 夹具与断言辅助
    // -----------------------------------------------------------------------------------------

    /**
     * 借一行真实任务的 `(id, episode_id)`。
     *
     * 不自己插一行任务：插任务就要插 `t_feedback_episode`（外键链的根），
     * 于是本类会开始造业务实体——而本类要验的恰恰是「生产代码路径写得对不对」，
     * 造父行只会让失败时的归因变模糊。借用的那行在事务回滚后原封不动。
     */
    private fun fixtureTaskId(): Long = jdbc.queryForObject(
        "SELECT id FROM t_rca_agent_task ORDER BY id LIMIT 1", Long::class.java
    ) ?: error("库里没有任务行，先跑接入测试（RCA_INTAKE_ENTRY）再跑本测试")

    private fun episodeIdOf(taskId: Long): Long = jdbc.queryForObject(
        "SELECT episode_id FROM t_rca_agent_task WHERE id = ?", Long::class.java, taskId
    )!!

    /**
     * 取一个该 `(episode_id, rca_task_id)` 上尚未使用的归因 revision。
     *
     * 借用的任务可能已经有**已提交**的归因行，直接用 0 会命中唯一键并静默走
     * `ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)` —— 于是「追加」这件事根本没发生，
     * 而断言会以「linkIncident 返回 0」这种与原因无关的形式失败。
     */
    private fun nextAttributionRevision(taskId: Long): Long = jdbc.queryForObject(
        """
        SELECT IFNULL(MAX(attribution_revision), -1) + 1
        FROM t_feedback_episode_attribution WHERE episode_id = ? AND rca_task_id = ?
        """.trimIndent(),
        Long::class.java, episodeIdOf(taskId), taskId
    )!!

    private fun episodeEvidence(
        evidenceId: String,
        digest: String = DIGEST_A,
        revision: Long = 0,
        summary: String = "首错点位于资源鉴权服务"
    ): EvidenceEntry = EvidenceEntry(
        evidenceId = evidenceId,
        type = EvidenceType.LOG_TRACE,
        sourceTool = "LOG_TRACE",
        sourceRef = "ledger://itest/$evidenceId",
        coverageType = EvidenceCoverage.EPISODE,
        coverageScope = "episode:$marker",
        subjectRefs = listOf("resource-auth-service"),
        numerator = 3,
        observedFrom = LocalDateTime.of(2026, 9, 1, 10, 0),
        observedTo = LocalDateTime.of(2026, 9, 1, 10, 5),
        permissionScope = PermissionScope.EPISODE,
        contentDigest = digest,
        summary = summary,
        revision = revision
    )

    private fun hypothesis(
        code: String,
        revision: Long,
        statement: String = "参数改名导致鉴权失败",
        mergedIntoCode: String? = null
    ): Hypothesis = Hypothesis(
        code = code,
        statement = statement,
        mechanismNote = "鉴权接口按参数名取值，改名后取到空值",
        confidence = HypothesisConfidence.MODERATE,
        supportingEvidenceIds = listOf("$marker-evt-1"),
        revision = revision,
        mergedIntoCode = mergedIntoCode
    )

    private fun requirement(
        code: String,
        type: MandatoryRequirementType,
        completion: MandatoryCompletion
    ): MandatoryRequirement = MandatoryRequirement(
        code = code,
        type = type,
        triggerRule = "itest 触发依据",
        requiredReason = "itest 必要性说明",
        completion = completion
    )

    private fun arbitration(
        taskId: Long,
        revision: Long,
        level: EpisodeAttributionLevel,
        signature: RootCauseSignature = RootCauseSignature(
            scope = "DEFAULT",
            component = "resource-auth-service",
            faultType = "PARAMETER_INCOMPATIBILITY",
            errorSignature = marker,
            endpointType = "HTTP_400"
        ),
        claimPairs: List<ClaimEvidencePair> = emptyList()
    ): RcaArbitrationOutcome = RcaArbitrationOutcome(
        attribution = EpisodeAttribution(
            episodeId = episodeIdOf(taskId),
            rcaTaskId = taskId,
            signature = signature,
            level = level,
            revision = revision,
            basisEvidenceIds = listOf("$marker-evt-1"),
            // 非 DIRECT_CONFIRMED 必须说明证据覆盖范围，这是 EpisodeAttribution 的自洽约束。
            coverageNote = if (level == EpisodeAttributionLevel.DIRECT_CONFIRMED) null else "证据只覆盖被查询的 Episode",
            decidedAt = LocalDateTime.of(2026, 9, 28, 12, 0),
            decidedBy = null
        ),
        claimPairs = claimPairs,
        violationCode = if (level.autoLinkPermitted) null else "AMBIGUOUS_MECHANISM",
        finalStatus = if (level.autoLinkPermitted) RcaTaskStatus.COMPLETED else RcaTaskStatus.NEEDS_HUMAN,
        stopReason = if (level.autoLinkPermitted) RcaStopReason.EVIDENCE_SUFFICIENT else RcaStopReason.HUMAN_REQUIRED
    )

    private fun rows(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        jdbc.queryForList(sql, *args)

    private fun count(sql: String, vararg args: Any?): Long =
        jdbc.queryForObject(sql, Long::class.java, *args)!!

    /**
     * 把 `TINYINT(1)` 归一成数字再断言。
     *
     * 不能直接 `assertEquals(true, value)`：Connector/J 的 `tinyInt1isBit` 决定这一列
     * 到底以 Boolean 还是 Integer 返回，断言写死其中一个，换一次驱动配置就会红，
     * 而红的原因与本类要验的行为毫无关系。
     */
    private fun asLong(value: Any?): Long = when (value) {
        is Boolean -> if (value) 1L else 0L
        is Number -> value.toLong()
        else -> error("不是可归一成数字的列值：$value")
    }

    /** 3306 是别的项目的库；本测试会写数据，端口判错就是在写别人的库。 */
    private fun assertLocalPort() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "持久化契约测试只允许连本机 feedback-platform 的 3307，当前地址端口不对"
        )
    }

    companion object {
        /** 两个合法摘要。库上 chk_rca_evidence_digest 要求 `^[0-9a-f]{64}$`。 */
        private val DIGEST_A = "0".repeat(64)
        private val DIGEST_B = "1".repeat(64)

        /**
         * 只补**测试专用**的固定密钥，不补数据库地址（理由同 `RcaIntakeEndToEndTest`：
         * 一个 3306 的兜底会让本测试静默连上别的项目的库）。
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
