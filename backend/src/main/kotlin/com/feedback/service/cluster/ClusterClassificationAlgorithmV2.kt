package com.feedback.service.cluster

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/** 人工结论是影子运行的不可变输入，其优先级始终高于自动算法。 */
data class ManualClusterConclusion(
    val clusterKey: String,
    val clusterName: String
)

data class ClusterClassificationInput(
    val sourceId: Long,
    val applicationKey: String?,
    val applicationName: String?,
    val title: String?,
    val description: String?,
    val functionModule: String? = null,
    val issueCategory: String? = null,
    val applicationAmbiguous: Boolean = false,
    val contentDisposition: ShadowContentDisposition = ShadowContentDisposition.ELIGIBLE,
    val manualConclusion: ManualClusterConclusion? = null,
    val sourceChannel: String? = null,
    val applicationEvidence: ApplicationIdentityEvidence = ApplicationIdentityEvidence.APPROVED_IDENTITY
)

enum class ShadowContentDisposition { ELIGIBLE, MASKING_REVIEW_REQUIRED, INELIGIBLE }

enum class ApplicationIdentityEvidence {
    APPROVED_IDENTITY,
    JIRA_PROJECT_KEY,
    SOURCE_PLATFORM,
    CHANNEL_NAME,
    DISPLAY_NAME,
    TEXT_MENTION
}

/**
 * application 身份与症状是否可归簇是两个独立结论。待定身份只允许进入 shadow 候选域，
 * 且 unresolved/ambiguous 使用不同分区，永远不能与已确认 application 候选互相召回。
 */
enum class ApplicationClusteringScope {
    RESOLVED,
    PENDING_UNRESOLVED,
    PENDING_AMBIGUOUS
}

enum class PendingApplicationPolicy { FAIL_CLOSED, ISOLATED_SYMPTOM_CLUSTER }

enum class ProblemClassificationStatus {
    ACTIONABLE,
    INSUFFICIENT_INFORMATION,
    USER_INTENT_INCOMPLETE,
    NOISE,
    NON_FAULT_INPUT,
    LEGACY_INFORMATION_LOSS,
    NOVEL_SYMPTOM,
    UNKNOWN_FAILURE_MODE,
    APPLICATION_UNRESOLVED,
    APPLICATION_AMBIGUOUS,
    CONTENT_REVIEW_NEEDED,
    CONTENT_INELIGIBLE,
    MULTI_PROBLEM_AMBIGUOUS
}

enum class ProblemEvidenceSource { DIRECT_TEXT, SOURCE_LABEL, DIRECT_AND_SOURCE }

data class AtomicProblemProfile(
    val sourceId: Long,
    val applicationKey: String?,
    val applicationName: String?,
    val status: ProblemClassificationStatus,
    val primaryClause: String?,
    val target: String?,
    val action: String?,
    val failureMode: String?,
    val errorSignature: String?,
    val canonicalSymptom: String?,
    val identityKey: String?,
    val reason: String,
    val evidenceSource: ProblemEvidenceSource? = null,
    val applicationScope: ApplicationClusteringScope = ApplicationClusteringScope.RESOLVED,
    val capabilityDomain: EngineeringCapabilityDomain? = null,
    val faultFamily: EngineeringFaultFamily? = null
) {
    /** 仅作为内部分区使用；它绝不是应用身份，也不是面向用户的标识。 */
    fun applicationPartitionKey(): String = when (applicationScope) {
        ApplicationClusteringScope.RESOLVED -> "resolved:${applicationKey.orEmpty()}"
        ApplicationClusteringScope.PENDING_UNRESOLVED ->
            "pending:unresolved:${capabilityDomain?.name ?: "UNCLASSIFIED"}"
        ApplicationClusteringScope.PENDING_AMBIGUOUS ->
            "pending:ambiguous:${capabilityDomain?.name ?: "UNCLASSIFIED"}"
    }
}

data class ShadowClusterAssignment(
    val sourceId: Long,
    val status: ProblemClassificationStatus,
    val clusterKey: String?,
    val clusterName: String?,
    val decision: String,
    val score: BigDecimal?,
    val reason: String,
    val manualLocked: Boolean
)

data class ShadowClusterResult(
    val assignments: List<ShadowClusterAssignment>,
    val clusterCount: Int,
    val nonActionableCount: Int
)

/**
 * v3 影子分类器使用的证据分层、子句级确定性提取器。
 * 本实现不依赖数据库、向量模型或 LLM，任何可选外部服务故障都不能改变分类结果。
 */
fun interface AtomicProblemExtraction {
    fun extract(input: ClusterClassificationInput): AtomicProblemProfile
}

class AtomicProblemExtractorV3(
    private val pendingApplicationPolicy: PendingApplicationPolicy = PendingApplicationPolicy.FAIL_CLOSED
) : AtomicProblemExtraction {
    override fun extract(input: ClusterClassificationInput): AtomicProblemProfile {
        when (input.contentDisposition) {
            ShadowContentDisposition.MASKING_REVIEW_REQUIRED -> return terminal(
                input, ProblemClassificationStatus.CONTENT_REVIEW_NEEDED,
                "内容等待安全审核，正文未进入影子算法"
            )
            ShadowContentDisposition.INELIGIBLE -> return terminal(
                input, ProblemClassificationStatus.CONTENT_INELIGIBLE,
                "内容不满足影子归簇资格，正文未进入影子算法"
            )
            ShadowContentDisposition.ELIGIBLE -> Unit
        }
        if (input.applicationEvidence != ApplicationIdentityEvidence.APPROVED_IDENTITY) {
            return terminal(input, ProblemClassificationStatus.APPLICATION_UNRESOLVED,
                "application 证据未经身份契约批准")
        }
        if (input.applicationAmbiguous && pendingApplicationPolicy == PendingApplicationPolicy.FAIL_CLOSED) {
            return terminal(input, ProblemClassificationStatus.APPLICATION_AMBIGUOUS, "应用 alias 指向多个身份")
        }
        val applicationKey = input.applicationKey?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        if (applicationKey == null && pendingApplicationPolicy == PendingApplicationPolicy.FAIL_CLOSED) {
            return terminal(input, ProblemClassificationStatus.APPLICATION_UNRESOLVED,
                "缺少经过审计的 application 身份")
        }
        if (applicationKey != null && applicationKey.uppercase() in PROHIBITED_APPLICATION_KEYS) {
            return terminal(input, ProblemClassificationStatus.APPLICATION_UNRESOLVED,
                "渠道名或来源平台不得作为 application 身份")
        }
        val applicationScope = when {
            input.applicationAmbiguous -> ApplicationClusteringScope.PENDING_AMBIGUOUS
            applicationKey == null -> ApplicationClusteringScope.PENDING_UNRESOLVED
            else -> ApplicationClusteringScope.RESOLVED
        }

        val rawDescription = input.description.orEmpty().trim()
        val title = clean(input.title.orEmpty())
        val description = clean(rawDescription)
        val text = clean(listOf(title, description).filter(String::isNotBlank).joinToString("。"))
        routeTerminal(rawDescription, title)?.let { (status, reason) -> return terminal(input, status, reason) }
        if (isLowInformation(text)) return terminal(input, ProblemClassificationStatus.INSUFFICIENT_INFORMATION,
            "正文为空、模板化、乱码或仅包含噪声")

        val descriptionClauses = splitClauses(description)
        val titleClauses = splitClauses(title)
        if (descriptionClauses.any(::hasCompetingProblems) || titleClauses.any(::hasCompetingProblems)) {
            return terminal(input, ProblemClassificationStatus.MULTI_PROBLEM_AMBIGUOUS,
                "同一子句包含多个独立故障，禁止任意选择")
        }
        val descriptionFacts = descriptionClauses.mapNotNull(::facts).distinctBy { it.identityTuple() }
        val titleFacts = titleClauses.mapNotNull(::facts).distinctBy { it.identityTuple() }
        val fact: ClauseFact
        val evidenceSource: ProblemEvidenceSource
        when {
            descriptionFacts.isNotEmpty() -> {
                fact = reconcileFacts(descriptionFacts) ?: return terminal(
                    input, ProblemClassificationStatus.MULTI_PROBLEM_AMBIGUOUS,
                    "用户正文存在多个不同原子问题，禁止跨子句拼接")
                val compatibleTitle = titleFacts.filter { compatible(fact, it) }
                evidenceSource = if (compatibleTitle.isEmpty()) ProblemEvidenceSource.DIRECT_TEXT
                    else ProblemEvidenceSource.DIRECT_AND_SOURCE
            }
            titleFacts.isNotEmpty() -> {
                fact = reconcileFacts(titleFacts) ?: return terminal(
                    input, ProblemClassificationStatus.MULTI_PROBLEM_AMBIGUOUS,
                    "来源标签包含多个冲突故障")
                if (fact.failureMode == "APP_MISSING" && fact.target == null) return terminal(
                    input, ProblemClassificationStatus.USER_INTENT_INCOMPLETE,
                    "来源标签仅说明应用缺失，但正文没有给出具体应用对象")
                evidenceSource = ProblemEvidenceSource.SOURCE_LABEL
            }
            else -> {
                val status = if (looksLikeNovelFault(description)) ProblemClassificationStatus.NOVEL_SYMPTOM
                    else ProblemClassificationStatus.NON_FAULT_INPUT
                val reason = if (status == ProblemClassificationStatus.NOVEL_SYMPTOM)
                    "用户正文包含明确异常线索，但当前症状本体尚未覆盖"
                else "未发现可验证的软件故障事实，自动进入非故障终态"
                return terminal(input, status, reason)
            }
        }
        val symptom = canonicalSymptom(fact)
        // RESOLVED 必须保持 shadow-11 v3 指纹完全不变，确保既有 VERIFIED generation 可继续只读复核。
        val identityMaterial = (if (applicationScope == ApplicationClusteringScope.RESOLVED) listOf(
            IDENTITY_SCHEMA_VERSION, applicationKey.orEmpty(), fact.target.orEmpty(), fact.action.orEmpty(),
            fact.failureMode, fact.errorSignature.orEmpty(), normalizeClusterSimilarityText(symptom)
        ) else listOf(
            IDENTITY_SCHEMA_VERSION, applicationScope.name, fact.target.orEmpty(), fact.action.orEmpty(),
            fact.failureMode, fact.errorSignature.orEmpty(), normalizeClusterSimilarityText(symptom)
        )).joinToString("|")
        return AtomicProblemProfile(
            sourceId = input.sourceId,
            applicationKey = applicationKey,
            applicationName = when (applicationScope) {
                ApplicationClusteringScope.RESOLVED ->
                    input.applicationName?.trim()?.takeIf(String::isNotBlank) ?: applicationKey
                ApplicationClusteringScope.PENDING_UNRESOLVED -> "应用待确认"
                ApplicationClusteringScope.PENDING_AMBIGUOUS -> "应用有歧义（待确认）"
            },
            status = ProblemClassificationStatus.ACTIONABLE,
            primaryClause = fact.clause,
            target = fact.target,
            action = fact.action,
            failureMode = fact.failureMode,
            errorSignature = fact.errorSignature,
            canonicalSymptom = symptom,
            identityKey = sha256(identityMaterial),
            reason = (when (evidenceSource) {
                ProblemEvidenceSource.DIRECT_TEXT -> "故障事实来自用户正文中的同一原子子句"
                ProblemEvidenceSource.SOURCE_LABEL -> "用户正文不足，故障事实来自结构化来源标签"
                ProblemEvidenceSource.DIRECT_AND_SOURCE -> "用户正文与结构化来源标签相互印证"
            }) + when (applicationScope) {
                ApplicationClusteringScope.RESOLVED -> ""
                ApplicationClusteringScope.PENDING_UNRESOLVED -> "；application 未解析，仅进入隔离的待确认症状簇"
                ApplicationClusteringScope.PENDING_AMBIGUOUS -> "；application 命中有歧义，仅进入隔离的待确认症状簇"
            },
            evidenceSource = evidenceSource,
            applicationScope = applicationScope
        )
    }

    private fun terminal(input: ClusterClassificationInput, status: ProblemClassificationStatus, reason: String) =
        AtomicProblemProfile(input.sourceId, input.applicationKey, input.applicationName, status,
            null, null, null, null, null, null, null, reason, null,
            applicationScope = when {
                pendingApplicationPolicy != PendingApplicationPolicy.ISOLATED_SYMPTOM_CLUSTER ->
                    ApplicationClusteringScope.RESOLVED
                input.applicationAmbiguous -> ApplicationClusteringScope.PENDING_AMBIGUOUS
                input.applicationKey.isNullOrBlank() -> ApplicationClusteringScope.PENDING_UNRESOLVED
                else -> ApplicationClusteringScope.RESOLVED
            })

    private fun routeTerminal(description: String, title: String): Pair<ProblemClassificationStatus, String>? {
        if (description.isBlank() && title.isBlank()) return null
        val payload = description.trim()
        if (isCatalogOrParentalGovernanceIntent(payload)) return ProblemClassificationStatus.NON_FAULT_INPUT to
            "应用目录或家长管理诉求不属于软件故障症状"
        val directProvidesFault = splitClauses(clean(description)).any { facts(it) != null }
        if (directProvidesFault) return null
        val sourceProvidesFault = splitClauses(title).mapNotNull(::facts).any {
            it.failureMode != "APP_MISSING" || it.target != null
        }
        if (sourceProvidesFault) return null
        if (isStrongNoise(payload)) return ProblemClassificationStatus.NOISE to
            "随机字符、异常符号或无语言结构文本，自动进入噪声终态"
        if (hasLegacyInformationLoss(payload)) return ProblemClassificationStatus.LEGACY_INFORMATION_LOSS to
            "旧脱敏占位符遮蔽了唯一有效对象或内容，自动隔离且不创建症状簇"
        if (isIncompleteIntent(payload)) return ProblemClassificationStatus.USER_INTENT_INCOMPLETE to
            "表达包含情绪或动作意图，但缺少可验证的故障对象和结果"
        if (isExplicitNonFault(payload)) return ProblemClassificationStatus.NON_FAULT_INPUT to
            "求助、主观叙述或非软件行为，自动进入非故障终态"
        return null
    }

    private fun hasLegacyInformationLoss(value: String): Boolean {
        if (!LEGACY_INFORMATION_MARKER.containsMatchIn(value)) return false
        val remaining = value.replace(LEGACY_INFORMATION_MARKER, " ")
            .replace(Regex("(?i)[{}\"':,_a-z0-9./-]+"), " ")
            .replace(Regex("\\s+"), "")
        return remaining.length <= 8
    }

    private fun isIncompleteIntent(value: String): Boolean {
        val normalized = value.replace(Regex("(?i)\\{.*$"), "").trim(' ', '。', '.', '！', '!', '？', '?')
        return normalized.matches(Regex("(?:没有){2,}|有一点点烦|我不会写|请修改一下(?:这个)?(?:离奇的)?错误|不知道|我想换|怎么")) ||
            normalized.length <= 1
    }

    private fun isExplicitNonFault(value: String): Boolean =
        Regex("AI.{0,12}偷|偷东西|不会写|只是?觉得|it's no use", RegexOption.IGNORE_CASE).containsMatchIn(value)

    private fun isCatalogOrParentalGovernanceIntent(value: String): Boolean =
        Regex("(?:家长管理|家长管控).{0,20}(?:我想|自己).{0,8}(?:管理|管控)|" +
            "(?:不能|无法).{0,8}自己下载.{0,30}(?:家长管理|家长管控|我想自己管理)|" +
            "(?:能不能|可以不可以).{0,20}(?:多来点|多加|增加|提供).{0,16}(?:游戏|应用)|" +
            "(?:希望|建议|请|想要|能否|可否).{0,12}(?:多来点|多加|增加|提供).{0,16}(?:游戏|应用)|" +
            "(?:添加|加入|上线).{0,12}游戏中心.{0,20}(?:下载|游戏)|" +
            "只能.{0,10}(?:有益|学习|益智).{0,16}不能.{0,8}(?:弄|玩|下载)?(?:游戏|应用)|" +
            "(?:应该|请|希望).{0,12}(?:把)?所有(?:程序|应用).{0,8}(?:录入|提供|上线)", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)

    private fun isStrongNoise(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.matches(Regex("(?i)^[a-z0-9]{10,}$"))) return true
        if (Regex("(?i)[a-z]{14,}").containsMatchIn(normalized)) return true
        if (normalized.length >= 60) {
            val latin = Regex("[a-z]", RegexOption.IGNORE_CASE).findAll(normalized).count()
            val faultWords = Regex("失败|错误|异常|无法|不能|卡顿|损坏|失效").findAll(normalized).count()
            if (latin >= 24 && faultWords == 0) return true
        }
        return false
    }

    private fun looksLikeNovelFault(value: String): Boolean = NOVEL_FAULT_CUE.containsMatchIn(value)

    private data class ClauseFact(
        val clause: String,
        val target: String?,
        val action: String?,
        val failureMode: String,
        val errorSignature: String?
    ) {
        fun identityTuple() = listOf(target, action, failureMode, errorSignature)
    }

    private fun facts(clause: String): ClauseFact? {
        val normalized = normalizeSynonyms(clause)
        val modes = detectedModes(normalized)
        if (modes.isEmpty()) return null
        val distinctModes = modes.map { it.second }.toSet()
        val causalSubmission = distinctModes == setOf("NO_RESPONSE", "SUBMIT") && normalized.contains("提交")
        val causalResult = causalResultMode(normalized, modes)
        if (distinctModes.size > 1 && !causalSubmission && causalResult == null) return null
        val mode = when {
            causalSubmission -> "SUBMIT"
            causalResult != null -> causalResult
            else -> modes.first().second
        }
        return ClauseFact(
            clause = clause,
            target = extractTarget(normalized, mode),
            action = ACTIONS.entries.firstOrNull { it.value.containsMatchIn(normalized) }?.key
                ?: impliedAction(mode),
            failureMode = mode,
            errorSignature = ErrorSignatureNormalizer().normalize(normalized)
        )
    }

    private fun impliedAction(mode: String): String? = when (mode) {
        "DOWNLOAD", "POST_DOWNLOAD_USAGE_FAILURE" -> "DOWNLOAD"
        "UPLOAD" -> "UPLOAD"
        "SUBMIT" -> "SUBMIT"
        "PLAYBACK" -> "PLAY"
        else -> null
    }

    private fun extractTarget(normalized: String, mode: String): String? {
        if (mode == "APP_MISSING") {
            val namedApp = Regex("(?:缺少|没有|添加)([^，。,.!?！？]{2,24})", RegexOption.IGNORE_CASE)
                .find(normalized)?.groupValues?.get(1)
                ?.removePrefix("我要的")?.removePrefix("所需")?.trim()
            if (!namedApp.isNullOrBlank()) return namedApp
        }
        if (mode == "DOWNLOAD") extractDownloadTarget(normalized)?.let { return it }
        return TARGETS.entries.firstOrNull { it.value.containsMatchIn(normalized) }?.key
    }

    private fun extractDownloadTarget(normalized: String): String? {
        val after = Regex("(?:不能|无法|下不了|为什么不能)\\s*下载\\s*([^，。,.!?！？]{2,24})", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.get(1)
        val before = Regex("([^，。,.!?！？]{2,24}?)(?:app|软件)?(?:无法下载|不能下载|下载不了)", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.get(1)
        return listOf(after, before).mapNotNull { raw ->
            raw?.trim(' ', '“', '”', '\'', '"')
                ?.replace(Regex("^(?:为什么|怎么|为何|我|还是|就是)+"), "")
                ?.replace(Regex("(?:为什么|怎么|为何)$"), "")
                ?.replace(Regex("(?:这个)?(?:app|应用|软件)$", RegexOption.IGNORE_CASE), "")
                ?.trim()?.takeIf { candidate ->
                    candidate.length in 2..24 && candidate !in GENERIC_DOWNLOAD_TARGETS &&
                        !Regex("^(?:下载|不了|失败|异常|搜不到|孩子|自己|直接|一直|更新)+$")
                            .matches(candidate)
                }
        }.firstOrNull()
    }

    private fun hasCompetingProblems(clause: String): Boolean {
        val normalized = normalizeSynonyms(clause)
        val modes = detectedModes(normalized)
        val distinct = modes.map { it.second }.toSet()
        return distinct.size > 1 && distinct != setOf("NO_RESPONSE", "SUBMIT") &&
            causalResultMode(normalized, modes) == null
    }

    /** 因果连接词后的故障是对外症状，连接词前的故障仅作为原因线索。 */
    private fun causalResultMode(normalized: String, modes: List<Triple<Int, String, String>>): String? {
        val connector = CAUSAL_CONNECTOR.find(normalized) ?: return null
        val after = modes.filter { it.first > connector.range.last }
        return after.firstOrNull()?.second
    }

    /** 具体对象/阶段故障优先于其子串触发的通用模式，避免把识别或下载后的使用失败归入宽泛模式。 */
    private fun detectedModes(normalized: String): List<Triple<Int, String, String>> {
        val matches = FAILURE_MODES.mapNotNull { (mode, pattern) ->
            pattern.find(normalized)?.let { Triple(it.range.first, mode, it.value) }
        }
        return matches.filterNot { (_, mode, _) ->
            mode == "RECOGNITION_ERROR" && matches.any { it.second == "BOOK_RECOGNITION_FAILURE" } ||
                mode == "DOWNLOAD" && matches.any { it.second == "POST_DOWNLOAD_USAGE_FAILURE" } ||
                mode == "WRITING_TRAJECTORY_ERROR" && matches.any {
                    it.second == "WRITING_FALSE_ACCEPT" || it.second == "WRITING_FALSE_REJECT" ||
                        it.second == "WRITING_PRONUNCIATION_ERROR"
                } ||
                mode == "CONTENT_ERROR" && matches.any {
                    it.second == "QUESTION_RECOGNITION_ERROR" || it.second == "READ_ALOUD_CONTENT_ERROR" ||
                        it.second == "GRADING_ERROR" || it.second == "ANSWER_CONTENT_ERROR" ||
                        it.second == "QUESTION_INPUT_ERROR" || it.second == "QUESTION_MISMATCH" ||
                        it.second == "PROCESS_ERROR" || it.second == "ANSWER_VARIANCE" ||
                        it.second == "EXPRESSION_ERROR"
                } ||
                mode == "ANSWER_CONTENT_ERROR" && matches.any {
                    it.second == "GRADING_ERROR" || it.second == "PROCESS_ERROR" ||
                        it.second == "EXPLANATION_RESULT_ERROR"
                } ||
                mode == "EXPRESSION_ERROR" && matches.any { it.second == "READ_ALOUD_CONTENT_ERROR" } ||
                mode == "GRADING_ERROR" && matches.any {
                    it.second == "WRITING_FALSE_ACCEPT" || it.second == "WRITING_FALSE_REJECT"
                } ||
                mode == "EXPLANATION_ERROR" && matches.any {
                    it.second == "EXPLANATION_METHOD_ERROR" || it.second == "EXPLANATION_RESULT_ERROR"
                } ||
                mode == "MEDIA_ERROR" && matches.any { it.second == "AUDIO_GRADING_ERROR" }
        }.sortedBy { it.first }
    }

    /** 标题和正文常重复描述同一问题；只有非空结构事实真正冲突时才拒绝合并。 */
    private fun reconcileFacts(facts: List<ClauseFact>): ClauseFact? {
        if (facts.isEmpty()) return null
        if (facts.any { left -> facts.any { right -> !compatible(left, right) } }) return null
        val primary = facts.maxWithOrNull(compareBy<ClauseFact> { knownFieldCount(it) }.thenBy { it.clause.length })!!
        return primary.copy(
            target = facts.firstNotNullOfOrNull(ClauseFact::target),
            action = facts.firstNotNullOfOrNull(ClauseFact::action),
            errorSignature = facts.firstNotNullOfOrNull(ClauseFact::errorSignature)
        )
    }

    private fun compatible(left: ClauseFact, right: ClauseFact): Boolean =
        left.failureMode == right.failureMode && compatible(left.target, right.target) &&
            compatible(left.action, right.action) && compatible(left.errorSignature, right.errorSignature)

    private fun compatible(left: String?, right: String?) = left == null || right == null || left.equals(right, true)

    private fun knownFieldCount(fact: ClauseFact) = listOf(fact.target, fact.action, fact.errorSignature).count { it != null }

    private fun canonicalSymptom(fact: ClauseFact): String {
        val target = fact.target
        return when (fact.failureMode) {
            "CRASH" -> if (target == null) "应用使用过程中闪退" else "使用${target}时应用闪退"
            "FREEZE" -> if (target == null) "使用过程中出现卡顿" else "使用${target}时出现卡顿"
            "NO_AUDIO" -> if (target == null) "使用过程中没有声音" else "使用${target}时没有声音"
            "PLAYBACK" -> if (target == null) "媒体内容无法正常播放" else "播放${target}时无法正常播放"
            "NETWORK" -> "设备网络连接异常"
            "NAVIGATION" -> if (target == null) "无法进入目标页面" else "无法进入${target}"
            "RECOGNITION_ERROR" -> if (target == null) "手写字符识别错误" else "${target}手写字符识别错误"
            "GRADING_ERROR" -> if (target == null) "作答批改结果错误" else "${target}作答批改结果错误"
            "QUESTION_RECOGNITION_ERROR" -> "题目识别结果错误"
            "READ_ALOUD_CONTENT_ERROR" -> "题目或算式朗读内容错误"
            "ANSWER_CONTENT_ERROR" -> "题目答案内容错误"
            "QUESTION_INPUT_ERROR" -> "题目输入内容错误"
            "PROCESS_ERROR" -> "题目解答过程错误"
            "ANSWER_VARIANCE" -> "同一题目答案不一致"
            "EXPRESSION_ERROR" -> "题目算式内容错误"
            "CONTENT_ERROR" -> if (target == null) "题目内容存在错误" else "${target}内容存在错误"
            "BOOK_RECOGNITION_FAILURE" -> if (target == null) "绘本无法识别" else "${target}无法识别"
            "MEDIA_ERROR" -> if (target == null) "音视频内容出现错误" else "${target}出现错误"
            "AUDIO_GRADING_ERROR" -> "跟读结果判定错误"
            "QUESTION_MISMATCH" -> if (target == null) "题目内容不匹配" else "${target}内容不匹配"
            "WRITING_FALSE_ACCEPT" -> "书写错误被误判为正确"
            "WRITING_FALSE_REJECT" -> "正确书写被误判为错误"
            "WRITING_PRONUNCIATION_ERROR" -> "书写题读音判定错误"
            "WRITING_TRAJECTORY_ERROR" -> "同步学书写轨迹出现异常"
            "EXPLANATION_ERROR" -> if (target == null) "题目讲解存在错误" else "${target}讲解存在错误"
            "EXPLANATION_METHOD_ERROR" -> "题目讲解方法错误"
            "EXPLANATION_RESULT_ERROR" -> "题目讲解结论错误"
            "EXPLANATION_INCOMPLETE" -> if (target == null) "题目讲解不完整" else "${target}讲解不完整"
            "CONTENT_MISSING" -> if (target == null) "内容存在缺失" else "${target}内容存在缺失"
            "APP_MISSING" -> if (target == null) "应用目录缺少所需应用" else "缺少${target}"
            "PERMISSION_ENTRY_UNCLEAR" -> "无法找到权限设置入口"
            "LOADING_SLOW" -> if (target == null) "内容加载耗时过长" else "${target}加载耗时过长"
            "RESPONSE_TOO_VERBOSE" -> if (target == null) "讲解过慢或过于冗长" else "${target}讲解过慢或过于冗长"
            "USAGE_TIME_DISPLAY_ERROR" -> "使用时长提示与实际不一致"
            "QUESTION_ASSOCIATION_ERROR" -> if (target == null) "内容挂靠关系错误" else "${target}挂靠关系错误"
            "UI_CONTROL_FAILURE" -> "页面控件无法正常退出或移除"
            "DISPLAY_ERROR" -> if (target == null) "页面内容显示错误" else "${target}显示错误"
            "SKIP_FAILURE" -> "内容跳过功能失效"
            "CONTENT_FILTER_ISSUE" -> "内容被判定为不健康"
            "IMAGE_DAMAGED" -> "图片资源损坏"
            "INTERACTION_FAILURE" -> if (target == null) "页面交互失败" else "${target}无法正常操作"
            "UPLOAD" -> if (target == null) "内容上传失败" else "上传${target}时失败"
            "DOWNLOAD" -> if (target == null) "内容下载失败" else "下载${target}时失败"
            "POST_DOWNLOAD_USAGE_FAILURE" -> if (target == null) "下载后无法正常使用" else "${target}下载后无法正常使用"
            "SUBMIT" -> if (target == null) "内容提交失败" else "提交${target}时失败"
            else -> fact.clause.take(200)
        }
    }

    private fun splitClauses(text: String): List<String> = text
        .replace(Regex("(?:但是|但|然而|同时|另外|此外|而且)"), "。")
        .split(Regex("[。！？!?；;\\n]+"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun clean(value: String): String = value.trim()
        .replace(Regex("^(?:【[^】]+】\\s*)+"), "")
        .replace(Regex("(?:客户描述|用户反馈|问题描述|现象)\\s*[:：-]*"), "")
        .replace(Regex("\\[[A-Z_]{2,32}]"), " ")
        .replace(Regex("\\s+"), " ")

    private fun isLowInformation(value: String): Boolean {
        if (value.isBlank()) return true
        val trimmed = value.trim(' ', '。', '.', '！', '!')
        if (trimmed.lowercase() in LOW_INFORMATION) return true
        if (trimmed.matches(Regex("^[\\p{P}\\p{S}\\d]+$"))) return true
        if (trimmed.matches(Regex("^(.{1,4})\\1+$"))) return true
        if (trimmed.matches(Regex("(?i)^[a-z0-9]{10,}$"))) return true
        val semanticPayload = trimmed
            .replace(Regex("^(?:应用程序|其他错误|其他问题|其他|建议|应用缺失|太空资源)[。.:：]*"), "")
            .trim()
        if (semanticPayload.length <= 1) return true
        if (semanticPayload.matches(Regex("(?i)^[a-z0-9]{10,}$"))) return true
        if (Regex("(?i)(?:https?://|www\\.)").findAll(trimmed).count() >= 2) return true
        return false
    }

    private fun normalizeSynonyms(value: String) = value.lowercase()
        .replace("crashed", "闪退").replace("crash", "闪退")
        .replace("freezes", "卡死").replace("frozen", "卡死").replace("freeze", "卡死")
        .replace("cannot play", "播放失败").replace("can't play", "播放失败")
        .replace("no sound", "没有声音").replace("no audio", "没有声音")
        .replace(Regex("崩溃|闪崩"), "闪退")
        .replace(Regex("卡住|冻结|一直很卡|非常卡"), "卡顿")
        .replace(Regex("进不去|打不开|无法打开"), "无法进入")
        .replace(Regex("识别有误|识别错了|识别错|识别不准确|识别的啥"), "识别错误")
        .replace(Regex("判错|改错"), "批改错误")

    companion object {
        const val VERSION = "cluster-classification-v3-shadow-11"
        private const val IDENTITY_SCHEMA_VERSION = "symptom-fingerprint-v1"
        private val LEGACY_INFORMATION_MARKER = Regex("\\[(?:NAME_REMOVED|LONG_NUMBER_REMOVED|LONG_ID|LONG_TOKEN_REVIEW|URL|TECH_ID)]", RegexOption.IGNORE_CASE)
        private val NOVEL_FAULT_CUE = Regex("错误|错位|故障|失败|异常|无法|不能|不了|不对|不准|缺失|少了|不显示|不工作|不响应|太慢|卡顿|损坏|失效", RegexOption.IGNORE_CASE)
        private val CAUSAL_CONNECTOR = Regex("导致|造成|引起|因此|从而|so that|caus(?:e|es|ed|ing)", RegexOption.IGNORE_CASE)
        private val LOW_INFORMATION = setOf("", "无", "一", "1", "测试", "unknown", "other", "不好", "很差", "请处理", "帮忙看看")
        private val PROHIBITED_APPLICATION_KEYS = setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK")
        private val GENERIC_DOWNLOAD_TARGETS = setOf(
            "应用", "app", "软件", "这个", "这个应用", "其他应用", "内容"
        )
        private val TARGETS = linkedMapOf(
            "书写轨迹" to Regex("书写轨迹|writing trajectory", RegexOption.IGNORE_CASE),
            "绘本" to Regex("绘本|picture book", RegexOption.IGNORE_CASE),
            "音视频" to Regex("音视频|音频|audio|media", RegexOption.IGNORE_CASE),
            "题目解析" to Regex("题目解析|解析|questionInfo|questionId", RegexOption.IGNORE_CASE),
            "题目" to Regex("题目|答案|算式|方程|question", RegexOption.IGNORE_CASE),
            "图片" to Regex("图片|image", RegexOption.IGNORE_CASE),
            "游戏" to Regex("游戏|game", RegexOption.IGNORE_CASE),
            "错题本" to Regex("错题本|wrong question", RegexOption.IGNORE_CASE),
            "AI助教" to Regex("ai助教|助教|tutor", RegexOption.IGNORE_CASE),
            "题库" to Regex("题库|question bank", RegexOption.IGNORE_CASE),
            "作业" to Regex("作业|homework", RegexOption.IGNORE_CASE),
            "视频" to Regex("视频|video", RegexOption.IGNORE_CASE),
            "课程" to Regex("课程|course|上课", RegexOption.IGNORE_CASE),
            "麦克风" to Regex("麦克风|microphone|mic\\b", RegexOption.IGNORE_CASE),
            "摄像头" to Regex("摄像头|camera", RegexOption.IGNORE_CASE),
            "登录" to Regex("登录|login", RegexOption.IGNORE_CASE),
            "支付" to Regex("支付|payment", RegexOption.IGNORE_CASE)
        )
        private val ACTIONS = linkedMapOf(
            "UPLOAD" to Regex("上传|upload", RegexOption.IGNORE_CASE),
            "DOWNLOAD" to Regex("下载|download", RegexOption.IGNORE_CASE),
            "SUBMIT" to Regex("提交|submit", RegexOption.IGNORE_CASE),
            "PLAY" to Regex("播放|play", RegexOption.IGNORE_CASE),
            "START" to Regex("启动|start|launch", RegexOption.IGNORE_CASE),
            "CLICK" to Regex("点击|click|tap", RegexOption.IGNORE_CASE),
            "ENTER" to Regex("进入|enter", RegexOption.IGNORE_CASE),
            "OPEN" to Regex("打开|open", RegexOption.IGNORE_CASE)
        )
        private val FAILURE_MODES = linkedMapOf(
            "POST_DOWNLOAD_USAGE_FAILURE" to Regex(
                "(?:下载(?:完成|成功|好了|了|后)).{0,12}(?:不能|无法|用不了|不可用).{0,4}(?:使用|打开|运行)?",
                RegexOption.IGNORE_CASE
            ),
            "BOOK_RECOGNITION_FAILURE" to Regex(
                "绘本.{0,8}(?:无法识别|识别不了|识别失败|不能识别|识别(?:有误|有错|错误|不准确))",
                RegexOption.IGNORE_CASE
            ),
            "MEDIA_ERROR" to Regex("(?:音视频|音频|媒体).{0,8}(?:错|错误|异常|失败)|(?:错|错误|异常|失败).{0,8}(?:音视频|音频|媒体)", RegexOption.IGNORE_CASE),
            "QUESTION_MISMATCH" to Regex(
                "(?:题目|同步学).{0,8}(?:不匹配|不对应)|(?:不匹配|不对应).{0,8}(?:题目|同步学)|" +
                    "(?:讲的是|变成|显示的是).{0,6}(?:另一道|别的)题|(?:别的题目|另一道题).{0,10}(?:不是|讲错)|" +
                    "(?:选一选的)?题目.{0,8}不是这个",
                RegexOption.IGNORE_CASE
            ),
            "QUESTION_INPUT_ERROR" to Regex(
                "题目.{0,8}(?:输入|录入|单词).{0,8}(?:错|错误|有误)|题目.{0,6}(?:认别|识别).{0,6}(?:错|错误|有误)",
                RegexOption.IGNORE_CASE
            ),
            "PROCESS_ERROR" to Regex("(?:不是答案|答案没错).{0,8}(?:过程|步骤).{0,6}(?:错|错误|有误)|(?:过程|步骤).{0,6}(?:错|错误|有误)", RegexOption.IGNORE_CASE),
            "ANSWER_VARIANCE" to Regex("(?:每次|两次|多次).{0,12}答案.{0,8}(?:不一样|不同|变化|变了)|答案.{0,8}(?:每次|两次|多次).{0,8}(?:不一样|不同)", RegexOption.IGNORE_CASE),
            "EXPRESSION_ERROR" to Regex("(?:算式|方程).{0,8}(?:错|错误|有误|不对)", RegexOption.IGNORE_CASE),
            "WRITING_FALSE_ACCEPT" to Regex(
                "(?:(?:错了|写错了|写错).{0,24}|笔画顺序.{0,8}(?:不对|错误).{0,12})(?:说|判|认为).{0,4}(?:对|正确).{0,160}书写轨迹",
                RegexOption.IGNORE_CASE
            ),
            "WRITING_FALSE_REJECT" to Regex(
                "(?:(?:写对了|写的是对的|明明.{0,8}对|本来.{0,8}对|笔画.{0,8}对).{0,30}" +
                    "(?:说|判|给|弄|看|打|报|算).{0,6}(?:错|错误)|" +
                    "(?:正常写字|写对的字|[✓√]对了|完美的字|字不好看|手写的.{0,6}不好写).{0,18}" +
                    "(?:说|算|给|报|跑|变成|批).{0,6}(?:错|错误|错字)|" +
                    "(?:他)?给我批错|不要.{0,10}(?:报错误|变成红色的错字)).{0,160}书写轨迹",
                RegexOption.IGNORE_CASE
            ),
            "WRITING_PRONUNCIATION_ERROR" to Regex("读音.{0,8}(?:错|错误|有误).{0,160}书写轨迹", RegexOption.IGNORE_CASE),
            "WRITING_TRAJECTORY_ERROR" to Regex("(?:书写轨迹).{0,40}(?:不好|错|错误|异常)|(?:不好|错|错误|异常).{0,40}(?:书写轨迹)", RegexOption.IGNORE_CASE),
            "EXPLANATION_INCOMPLETE" to Regex("(?:讲解|讲题|解析).{0,12}(?:不完整|没讲完|只讲|少讲|缺少)|(?:没告诉|未提供).{0,8}(?:算式|步骤|过程)", RegexOption.IGNORE_CASE),
            "EXPLANATION_METHOD_ERROR" to Regex("(?:讲解|讲题|解析).{0,10}(?:方法|方式|思路).{0,8}(?:错|错误|有误|不对)|(?:方法|方式|思路).{0,8}(?:错|错误|有误).{0,8}(?:讲解|讲题|解析)", RegexOption.IGNORE_CASE),
            "EXPLANATION_RESULT_ERROR" to Regex("(?:讲解|讲题|解析).{0,10}(?:总结|结论|答案).{0,8}(?:错|错误|有误|不对)", RegexOption.IGNORE_CASE),
            "EXPLANATION_ERROR" to Regex("(?:讲解|讲题|解析).{0,12}(?:错|错误|有误|不对)|(?:错|错误|有误).{0,8}(?:讲解|讲题|解析)", RegexOption.IGNORE_CASE),
            "CONTENT_MISSING" to Regex("(?:课程|课|章节|内容).{0,12}(?:少了|缺少|缺失|没有)", RegexOption.IGNORE_CASE),
            "APP_MISSING" to Regex("(?:^应用缺失$|缺少.{0,12}(?:应用|程序|游戏)|缺少.{0,16}(?:数学|英语|语文|中心|助手|课堂)|没有.{0,12}(?:应用|程序)|没有我要的程序|添加游戏中心)", RegexOption.IGNORE_CASE),
            "PERMISSION_ENTRY_UNCLEAR" to Regex("权限.{0,10}(?:在哪里|哪儿|入口|找不到)|(?:在哪里|哪儿).{0,6}权限", RegexOption.IGNORE_CASE),
            "LOADING_SLOW" to Regex("加载.{0,10}(?:好久|很久|太久|过慢|慢)|(?:好久|很久|太久).{0,8}(?:没好|没有好|加载)", RegexOption.IGNORE_CASE),
            "RESPONSE_TOO_VERBOSE" to Regex("(?:讲解|解析).{0,10}(?:太多|太长|啰嗦)|(?:讲快|快一点).{0,12}(?:讲解|解析|这么多)", RegexOption.IGNORE_CASE),
            "USAGE_TIME_DISPLAY_ERROR" to Regex("(?:分钟|时长).{0,18}(?:说了|显示|提示).{0,8}(?:分钟|时长)|实际.{0,10}(?:分钟|时长).{0,10}(?:显示|提示)", RegexOption.IGNORE_CASE),
            "QUESTION_ASSOCIATION_ERROR" to Regex("(?:题目|上面|内容).{0,8}(?:有关系吗|没关系|无关|挂靠错误)", RegexOption.IGNORE_CASE),
            "UI_CONTROL_FAILURE" to Regex("(?:退出|删除|移除|退的时候).{0,16}(?:不掉|不了|无效|失败)|(?:控件|按钮).{0,8}(?:不掉|不了|无效|失败)", RegexOption.IGNORE_CASE),
            "DISPLAY_ERROR" to Regex("显示.{0,6}(?:错|错误|异常|不对)|显示错误", RegexOption.IGNORE_CASE),
            "SKIP_FAILURE" to Regex("跳过.{0,8}(?:失效|失败|不了|无效)", RegexOption.IGNORE_CASE),
            "CONTENT_FILTER_ISSUE" to Regex("不健康.{0,6}内容|内容.{0,6}不健康", RegexOption.IGNORE_CASE),
            "IMAGE_DAMAGED" to Regex("图片.{0,8}(?:损坏|坏掉|破损)|(?:损坏|坏掉|破损).{0,8}图片", RegexOption.IGNORE_CASE),
            "INTERACTION_FAILURE" to Regex("(?:无法|不能).{0,6}(?:提问|点击|操作)|(?:提问|点击|操作).{0,6}(?:不了|失败|无效)", RegexOption.IGNORE_CASE),
            "CRASH" to Regex("闪退|崩溃|crash", RegexOption.IGNORE_CASE),
            "FREEZE" to Regex("卡死|卡住|卡顿|冻结|freeze|frozen", RegexOption.IGNORE_CASE),
            "NO_RESPONSE" to Regex("无响应|没反应|转圈|no response", RegexOption.IGNORE_CASE),
            "NO_AUDIO" to Regex("无声|没有声音|没声音|no sound|no audio", RegexOption.IGNORE_CASE),
            "RECOGNITION_ERROR" to Regex(
                "识别.{0,8}(?:错|错误|有误|不准确|的啥)|扫出来.{0,8}(?:不是|不对|错误)|认成.{0,8}(?:别的|其他|错误)",
                RegexOption.IGNORE_CASE
            ),
            "QUESTION_RECOGNITION_ERROR" to Regex(
                "(?:题目|算式|答案).{0,6}识别.{0,8}(?:错|错误|有误|不对|不准确)|识别.{0,8}(?:错|错误|有误|不对|不准确).{0,6}(?:题目|算式|答案)",
                RegexOption.IGNORE_CASE
            ),
            "READ_ALOUD_CONTENT_ERROR" to Regex(
                "(?:题目|答案|算式|方程).{0,8}(?:朗读|读法|读音).{0,8}(?:错|错误|有误|不对)|" +
                    "(?:朗读|读法|读音).{0,8}(?:错|错误|有误|不对).{0,8}(?:题目|答案|算式|方程)",
                RegexOption.IGNORE_CASE
            ),
            "GRADING_ERROR" to Regex(
                "批改.{0,8}(?:错|错误|有误)|判错|改错|说我(?:写)?错|答案.{0,8}变成错|" +
                    "(?:答案|我).{0,12}(?:明明|写的|说的).{0,12}(?:对|正确).{0,20}(?:判|算|说|给|变成).{0,8}(?:错|错误)|" +
                    "答案都是.{0,12}(?:我的|我).{0,8}(?:错|错误)|" +
                    "(?:两种答案|顺序不同|填空顺序相反).{0,20}(?:算|判|报).{0,6}(?:错|错误)",
                RegexOption.IGNORE_CASE
            ),
            "ANSWER_CONTENT_ERROR" to Regex("答案.{0,10}(?:错|错误|有误|不对)|(?:错|错误|有误|不对).{0,8}答案", RegexOption.IGNORE_CASE),
            "CONTENT_ERROR" to Regex("(?:题目|内容|答案|算式|方程|.{0,6}的数).{0,10}(?:错|错误|有误|不对|不是)|(?:等于|算).{0,20}结果.{0,20}等于", RegexOption.IGNORE_CASE),
            "AUDIO_GRADING_ERROR" to Regex(
                "(?:照着|跟着).{0,12}音频.{0,12}(?:读|说).{0,12}(?:说错|判错|错误)",
                RegexOption.IGNORE_CASE
            ),
            "PLAYBACK" to Regex("无法.{0,2}播放|播放失败|黑屏|cannot play", RegexOption.IGNORE_CASE),
            "NETWORK" to Regex("无网络|断网|无法联网|网络.{0,6}(?:异常|失败|断开|不可用|不好)", RegexOption.IGNORE_CASE),
            "NAVIGATION" to Regex("无法进入|进不去|打不开", RegexOption.IGNORE_CASE),
            "DOWNLOAD" to Regex("下载.{0,12}(?:失败|异常|不能|无法|不了)|(?:无法|不能|下不了).{0,6}(?:下载|游戏)|根本下不了", RegexOption.IGNORE_CASE),
            "UPLOAD" to Regex("上传.{0,8}(?:失败|异常|不能|无法)|无法上传", RegexOption.IGNORE_CASE),
            "SUBMIT" to Regex("提交.{0,10}(?:失败|无法|不能)|无法完成提交", RegexOption.IGNORE_CASE)
        )
    }
}

/** 新 generation 使用：保留 v3 症状本体，仅把 application 待定记录从提前终止改为隔离症状簇。 */
class AtomicProblemExtractorV4 : AtomicProblemExtraction {
    private val delegate = AtomicProblemExtractorV3(PendingApplicationPolicy.ISOLATED_SYMPTOM_CLUSTER)
    private val ontology = EngineeringSymptomOntologyV4()
    override fun extract(input: ClusterClassificationInput): AtomicProblemProfile =
        ontology.enrich(input, delegate.extract(input))

    companion object {
        const val VERSION = "cluster-classification-v4-capability-fault-family-shadow"
    }
}

typealias AtomicProblemExtractorV2 = AtomicProblemExtractorV3

enum class BoundaryEvidence { MATCH, CONFLICT, UNKNOWN }

/** 三态问题边界：结构字段缺失只能判定为未知，不能作为正向兼容证据。 */
class ProblemBoundaryPolicyV2 {
    fun compare(left: AtomicProblemProfile, right: AtomicProblemProfile): BoundaryEvidence {
        if (left.status != ProblemClassificationStatus.ACTIONABLE || right.status != ProblemClassificationStatus.ACTIONABLE) {
            return BoundaryEvidence.CONFLICT
        }
        if (left.applicationPartitionKey() != right.applicationPartitionKey()) return BoundaryEvidence.CONFLICT
        if (left.capabilityDomain != null && right.capabilityDomain != null &&
            left.capabilityDomain != right.capabilityDomain) return BoundaryEvidence.CONFLICT
        if (left.faultFamily != null && right.faultFamily != null && left.faultFamily != right.faultFamily) {
            return BoundaryEvidence.CONFLICT
        }
        if (different(left.target, right.target) || different(left.action, right.action) ||
            different(left.failureMode, right.failureMode) || different(left.errorSignature, right.errorSignature)) {
            return BoundaryEvidence.CONFLICT
        }
        val anchors = listOf(left.target to right.target, left.action to right.action,
            left.failureMode to right.failureMode, left.errorSignature to right.errorSignature)
        return if (anchors.any { (a, b) -> a != null && b != null && a.equals(b, true) }) BoundaryEvidence.MATCH
        else BoundaryEvidence.UNKNOWN
    }

    private fun different(left: String?, right: String?) = left != null && right != null && !left.equals(right, true)
}

enum class SemanticCandidateJudgment { NOT_EVALUATED, ACCEPT, PROVISIONAL, REJECT }

data class ShadowCandidate(
    val profile: AtomicProblemProfile,
    val score: BigDecimal,
    val boundary: BoundaryEvidence,
    val semanticScore: BigDecimal? = null,
    val semanticJudgment: SemanticCandidateJudgment = SemanticCandidateJudgment.NOT_EVALUATED,
    val semanticEligibilityReason: SemanticAcceptEligibilityReason = SemanticAcceptEligibilityReason.ELIGIBLE
)

/** 影子验证使用的 application 强过滤召回与可解释加权相似度。 */
interface ShadowCandidateRecall {
    fun recall(query: AtomicProblemProfile, candidates: Collection<AtomicProblemProfile>, topK: Int = 20): List<ShadowCandidate>
    fun similarity(left: AtomicProblemProfile, right: AtomicProblemProfile): BigDecimal
}

class ClusterCandidateRecallV2(private val boundary: ProblemBoundaryPolicyV2 = ProblemBoundaryPolicyV2()) : ShadowCandidateRecall {
    override fun recall(query: AtomicProblemProfile, candidates: Collection<AtomicProblemProfile>, topK: Int): List<ShadowCandidate> {
        ensureClusterRequest(topK in 1..50) { "CLUSTER_SHADOW_TOP_K_OUT_OF_RANGE" }
        return candidates.asSequence()
            .filter { it.sourceId != query.sourceId &&
                it.applicationPartitionKey() == query.applicationPartitionKey() }
            .map { candidate -> ShadowCandidate(candidate, similarity(query, candidate), boundary.compare(query, candidate)) }
            .filter { it.boundary != BoundaryEvidence.CONFLICT }
            .sortedWith(compareByDescending<ShadowCandidate>(ShadowCandidate::score)
                .thenBy { it.profile.identityKey }.thenBy { it.profile.sourceId })
            .take(topK).toList()
    }

    override fun similarity(left: AtomicProblemProfile, right: AtomicProblemProfile): BigDecimal {
        if (left.applicationPartitionKey() != right.applicationPartitionKey()) return BigDecimal.ZERO.setScale(6)
        var score = BigDecimal.ZERO
        if (left.failureMode != null && left.failureMode == right.failureMode) score += BigDecimal("0.45")
        if (left.target != null && left.target == right.target) score += BigDecimal("0.25")
        if (left.action != null && left.action == right.action) score += BigDecimal("0.15")
        if (left.errorSignature != null && left.errorSignature == right.errorSignature) score += BigDecimal("0.10")
        val text = dice(normalizeClusterSimilarityText(left.canonicalSymptom.orEmpty()),
            normalizeClusterSimilarityText(right.canonicalSymptom.orEmpty()))
        score += text.multiply(BigDecimal("0.05"))
        return score.min(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP)
    }

    private fun dice(left: String, right: String): BigDecimal {
        if (left.isBlank() || right.isBlank()) return BigDecimal.ZERO
        val lg = grams(left); val rg = grams(right)
        if (lg.isEmpty() || rg.isEmpty()) return if (left == right) BigDecimal.ONE else BigDecimal.ZERO
        return BigDecimal.valueOf(2.0 * lg.intersect(rg).size / (lg.size + rg.size).toDouble())
    }

    private fun grams(value: String) = if (value.length < 2) setOf(value)
        else (0 until value.length - 1).map { value.substring(it, it + 2) }.toSet()
}

/**
 * 纯计算影子分类器。稳定身份哈希保证重复运行及输入顺序变化不会造成漂移。
 * 本类不修改 Cluster、MAIN 或 generation；可选模型调用由召回实现负责并在数据库事务外执行。
 */
class ClusterShadowClassifierV3(
    private val extractor: AtomicProblemExtraction = AtomicProblemExtractorV3(),
    private val recall: ShadowCandidateRecall = ClusterCandidateRecallV2(),
    private val names: ClusterNamePolicy = DeterministicClusterNamePolicy()
) {
    fun classify(inputs: Collection<ClusterClassificationInput>): ShadowClusterResult {
        ensureClusterRequest(inputs.map { it.sourceId }.distinct().size == inputs.size) {
            "CLUSTER_SHADOW_SOURCE_DUPLICATED"
        }
        val profiles = inputs.associate { it.sourceId to extractor.extract(it) }
        val automaticProfiles = inputs.asSequence()
            .filter { it.manualConclusion == null }
            .map { input -> profiles[input.sourceId] ?: clusterInvariantViolation("CLUSTER_SHADOW_PROFILE_MISSING") }
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .sortedWith(compareBy<AtomicProblemProfile> { it.applicationPartitionKey() }.thenBy { it.identityKey }
                .thenBy { it.sourceId })
            .toList()

        val built = mutableListOf<WorkingCluster>()
        val builtByApplication = mutableMapOf<String, MutableList<WorkingCluster>>()
        val byIdentity = mutableMapOf<String, WorkingCluster>()
        automaticProfiles.forEach { profile ->
            val exact = profile.identityKey?.let(byIdentity::get)
            if (exact != null) {
                exact.add(profile)
                return@forEach
            }

            if (profile.evidenceSource == ProblemEvidenceSource.SOURCE_LABEL) {
                val provisional = WorkingCluster(profile, provisional = true).also { cluster ->
                    built += cluster
                    builtByApplication.getOrPut(profile.applicationPartitionKey()) { mutableListOf() } += cluster
                }
                profile.identityKey?.let { byIdentity[it] = provisional }
                return@forEach
            }

            val applicationCandidates = builtByApplication[profile.applicationPartitionKey()].orEmpty()
            val recalled = recall.recall(profile, applicationCandidates.map(WorkingCluster::representative), TOP_K)
            val candidates = recalled
                .filter { it.boundary == BoundaryEvidence.MATCH && it.score >= LINK_THRESHOLD &&
                    it.semanticJudgment != SemanticCandidateJudgment.PROVISIONAL &&
                    it.semanticJudgment != SemanticCandidateJudgment.REJECT }
                .mapNotNull { candidate ->
                    built.firstOrNull { it.representative.sourceId == candidate.profile.sourceId }
                        ?.takeIf { it.accepts(profile) }
                        ?.let { it to candidate }
                }
            val best = candidates.firstOrNull()
            val runnerUp = candidates.drop(1).firstOrNull()
            val semanticProvisional = recalled.any {
                it.boundary == BoundaryEvidence.MATCH &&
                    it.semanticJudgment == SemanticCandidateJudgment.PROVISIONAL
            }
            if (best != null && runnerUp != null && best.second.score - runnerUp.second.score <= COMPETITION_MARGIN) {
                val provisional = WorkingCluster(profile, provisional = true).also { cluster ->
                    built += cluster
                    builtByApplication.getOrPut(profile.applicationPartitionKey()) { mutableListOf() } += cluster
                }
                profile.identityKey?.let { byIdentity[it] = provisional }
                return@forEach
            }
            if (best == null && semanticProvisional) {
                val provisional = WorkingCluster(profile, provisional = true).also { cluster ->
                    built += cluster
                    builtByApplication.getOrPut(profile.applicationPartitionKey()) { mutableListOf() } += cluster
                }
                profile.identityKey?.let { byIdentity[it] = provisional }
                return@forEach
            }
            val cluster = if (best == null) WorkingCluster(profile).also { created ->
                built += created
                builtByApplication.getOrPut(profile.applicationPartitionKey()) { mutableListOf() } += created
            } else best.first.also { it.add(profile) }
            profile.identityKey?.let { byIdentity[it] = cluster }
        }

        val clusterBySource = built.flatMap { cluster -> cluster.members.map { it.sourceId to cluster } }.toMap()
        val assignments = inputs.sortedBy { it.sourceId }.map { input ->
            input.manualConclusion?.takeIf { input.contentDisposition == ShadowContentDisposition.ELIGIBLE }?.let { manual ->
                val profile = profiles[input.sourceId]
                    ?: clusterInvariantViolation("CLUSTER_SHADOW_PROFILE_MISSING")
                return@map ShadowClusterAssignment(input.sourceId, profile.status,
                    manual.clusterKey, manual.clusterName, "MANUAL_LOCKED", BigDecimal.ONE.setScale(6),
                    "人工审核结论优先，影子重跑不得覆盖", true)
            }
            val profile = profiles[input.sourceId]
                ?: clusterInvariantViolation("CLUSTER_SHADOW_PROFILE_MISSING")
            if (profile.status != ProblemClassificationStatus.ACTIONABLE) {
                return@map ShadowClusterAssignment(input.sourceId, profile.status, null, null,
                    "TERMINAL", null, profile.reason, false)
            }
            val cluster = clusterBySource[profile.sourceId]
                ?: clusterInvariantViolation("CLUSTER_SHADOW_ASSIGNMENT_MISSING")
            val representative = cluster.representative
            val creatorSourceId = cluster.members.minOfOrNull(AtomicProblemProfile::sourceId)
                ?: clusterInvariantViolation("CLUSTER_SHADOW_CLUSTER_MEMBER_MISSING")
            val exactIdentity = profile.identityKey == representative.identityKey
            val score = if (profile.sourceId == creatorSourceId) BigDecimal.ZERO.setScale(6)
                else recall.similarity(profile, representative)
            ShadowClusterAssignment(
                sourceId = input.sourceId,
                status = profile.status,
                clusterKey = "symptom:${representative.identityKey}",
                clusterName = names.name(representative),
                decision = when {
                    cluster.provisional && profile.sourceId == creatorSourceId -> "PROVISIONAL_NEW_CLUSTER"
                    cluster.provisional -> "PROVISIONAL_LINK_EXISTING"
                    profile.sourceId == creatorSourceId -> "NEW_CLUSTER"
                    else -> "LINK_EXISTING"
                },
                score = score,
                reason = when {
                    cluster.provisional && profile.evidenceSource == ProblemEvidenceSource.SOURCE_LABEL ->
                        "仅有来源标签证据，自动创建隔离的临时候选簇"
                    cluster.provisional -> "候选竞争未达到唯一关联条件，自动创建隔离的临时候选簇"
                    profile.sourceId == creatorSourceId -> "同应用不存在可关联的症状簇，影子新建"
                    exactIdentity -> "稳定症状指纹一致"
                    else -> "候选召回通过三态边界校验且相似度达到关联阈值"
                },
                manualLocked = false
            )
        }
        return ShadowClusterResult(assignments, assignments.mapNotNull { it.clusterKey }.toSet().size,
            assignments.count { it.clusterKey == null })
    }

    /** 簇级冲突集防止缺失字段样本成为传递误合并的“桥”。 */
    private class WorkingCluster(first: AtomicProblemProfile, provisional: Boolean = false) {
        val members = mutableListOf(first)
        var provisional = provisional
            private set
        val representative: AtomicProblemProfile get() = members.first()
        private val targets = mutableSetOf<String>().apply { first.target?.let(::add) }
        private val actions = mutableSetOf<String>().apply { first.action?.let(::add) }
        private val failures = mutableSetOf<String>().apply { first.failureMode?.let(::add) }
        private val signatures = mutableSetOf<String>().apply { first.errorSignature?.let(::add) }
        private val applicationPartitionKey = first.applicationPartitionKey()

        fun accepts(profile: AtomicProblemProfile): Boolean =
            applicationPartitionKey == profile.applicationPartitionKey() && accepts(targets, profile.target) && accepts(actions, profile.action) &&
                accepts(failures, profile.failureMode) && accepts(signatures, profile.errorSignature)

        fun add(profile: AtomicProblemProfile) {
            members += profile
            if (profile.evidenceSource != ProblemEvidenceSource.SOURCE_LABEL) provisional = false
            profile.target?.let(targets::add)
            profile.action?.let(actions::add)
            profile.failureMode?.let(failures::add)
            profile.errorSignature?.let(signatures::add)
        }

        private fun accepts(values: Set<String>, candidate: String?) =
            candidate == null || values.isEmpty() || values.any { it.equals(candidate, true) }
    }

    companion object {
        private const val TOP_K = 20
        // 0.70 要求至少“故障模式 + 对象”一致，同时允许动作等可选事实单侧缺失。
        private val LINK_THRESHOLD = BigDecimal("0.700000")
        // 与现有归簇治理的候选竞争差值保持一致，真实真值校准前只用于影子决策。
        private val COMPETITION_MARGIN = BigDecimal("0.030000")
    }

}

typealias ClusterShadowClassifierV2 = ClusterShadowClassifierV3

fun interface ClusterNamePolicy {
    fun name(profile: AtomicProblemProfile): String
}

/** 名称是带 application 限定的稳定展示结果，不能充当问题身份或合并键。 */
class DeterministicClusterNamePolicy : ClusterNamePolicy {
    override fun name(profile: AtomicProblemProfile): String {
        val app = profile.applicationName.orEmpty().ifBlank { profile.applicationKey.orEmpty() }
        val symptom = profile.canonicalSymptom.orEmpty().ifBlank { "具体问题待补充" }
        return "$app·$symptom".take(200)
    }
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
