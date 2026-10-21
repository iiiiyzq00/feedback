package com.feedback.service.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.config.RuntimeConfigService
import com.feedback.model.cluster.Cluster
import com.feedback.model.feedback.FeedbackAi
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import com.feedback.repository.cluster.ClusterProfileFactRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

data class ProblemProfile(
    val productScope: String,
    val functionModule: String?,
    val issueCategory: String?,
    val problemType: String?,
    val symptom: String,
    val targetObject: String?,
    val trigger: String?,
    val usageStage: String?,
    val errorSignature: String?,
    val appVersion: String?,
    val platform: String?,
    val deviceModel: String?,
    val severityLevel: String?,
    val sourceChannel: String,
    val aiConfidence: BigDecimal?,
    val fieldSources: Map<String, String>,
    val problemFingerprint: String = "",
    val errorSignatureType: String? = null,
    val capability: String? = targetObject,
    val scenarioOrAction: String? = trigger,
    val failureModes: Set<String> = emptySet(),
    val stableQualifiers: Set<String> = emptySet(),
    val informationQuality: String = "ACTIONABLE",
    val fingerprintVersion: String = "problem-fingerprint-v1",
    val recallDiagnostics: Map<String, Any?> = emptyMap(),
    val sourceEventTime:LocalDateTime=LocalDateTime.now()
)

data class CandidateEvidence(
    val cluster: Cluster,
    /** 纯粹的确定性文本语义。上下文绝不能影响这个值。 */
    val lexicalScore: BigDecimal,
    val semanticScore: BigDecimal = lexicalScore,
    /** 仅用于候选排序；版本/时间/渠道/模块上下文可以影响这个值。 */
    val rankingScore: BigDecimal = semanticScore,
    val fusedScore: Double? = null,
    val textRank: Int? = null,
    val vectorRank: Int? = null,
    val vectorScore: Float? = null,
    val coreMatches: Set<String> = emptySet(),
    val conflicts: Set<String> = emptySet(),
    val historicalRecurrence: Boolean = false,
    val textScore:BigDecimal?=null,val versionMatch:Boolean?=null,val timeDistanceHours:Long?=null,val crossChannelEvidence:Boolean=false,
    /** 确定性相似度门禁所要求的最小归一化症状长度。 */
    val normalizedTextLength: Int = 0
)

data class ClusterDecisionPolicy(
    val highThreshold: BigDecimal,
    val reviewThreshold: BigDecimal,
    val competitionMargin: BigDecimal,
    val decisionVersion: String,
    val configVersion: String,
    val vectorHighThreshold: BigDecimal = BigDecimal("0.900000"),
    val aiConfidenceReviewThreshold: BigDecimal = BigDecimal("0.850000")
)

data class ClusterDecisionPlan(
    val action: String,
    val selected: CandidateEvidence?,
    val candidates: List<CandidateEvidence>,
    val reason: String
)

@Service
class ProductScopeResolver(
    private val facts: ClusterProfileFactRepository,
    private val config: RuntimeConfigService
) {
    fun resolve(source: FeedbackSource): String = resolveAll(listOf(source)).getValue(source.id)

    /** 同一画像批次只读取一次渠道扩展事实，避免 split 对每条成员逐表查询。 */
    fun resolveAll(sources: Collection<FeedbackSource>): Map<Long, String> {
        if (sources.isEmpty()) return emptyMap()
        val byId = sources.associateBy(FeedbackSource::id)
        ensureClusterRequest(byId.size == sources.size) { "CLUSTER_PRODUCT_SOURCE_DUPLICATED" }
        return resolveAll(sources, facts.load(byId.keys).identifiers)
    }

    fun resolveAll(sources: Collection<FeedbackSource>, identifiers: Map<Long, String>): Map<Long, String> {
        if (sources.isEmpty()) return emptyMap()
        val byId = sources.associateBy(FeedbackSource::id)
        ensureClusterRequest(byId.size == sources.size) { "CLUSTER_PRODUCT_SOURCE_DUPLICATED" }
        ensureClusterInvariant(identifiers.keys.all { it in byId }, ErrorCode.FEEDBACK_READ_UNAVAILABLE) {
            "CLUSTER_PRODUCT_IDENTIFIER_UNEXPECTED"
        }
        val mappings = config.dictionary("cluster.product-scope-map")
        return byId.mapValues { (id, source) ->
            val identifier = identifiers[id]
            listOfNotNull(
                identifier?.let { mappings["${source.sourceChannel.uppercase()}:$it"] },
                identifier?.let(mappings::get),
                mappings[source.sourceChannel.uppercase()],
                config.string("cluster.default-product-scope", "LEARNING_MACHINE")
            ).firstOrNull { it.isNotBlank() }?.uppercase()
                ?: throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "CLUSTER_PRODUCT_SCOPE_MISSING")
        }
    }
}

@Service
class ChannelClusterContextResolver(private val facts: ClusterProfileFactRepository) {
    fun resolve(sourceId: Long): Map<String, String?> = resolveAll(listOf(sourceId))[sourceId].orEmpty()

    fun resolveAll(sourceIds: Collection<Long>): Map<Long, Map<String, String?>> =
        facts.load(sourceIds).contexts.mapValues { it.value.asMap() }
}

@Service
class ErrorSignatureNormalizer {
    fun normalize(text: String): String? {
        val exception = Regex("(?i)([a-z][a-z0-9_.]+(?:Exception|Error)|[A-Z][A-Za-z]+(?:Exception|Error))")
            .find(text)?.value?.substringAfterLast('.')?.uppercase()
        val businessCode = Regex(
            "(?i)(?:错误码|错误代码|故障码|返回(?:码)?|error\\s*code|err\\s*code|\\bcode\\b)" +
                "\\s*(?:为|是|[:：=\\-—])?\\s*([A-Z]{2,12}[-_:]?\\d{2,8})\\b"
        ).find(text)?.groupValues?.get(1)
            ?.replace('_', '-')?.replace(':', '-')?.uppercase()
            ?.takeUnless { code ->
                code.matches(Regex("^TC-?\\d+$")) ||
                    code.matches(Regex("^XC-?\\d+$")) ||
                    code.matches(Regex("^(?:REHEARSAL|DEMO|MOCK|FIXTURE|TEST|TESTDATA)-?\\d{6,8}$"))
            }
        val nativeSignal = Regex("\\b(SIGSEGV|SIGABRT|SIGBUS|SIGILL|ANR|WATCHDOG)\\b",RegexOption.IGNORE_CASE)
            .find(text)?.value?.uppercase()
        val httpCode = Regex("(?i)\\bHTTP(?:/\\d(?:\\.\\d)?)?[- :]*(4\\d{2}|5\\d{2})\\b")
            .find(text)?.groupValues?.get(1)?.let{"HTTP-$it"}
        val frame = Regex("(?i)at\\s+([a-z0-9_.$]+)\\([^:()]+(?::\\d+)?\\)").find(text)?.groupValues?.get(1)
            ?.replace(Regex("\\$[A-Za-z0-9_]+"), "")?.lowercase()
        return listOfNotNull(exception, businessCode, nativeSignal, httpCode, frame).distinct()
            .takeIf { it.isNotEmpty() }?.joinToString("|")?.take(500)
    }

    fun typeOf(signature: String?): String? = when {
        signature.isNullOrBlank() -> null
        signature.contains('|') -> "COMPOSITE"
        signature.matches(Regex("HTTP-[45]\\d{2}")) -> "HTTP_STATUS"
        signature in setOf("SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "ANR", "WATCHDOG") -> "SYSTEM_SIGNAL"
        signature.matches(Regex("[A-Z]{2,12}-?\\d{2,8}")) -> "BUSINESS_CODE"
        signature.contains("EXCEPTION") || signature.endsWith("ERROR") -> "EXCEPTION_TYPE"
        signature.contains('.') -> "STACK_FRAME"
        else -> "UNKNOWN"
    }
}

@Service
class ProblemProfileExtractor(private val signatures: ErrorSignatureNormalizer = ErrorSignatureNormalizer()) {
    fun extract(
        source: FeedbackSource,
        content: FeedbackContent,
        ai: FeedbackAi,
        productScope: String,
        context: Map<String, String?> = emptyMap()
    ): ProblemProfile {
        val text = "${content.title.orEmpty()} ${content.description.orEmpty()}".replace(Regex("\\s+"), " ").trim()
        val trigger = Regex("(?:点击|打开|进入|启动|上传|下载|提交|播放)[^，,。.;；]{0,40}")
            .findAll(text).lastOrNull()?.value
        val target = detectPrimaryTarget(text)
        val sourceTitle = content.title?.trim()?.takeIf { it.length >= 4 }
        val sourceSymptom = ProductPhenomenonFormatter.fromSource(
            sourceTitle, content.description, ai.aiSummary, content.problemType,
            content.functionModule, content.issueCategory, target
        )
        val descriptionPrimary = ClusterProblemOntology.primaryFailureMode(null, content.description)
        val concreteTitlePrimary = content.title?.takeUnless(ProductPhenomenonFormatter::isPlaceholder)
            ?.let { ClusterProblemOntology.primaryFailureMode(it, null) }
        val fallbackTitlePrimary = ClusterProblemOntology.primaryFailureMode(content.title, null)
        val primaryFailureMode = descriptionPrimary ?: concreteTitlePrimary ?: fallbackTitlePrimary
        val primaryFailureSource = when {
            descriptionPrimary != null -> "MASKED_TEXT_PRIMARY"
            concreteTitlePrimary != null || fallbackTitlePrimary != null -> "NORMALIZED_TITLE"
            else -> null
        }
        val symptom = ProductPhenomenonFormatter.forPrimaryIssue(primaryFailureMode, target, trigger, sourceSymptom)
        val failureModes = setOfNotNull(primaryFailureMode)
        val lowInformation = ProductPhenomenonFormatter.isLowInformation(symptom)
        val normalizedCapability = ClusterProblemOntology.targetClass(target).takeUnless { lowInformation }
        val normalizedAction = ClusterProblemOntology.operationClass(trigger).takeUnless { lowInformation }
        val signature = signatures.normalize(text).takeUnless { lowInformation }
        val signatureType = signatures.typeOf(signature)
        val informationQuality = if (lowInformation) "INSUFFICIENT_INFORMATION" else "ACTIONABLE"
        val fingerprint = clusterProblemFingerprint(
            productScope, content.functionModule.takeUnless { lowInformation }, normalizedCapability, normalizedAction,
            failureModes, setOfNotNull(signatureType), informationQuality, signature, symptom,
            ClusterProblemOntology.VERSION
        )
        return ProblemProfile(
            productScope = productScope,
            functionModule = content.functionModule.takeUnless { lowInformation },
            issueCategory = content.issueCategory.takeUnless { lowInformation },
            problemType = content.problemType.takeUnless { lowInformation },
            symptom = symptom,
            targetObject = target.takeUnless { lowInformation },
            trigger = trigger.takeUnless { lowInformation },
            usageStage = content.usageStage,
            errorSignature = signature,
            appVersion = source.appVersion ?: context["appVersion"],
            platform = context["platform"],
            deviceModel = context["deviceModel"],
            severityLevel = content.severityLevel.takeUnless { lowInformation },
            sourceChannel = source.sourceChannel,
            sourceEventTime=source.sourceEventTime?:source.ingestTime,
            aiConfidence = ai.aiConfidence,
            problemFingerprint = fingerprint,
            errorSignatureType = signatureType,
            capability = normalizedCapability,
            scenarioOrAction = normalizedAction,
            failureModes = failureModes,
            stableQualifiers = setOfNotNull(signatureType),
            informationQuality = informationQuality,
            fingerprintVersion = ClusterProblemOntology.VERSION,
            fieldSources = mapOf(
                "productScope" to "CHANNEL_MAPPING",
                "functionModule" to "NORMALIZED_FIELD",
                "issueCategory" to if (content.issueCategory != null) "NORMALIZED_FIELD" else "MISSING",
                "symptom" to (primaryFailureSource ?: if (sourceTitle != null && !ProductPhenomenonFormatter.isPlaceholder(sourceTitle)) "NORMALIZED_TITLE"
                    else if (!content.description.isNullOrBlank()) "MASKED_TEXT" else if (!ai.aiSummary.isNullOrBlank()) "AI_SUGGESTION" else "MISSING"),
                "errorSignature" to "DETERMINISTIC_RULE",
                "primaryFailureMode" to if (primaryFailureMode != null) "MASKED_TEXT_PRIMARY" else "MISSING"
            )
        )
    }

    private fun detectPrimaryTarget(text: String): String? = listOf(
        "错题本", "语音答疑", "应用商店", "网课助手", "优学练习", "同步学",
        "助教", "题库", "作业", "视频", "课程", "屏幕", "麦克风", "摄像头", "登录", "支付"
    ).firstOrNull(text::contains) ?: if (text.contains("上课")) "课程" else null

}

internal fun clusterProblemFingerprint(
    productScope: String,
    functionModule: String?,
    capability: String?,
    action: String?,
    failureModes: Set<String>,
    stableQualifiers: Set<String>,
    informationQuality: String,
    errorSignature: String?,
    symptom: String,
    ontologyVersion: String
): String {
    val symptomFallback = if (failureModes.isEmpty() && capability.isNullOrBlank() && action.isNullOrBlank()) {
        normalizeClusterSimilarityText(symptom)
    } else ""
    val material = listOf(
        ontologyVersion, capability.orEmpty(), action.orEmpty(), failureModes.sorted().joinToString("+"),
        stableQualifiers.sorted().joinToString("+"), informationQuality,
        errorSignature.orEmpty(), symptomFallback
    ).joinToString("|")
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Service
class ClusterDecisionMaker {
    fun evaluate(
        profile: ProblemProfile,
        cluster: Cluster,
        recall: CandidateEvidence? = null,
        allowedCrossModulePairs: Set<String> = emptySet()
    ): CandidateEvidence {
        val conflicts = linkedSetOf<String>()
        if (different(profile.errorSignature, cluster.errorSignature)) conflicts += "ERROR_SIGNATURE"
        if (differentSemanticTarget(profile.targetObject, cluster.canonicalObject)) conflicts += "TARGET_OBJECT"
        if (differentTriggerOperation(profile.trigger, cluster.canonicalTrigger)) conflicts += "TRIGGER"
        if (failureModesConflict(profile, cluster))
            conflicts += "SYMPTOM"
        val matches = linkedSetOf<String>()
        if (same(profile.functionModule, cluster.functionModule)) matches += "FUNCTION_MODULE"
        if (same(profile.issueCategory, cluster.issueCategory)) matches += "ISSUE_CATEGORY"
        if (sameSemanticTarget(profile.targetObject, cluster.canonicalObject)) matches += "TARGET_OBJECT"
        if (sameTriggerOperation(profile.trigger, cluster.canonicalTrigger)) matches += "TRIGGER"
        if (same(profile.errorSignature, cluster.errorSignature)) matches += "ERROR_SIGNATURE"
        val normalizedProfile = normalizeClusterSimilarityText(profile.symptom)
        val normalizedCluster = normalizeClusterSimilarityText(cluster.canonicalSymptom ?: cluster.clusterName)
        val textScore=similarity(normalizedProfile, normalizedCluster)
        return CandidateEvidence(
            cluster = cluster,
            lexicalScore = textScore, semanticScore = textScore, rankingScore = textScore, textScore=textScore,
            fusedScore = recall?.fusedScore,
            textRank = recall?.textRank,
            vectorRank = recall?.vectorRank,
            vectorScore = recall?.vectorScore,
            coreMatches = matches,
            conflicts = conflicts,
            historicalRecurrence = cluster.clusterStatus == "RESOLVED",
            normalizedTextLength = minOf(normalizedProfile.length, normalizedCluster.length)
        )
    }

    fun decide(profile: ProblemProfile, candidates: List<CandidateEvidence>, policy: ClusterDecisionPolicy): ClusterDecisionPlan {
        val eligible = candidates.filter { it.conflicts.isEmpty() }
            .sortedWith(compareByDescending<CandidateEvidence> { it.rankingScore }
                .thenByDescending { it.semanticScore }.thenBy { it.cluster.id })
        val best = eligible.firstOrNull()
            ?: return ClusterDecisionPlan("NEW_CLUSTER", null, emptyList(), "无无冲突候选，创建新簇")
        val exactSignature = profile.errorSignature != null && "ERROR_SIGNATURE" in best.coreMatches
        val coreAligned = best.coreMatches.count { it in CORE_FIELDS } >= 2
        val coreMatchCount = best.coreMatches.count { it in CORE_FIELDS }
        val moduleAndCategoryAligned = setOf("FUNCTION_MODULE", "ISSUE_CATEGORY").all { it in best.coreMatches }
        val vectorScore = best.vectorScore?.let { BigDecimal.valueOf(it.toDouble()) }
        val vectorHigh = vectorScore?.let { it >= policy.vectorHighThreshold } == true
        val textSimilarity = best.semanticScore
        val candidateSymptom = best.cluster.canonicalSymptom ?: best.cluster.clusterName
        val signatureType = profile.errorSignatureType ?: ErrorSignatureNormalizer().typeOf(profile.errorSignature)
        val signatureStructuralAnchor = best.coreMatches.any { it in setOf("TARGET_OBJECT", "TRIGGER") } ||
            ClusterProblemOntology.failureModes(profile.symptom).let { modes ->
                modes.size == 1 && modes == ClusterProblemOntology.failureModes(candidateSymptom)
            }
        val signatureCanAutoLink = exactSignature && when (signatureType) {
            "BUSINESS_CODE", "HTTP_STATUS", "EXCEPTION_TYPE", "SYSTEM_SIGNAL" ->
                signatureStructuralAnchor || textSimilarity >= BigDecimal("0.940000")
            else -> signatureStructuralAnchor && textSimilarity >= BigDecimal("0.940000")
        }
        val normalizedProfile = normalizeClusterSimilarityText(profile.symptom)
        val normalizedCandidate = normalizeClusterSimilarityText(candidateSymptom)
        // LCS/包含度得分高并不等于现象完全一致。下面的跨渠道门禁
        // 仍要拿归一化后的字符串本身来比，这样一句包含其他渠道
        // 短标签的话就不可能被自动合并。
        val normalizedExact = normalizedProfile.isNotBlank() && normalizedProfile == normalizedCandidate
        val causalSubmissionVariant = ClusterProblemOntology.operationClass(profile.trigger) == "SUBMIT" &&
            ClusterProblemOntology.operationClass(best.cluster.canonicalTrigger) == "SUBMIT" &&
            (ClusterProblemOntology.failureModes(profile.symptom) +
                ClusterProblemOntology.failureModes(candidateSymptom)) == setOf("NO_RESPONSE", "SUBMIT")
        val profileLowInformation = ProductPhenomenonFormatter.isLowInformation(profile.symptom)
        val candidateLowInformation = ProductPhenomenonFormatter.isLowInformation(candidateSymptom)
        if (profileLowInformation || candidateLowInformation) {
            return if (profileLowInformation && candidateLowInformation && normalizedExact) {
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "低信息反馈统一进入无法判断具体问题的数据质量簇")
            } else {
                ClusterDecisionPlan("NEW_CLUSTER", null, emptyList(), "低信息反馈不得与具体问题合并")
            }
        }
        val crossChannelSubstantiveAnchor = best.coreMatches.any {
            it in setOf("FUNCTION_MODULE", "TARGET_OBJECT", "TRIGGER", "ERROR_SIGNATURE")
        }
        val specificity = ProductPhenomenonFormatter.specificity(profile.symptom)
        val candidateSpecificity = ProductPhenomenonFormatter.specificity(candidateSymptom)
        val boundaryAnchorCount = best.coreMatches.count { it in setOf("TARGET_OBJECT", "TRIGGER", "ERROR_SIGNATURE") }
        val insufficientSpecificityEvidence = when {
            specificity == ProblemSpecificity.C || candidateSpecificity == ProblemSpecificity.C ->
                boundaryAnchorCount < 2 && "ERROR_SIGNATURE" !in best.coreMatches
            specificity == ProblemSpecificity.B || candidateSpecificity == ProblemSpecificity.B ->
                boundaryAnchorCount == 0
            else -> false
        }
        if (normalizedExact && insufficientSpecificityEvidence) {
            return ClusterDecisionPlan(
                "NEW_CLUSTER", null, emptyList(),
                "通用或缺少场景的问题描述不能仅凭相同文本自动合并"
            )
        }
        val placeholderPhenomenon = ProductPhenomenonFormatter.isPlaceholder(profile.symptom) ||
            ProductPhenomenonFormatter.isPlaceholder(candidateSymptom)
        val concreteCrossChannelExact = normalizedExact && best.normalizedTextLength >= 4 &&
            !placeholderPhenomenon
        if (placeholderPhenomenon && !crossChannelSubstantiveAnchor) {
            return ClusterDecisionPlan(
                "NEW_CLUSTER", null, emptyList(),
                "问题现象为占位值且缺少实质核心锚点，保守新建簇"
            )
        }
        if (best.crossChannelEvidence && !crossChannelSubstantiveAnchor && !concreteCrossChannelExact) {
            return ClusterDecisionPlan(
                "NEW_CLUSTER", null, emptyList(),
                "跨渠道缺少实质核心锚点或规范化现象不够具体，保守新建簇"
            )
        }
        val highRisk = isHighRisk(profile)
        val hasReviewGradeEvidence = signatureCanAutoLink || (coreAligned && textSimilarity >= policy.reviewThreshold) ||
            vectorHigh || textSimilarity >= policy.reviewThreshold
        val duplicateExact = normalizedExact && eligible.size > 1 && eligible.all {
            it.conflicts.isEmpty() && normalizeClusterSimilarityText(
                it.cluster.canonicalSymptom ?: it.cluster.clusterName
            ) == normalizedProfile
        }
        val competing = !duplicateExact && hasReviewGradeEvidence && eligible.drop(1).firstOrNull()?.let {
            best.semanticScore.subtract(it.semanticScore).abs() <= policy.competitionMargin
        } == true
        return when {
            best.historicalRecurrence && (exactSignature || coreAligned) ->
                ClusterDecisionPlan("CANDIDATE", best, eligible.take(5), "已解决问题疑似复发，需人工决定重开或新建")
            normalizedExact && best.normalizedTextLength >= 4 && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "规范化问题现象一致且长度足够")
            causalSubmissionVariant && "TRIGGER" in best.coreMatches && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "同一提交操作的转圈与无法完成是同一因果问题")
            hasReviewGradeEvidence && highRisk && !exactSignature ->
                ClusterDecisionPlan("CANDIDATE", best, eligible.take(5), "高风险结果需人工确认归簇边界")
            signatureCanAutoLink && (coreAligned || textSimilarity >= policy.reviewThreshold) && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "规范化错误签名一致且结构或文本证据充分")
            coreAligned && textSimilarity >= policy.highThreshold && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "至少两项核心画像一致且文本证据达到高阈值")
            moduleAndCategoryAligned && vectorHigh && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "模块和问题类型一致，且向量语义达到高阈值")
            coreMatchCount >= 1 && textSimilarity >= BigDecimal("0.940000") &&
                best.normalizedTextLength >= 6 && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "至少一项核心画像一致且保守文本证据达到阈值")
            coreMatchCount == 0 && textSimilarity >= BigDecimal("0.970000") &&
                best.normalizedTextLength >= 8 && !competing ->
                ClusterDecisionPlan("AUTO_HIGH", best, listOf(best), "无结构画像时文本证据达到高阈值且长度足够")
            competing -> ClusterDecisionPlan("CANDIDATE", best, eligible.take(5), "多个无冲突候选评分接近，需人工确认簇边界")
            textSimilarity >= policy.reviewThreshold && highRisk ->
                ClusterDecisionPlan("CANDIDATE", best, eligible.take(5), "高价值边界不确定，进入人工审核")
            else -> ClusterDecisionPlan("NEW_CLUSTER", null, emptyList(), "语义证据不能单独触发自动关联，保守新建簇")
        }
    }

    private fun isHighRisk(profile: ProblemProfile) =
        profile.issueCategory in setOf("SECURITY") || profile.severityLevel in setOf("S0", "S1")
    private fun different(left: String?, right: String?) = left != null && right != null && !left.equals(right, true)
    private fun same(left: String?, right: String?) = left != null && right != null && left.equals(right, true)
    private fun modulePair(left:String?,right:String?)=listOfNotNull(left,right).map(String::uppercase).sorted().joinToString(":")
    private fun differentSemanticTarget(left:String?,right:String?):Boolean {
        val leftClass=ClusterProblemOntology.targetClass(left)?:return false
        val rightClass=ClusterProblemOntology.targetClass(right)?:return false
        return leftClass!=rightClass
    }
    private fun sameSemanticTarget(left:String?,right:String?):Boolean {
        val leftClass=ClusterProblemOntology.targetClass(left)?:return false
        val rightClass=ClusterProblemOntology.targetClass(right)?:return false
        return leftClass==rightClass
    }
    private fun differentTriggerOperation(left:String?,right:String?):Boolean {
        val leftClass=ClusterProblemOntology.operationClass(left)?:return false
        val rightClass=ClusterProblemOntology.operationClass(right)?:return false
        return leftClass!=rightClass
    }
    private fun sameTriggerOperation(left:String?,right:String?):Boolean {
        val leftClass=ClusterProblemOntology.operationClass(left)?:return false
        val rightClass=ClusterProblemOntology.operationClass(right)?:return false
        return leftClass==rightClass
    }
    private fun failureModesConflict(profile: ProblemProfile, cluster: Cluster): Boolean {
        val left = ClusterProblemOntology.failureModes(profile.symptom)
        val right = ClusterProblemOntology.failureModes(cluster.canonicalSymptom ?: cluster.clusterName)
        if (left == right) return false
        // 只有一侧未知，不构成兼容的证据。这可以防止常见的
        // 异常/堆栈帧加上对象/动作元数据，盖过一个未分类的症状。
        if (left.isEmpty() != right.isEmpty()) return true
        if (left.isEmpty()) return false
        val causalSubmission = left + right == setOf("NO_RESPONSE", "SUBMIT") &&
            ClusterProblemOntology.operationClass(profile.trigger) == "SUBMIT" &&
            ClusterProblemOntology.operationClass(cluster.canonicalTrigger) == "SUBMIT"
        return !causalSubmission
    }
    private fun similarity(leftValue: String, rightValue: String): BigDecimal {
        if (leftValue.isEmpty() || rightValue.isEmpty()) return BigDecimal.ZERO.setScale(6)
        val left = grams(leftValue); val right = grams(rightValue)
        val dice = if (left.isEmpty() || right.isEmpty()) 0.0 else
            2.0 * left.intersect(right).size / (left.size + right.size).toDouble()
        val minLength = minOf(leftValue.length, rightValue.length)
        val maxLength = maxOf(leftValue.length, rightValue.length)
        val lcsCoverage = if (minLength == 0) 0.0 else
            longestCommonSubsequence(leftValue, rightValue).toDouble() / minLength
        val containment = lcsCoverage * (0.85 + 0.15 * minLength.toDouble() / maxLength.toDouble())
        return BigDecimal.valueOf(maxOf(dice, containment).coerceIn(0.0, 1.0))
            .setScale(6, RoundingMode.HALF_UP)
    }
    private fun grams(value: String): Set<String> {
        return if (value.length < 2) setOf(value).filter(String::isNotBlank).toSet()
        else (0 until value.length - 1).map { value.substring(it, it + 2) }.toSet()
    }

    private fun longestCommonSubsequence(left: String, right: String): Int {
        if (left.isEmpty() || right.isEmpty()) return 0
        var previous = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            for (rightIndex in right.indices) {
                current[rightIndex + 1] = if (left[leftIndex] == right[rightIndex]) {
                    previous[rightIndex] + 1
                } else {
                    maxOf(previous[rightIndex + 1], current[rightIndex])
                }
            }
            previous = current
        }
        return previous[right.length]
    }
    companion object { private val CORE_FIELDS = setOf("FUNCTION_MODULE", "ISSUE_CATEGORY", "TARGET_OBJECT", "TRIGGER") }
}

/**
 * 为确定性聚类证据提供的稳定、无依赖文本归一化。
 * 保留字母数字（含错误码里的数字），只映射少量已知的
 * 通用前缀与同义词；这不是自由改写。
 */
internal fun normalizeClusterSimilarityText(value: String): String {
    var normalized = value.trim()
        .replace(Regex("^(?:【[^】]+】\\s*)+"), "")
        .replace(Regex("^(?:客户描述|用户反馈|问题描述|现象)[\\s:：-]*"), "")
        .replace(Regex("[；;，,]?\\s*记录编号\\s*[A-Za-z0-9_-]+[。.]?\\s*$"), "")
        .lowercase()
        .replace("崩溃", "闪退")
        .replace("crash", "闪退")
        .replace("这儿", "这里")
        .replace(Regex("卡住|冻结"), "卡死")
        .replace(Regex("跳不进去|进不去"), "无法进入")
        .replace(Regex("识别有错误|识别错了|识别错"), "识别错误")
        .replace(Regex("判错|改错"), "判定错误")
        .replace(Regex("无法打开|打开不了|打不开"), "打开失败")
    return normalized.replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
