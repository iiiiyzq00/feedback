package com.feedback.service.query.analysis

import com.feedback.application.port.problem.analysis.ClusterShadowPreviewDataset
import com.feedback.application.port.problem.analysis.ClusterShadowPreviewReadPort
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.auth.RoleCode
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.security.CurrentWebPrincipalProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class ClusterShadowPreviewApplicationService(
    private val reads: ClusterShadowPreviewReadPort,
    private val principal: CurrentWebPrincipalProvider,
    private val phenomena: ClusterShadowPreviewPhenomenonProjector
) : ClusterShadowPreviewQueryUseCase {
    @Volatile
    private var cachedVerifiedProjection: Projection? = null

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun list(query: ClusterShadowPreviewQuery): ClusterShadowPreviewPageResponse {
        requireAdmin()
        validatePage(query.page, query.size)
        val projection = projection()
        val filtered = projection.clusters.filter { row ->
            (!query.largeOnly || row.item.large) &&
                (!query.provisionalOnly || row.item.provisional) &&
                (!query.crossChannelOnly || row.item.crossChannel) &&
                (query.application.isNullOrBlank() || row.item.application == query.application.trim()) &&
                when (query.analysisSection) {
                    null -> true
                    "CONFIRMED_APPLICATION" -> row.confirmedApplication
                    "PENDING_APPLICATION" -> !row.confirmedApplication
                    else -> throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_SHADOW_PREVIEW_SECTION_INVALID")
                }
        }.sortedWith(compareByDescending<ProjectedCluster> { it.item.memberCount }.thenBy { it.item.problem })
        return ClusterShadowPreviewPageResponse(
            generation = projection.generation,
            analysisSections = analysisSections(projection.generation, projection.clusters),
            items = filtered.drop((query.page - 1) * query.size).take(query.size).map { it.item },
            total = filtered.size.toLong(),
            page = query.page,
            size = query.size
        )
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun detail(previewId: String, page: Int, size: Int): ClusterShadowPreviewDetailResponse {
        requireAdmin()
        validatePage(page, size)
        val row = projection().clusters.singleOrNull { it.item.previewId == previewId }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_SHADOW_PREVIEW_CLUSTER_NOT_FOUND")
        val offset = (page - 1) * size
        val members = row.members.drop(offset).take(size).mapIndexed { index, member ->
            ClusterShadowAnonymousMemberResponse(
                anonymousId = "%s-M%04d".format(previewId, offset + index + 1),
                channel = row.channel(member),
                application = row.application(member),
                decision = member.decisionCode,
                score = member.score,
                provisional = member.decisionCode.startsWith("PROVISIONAL_"),
                boundaryKnown = member.boundaryKnown()
            )
        }
        return ClusterShadowPreviewDetailResponse(row.item, members, row.members.size.toLong(), page, size)
    }

    /** 白名单 VERIFIED generation 不可变；首次完整校验后复用安全投影，分页不再重复扫描。 */
    private fun projection(): Projection = cachedVerifiedProjection ?: synchronized(this) {
        cachedVerifiedProjection ?: buildProjection().also { cachedVerifiedProjection = it }
    }

    private fun buildProjection(): Projection {
        val dataset = reads.readVerified(ALLOWED_GENERATION)
        val generation = dataset.generation
        if (generation.generationKey != ALLOWED_GENERATION ||
            generation.generationStatus != "VERIFIED" ||
            generation.algorithmVersion != ALGORITHM_VERSION ||
            generation.processedCount != INPUT_COUNT || generation.actionableCount != ACTIONABLE_COUNT ||
            generation.terminalCount != TERMINAL_COUNT || generation.clusterCount != CLUSTER_COUNT ||
            generation.provisionalCount != PROVISIONAL_COUNT) {
            previewUnavailable("CLUSTER_SHADOW_PREVIEW_GENERATION_CONTRACT_MISMATCH")
        }
        val grouped = dataset.members.groupBy { it.clusterKeyHash!! }
        if (dataset.members.size.toLong() != ACTIONABLE_COUNT ||
            dataset.members.any { it.problemStatus != "ACTIONABLE" } ||
            dataset.clusters.size != CLUSTER_COUNT.toInt() || grouped.size != dataset.clusters.size) {
            previewUnavailable("CLUSTER_SHADOW_PREVIEW_CLUSTER_SET_MISMATCH")
        }
        if (dataset.terminalCountByStatus != EXPECTED_TERMINAL_COUNTS ||
            dataset.terminalCountByStatus.values.sum() != TERMINAL_COUNT) {
            previewUnavailable("CLUSTER_SHADOW_PREVIEW_TERMINAL_COUNTS_MISMATCH")
        }
        val rawRows = dataset.clusters.sortedBy { it.clusterKeyHash }.mapIndexed { index, cluster ->
            val members = grouped[cluster.clusterKeyHash].orEmpty().sortedBy { it.feedbackSourceId }
            if (members.size.toLong() != cluster.memberCount ||
                members.count { it.decisionCode.startsWith("PROVISIONAL_") }.toLong() != cluster.provisionalMemberCount) {
                previewUnavailable("CLUSTER_SHADOW_PREVIEW_CLUSTER_SUMMARY_MISMATCH")
            }
            if (!hardBoundariesCompatible(members)) {
                previewUnavailable("CLUSTER_SHADOW_PREVIEW_HARD_BOUNDARY_CONFLICT")
            }
            val previewId = "SC-%04d".format(index + 1)
            val channels = members.groupingBy { member ->
                dataset.evidenceBySourceId[member.feedbackSourceId]?.channel
                    ?: previewUnavailable("CLUSTER_SHADOW_PREVIEW_EVIDENCE_MISSING")
            }.eachCount().toSortedMap()
            if (channels.keys.any { it !in ALLOWED_CHANNELS }) {
                previewUnavailable("CLUSTER_SHADOW_PREVIEW_CHANNEL_INVALID")
            }
            val readable = phenomena.project(
                cluster.clusterKeyHash, cluster.clusterNameHash, members, dataset.evidenceBySourceId
            )
            val evidenceApplications = members.mapNotNull { member ->
                dataset.evidenceBySourceId[member.feedbackSourceId]
                    ?.applicationName
            }.toSet()
            if (evidenceApplications.size > 1 ||
                (evidenceApplications.isNotEmpty() && evidenceApplications.single() != readable.application)) {
                previewUnavailable("CLUSTER_SHADOW_PREVIEW_APPLICATION_CONFLICT")
            }
            val boundaries = ClusterShadowBoundaryCoverageResponse(
                application = members.count { it.applicationHash != null }.toLong(),
                failureMode = members.count { it.failureModeHash != null }.toLong(),
                target = members.count { it.targetHash != null }.toLong(),
                action = members.count { it.actionHash != null }.toLong(),
                errorSignature = members.count { it.errorSignatureHash != null }.toLong()
            )
            ProjectedCluster(
                ClusterShadowPreviewItemResponse(
                    previewId, problemName(readable.application, readable.phenomenon), readable.phenomenon,
                    cluster.memberCount, readable.application, cluster.provisionalMemberCount > 0,
                    cluster.provisionalMemberCount, cluster.memberCount >= LARGE_CLUSTER_SIZE, channels.size >= 2,
                    channels.map { ClusterShadowChannelCountResponse(it.key, it.value.toLong()) }, boundaries
                ),
                members,
                { member -> dataset.evidenceBySourceId.getValue(member.feedbackSourceId).channel },
                { readable.application },
                members.all { it.applicationHash != null },
                readable.qualifier
            )
        }
        val rows = disambiguateDuplicateNames(rawRows)
        return Projection(
            ClusterShadowPreviewGenerationResponse(
                generation.generationKey, generation.generationStatus, generation.algorithmVersion,
                generation.modelVersion, generation.processedCount, generation.actionableCount,
                generation.terminalCount, generation.clusterCount, generation.provisionalCount,
                ACCEPT_THRESHOLD, PROVISIONAL_THRESHOLD, SEMANTIC_WEIGHT, generation.verifiedAt,
                terminalReasons(dataset.terminalCountByStatus)
            ), rows
        )
    }

    private fun problemName(application: String, phenomenon: String): String =
        "${application.trim()}\u00b7${phenomenon.trim()}"

    private fun disambiguateDuplicateNames(rows: List<ProjectedCluster>): List<ProjectedCluster> =
        rows.groupBy { it.item.problem }.values.flatMap { collisions ->
            if (collisions.size == 1) collisions else collisions.mapIndexed { index, row ->
                val qualifier = row.qualifier?.takeIf { candidate ->
                    collisions.count { it.qualifier == candidate } == 1
                } ?: "独立问题边界 ${index + 1}"
                row.copy(item = row.item.copy(problem = "${row.item.problem}（$qualifier）"))
            }
        }.sortedBy { it.item.previewId }

    /** 四条通道是对允许清单内 V4 代次的一个精确划分。 */
    private fun analysisSections(
        generation: ClusterShadowPreviewGenerationResponse,
        clusters: List<ProjectedCluster>
    ): List<ClusterShadowAnalysisSectionResponse> {
        val counts = generation.terminalReasons.associate { it.status to it.count }
        fun count(vararg statuses: String) = statuses.sumOf { counts.getValue(it) }
        val confirmed = clusters.filter(ProjectedCluster::confirmedApplication)
        val pending = clusters.filterNot(ProjectedCluster::confirmedApplication)
        return listOf(
            ClusterShadowAnalysisSectionResponse(
                "CONFIRMED_APPLICATION", "已确认应用问题簇", confirmed.sumOf { it.item.memberCount },
                confirmed.size.toLong(), true, "应用仅作为问题名称前缀和后端硬隔离边界"
            ),
            ClusterShadowAnalysisSectionResponse(
                "PENDING_APPLICATION", "应用待定问题簇",
                pending.sumOf { it.item.memberCount }, pending.size.toLong(), true,
                "按能力域和故障族展示；零命中与多命中保持独立，绝不冒充具体应用"
            ),
            ClusterShadowAnalysisSectionResponse(
                "ENGINEERING_CLASSIFICATION", "硬件／系统／其他问题分类",
                count("NON_FAULT_INPUT", "USER_INTENT_INCOMPLETE", "NOVEL_SYMPTOM", "MULTI_PROBLEM_AMBIGUOUS"),
                null, false, "保留尚不足以安全形成问题簇的硬件、诉求、咨询和复合问题输入"
            ),
            ClusterShadowAnalysisSectionResponse(
                "UNUSABLE_CONTENT", "确实不可用内容",
                count("NOISE", "LEGACY_INFORMATION_LOSS", "INSUFFICIENT_INFORMATION"), null, false,
                "仅包含噪声、历史脱敏信息丢失和有效信息不足"
            )
        ).also { sections ->
            if (sections.sumOf { it.recordCount } != generation.inputCount) {
                previewUnavailable("CLUSTER_SHADOW_PREVIEW_ANALYSIS_SECTION_TOTAL_MISMATCH")
            }
        }
    }

    private fun terminalReasons(counts: Map<String, Long>) = TERMINAL_REASON_COPY.map { (status, copy) ->
        ClusterShadowTerminalReasonResponse(status, copy.first, counts.getValue(status), copy.second)
    }

    private fun hardBoundariesCompatible(rows: List<ClusterShadowCandidateMemberPo>): Boolean {
        val automatic = rows.filterNot { it.manualLocked }
        fun compatible(selector: (ClusterShadowCandidateMemberPo) -> String?) =
            automatic.map(selector).toSet().size <= 1
        return compatible { it.applicationHash } && compatible { it.failureModeHash } &&
            compatible { it.targetHash } && compatible { it.actionHash } &&
            compatible { it.errorSignatureHash }
    }

    private fun requireAdmin() {
        if (RoleCode.ADMIN !in principal.require().roles) {
            throw ClientException(ErrorCode.AUTH_FORBIDDEN, "CLUSTER_SHADOW_PREVIEW_ADMIN_REQUIRED")
        }
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 1 || size !in 1..100) {
            throw ClientException(ErrorCode.BAD_REQUEST, "CLUSTER_SHADOW_PREVIEW_PAGINATION_INVALID")
        }
    }

    private fun ClusterShadowCandidateMemberPo.boundaryKnown() = ClusterShadowBoundaryKnownResponse(
        applicationHash != null, failureModeHash != null, targetHash != null,
        actionHash != null, errorSignatureHash != null
    )

    private data class Projection(
        val generation: ClusterShadowPreviewGenerationResponse,
        val clusters: List<ProjectedCluster>
    )

    private data class ProjectedCluster(
        val item: ClusterShadowPreviewItemResponse,
        val members: List<ClusterShadowCandidateMemberPo>,
        val channel: (ClusterShadowCandidateMemberPo) -> String,
        val application: (ClusterShadowCandidateMemberPo) -> String,
        val confirmedApplication: Boolean,
        val qualifier: String?
    )

    companion object {
        const val ALLOWED_GENERATION = "shadow-12-full-20260910-capability-v4-r2"
        const val ALGORITHM_VERSION = "cluster-classification-v4-capability-fault-family-shadow"
        const val INPUT_COUNT = 26_141L
        const val ACTIONABLE_COUNT = 10_558L
        const val TERMINAL_COUNT = 15_583L
        const val CLUSTER_COUNT = 1_358L
        const val PROVISIONAL_COUNT = 671L
        val ACCEPT_THRESHOLD: BigDecimal = BigDecimal("0.82")
        val PROVISIONAL_THRESHOLD: BigDecimal = BigDecimal("0.68")
        val SEMANTIC_WEIGHT: BigDecimal = BigDecimal("0.20")
        private const val LARGE_CLUSTER_SIZE = 50L
        private val ALLOWED_CHANNELS = setOf("STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK")
        private val EXPECTED_TERMINAL_COUNTS = linkedMapOf(
            "INSUFFICIENT_INFORMATION" to 118L,
            "USER_INTENT_INCOMPLETE" to 1_195L,
            "NOISE" to 5_081L,
            "NON_FAULT_INPUT" to 7_008L,
            "LEGACY_INFORMATION_LOSS" to 903L,
            "NOVEL_SYMPTOM" to 421L,
            "MULTI_PROBLEM_AMBIGUOUS" to 857L
        )
        private val TERMINAL_REASON_COPY = linkedMapOf(
            "NON_FAULT_INPUT" to ("非故障输入待分类" to "求助、主观叙述、目录或家长管理诉求，证据不足时不强行形成故障簇"),
            "NOISE" to ("噪声内容" to "随机字符、异常符号或无语言结构文本"),
            "USER_INTENT_INCOMPLETE" to ("问题描述不完整" to "有情绪或操作意图，但缺少可验证的故障对象和结果"),
            "NOVEL_SYMPTOM" to ("新症状待建模" to "存在异常线索，但当前症状本体尚未覆盖"),
            "MULTI_PROBLEM_AMBIGUOUS" to ("一条包含多个问题" to "无法安全选择其中一个问题，禁止任意归簇"),
            "LEGACY_INFORMATION_LOSS" to ("历史脱敏信息丢失" to "旧占位符遮蔽了唯一有效对象或内容"),
            "INSUFFICIENT_INFORMATION" to ("有效信息不足" to "正文为空、模板化、乱码或仅含噪声")
        )
    }
}

internal fun previewUnavailable(reason: String): Nothing =
    throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, reason)
