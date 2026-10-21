package com.feedback.service.query.listprojection

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.feedback.model.po.feedback.listprojection.FeedbackClusterMemberProjectionPo
import com.feedback.model.po.feedback.listprojection.FeedbackClusterIdentityProjectionPo
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 在调用方固定的 `REPEATABLE_READ` 事务中，对候选 generation 执行只读完整性验证。
 *
 * 输入是 generation、构建 fence、fence 内权威来源基线数和当前 mutation epoch。验证器按 source ID
 * keyset 分批重建每条安全投影，再分别扫描列表投影、簇成员投影和身份投影；它不推进持久化 build cursor、
 * 不写验证状态，最终只返回数量和四组确定性 checksum，由 Mutation Coordinator 在同一事务中决定是否
 * 持久化验证事实。
 *
 * 任一基线变化、投影缺失、多余行或业务内容差异都会以稳定错误码失败关闭。辅助事实只在整批 source/content
 * 安全资格判断完成后读取，避免不合格来源触达身份、附件、渠道或归簇事实。
 */
@Component
class FeedbackListProjectionGenerationVerifier(
    private val repository: FeedbackListProjectionRepository,
    private val assembler: FeedbackListProjectionAssembler,
    private val objectMapper: ObjectMapper
) {
    /**
     * 重算 fence 内的期望投影并与候选 generation 对账。
     *
     * 返回值中的 mutation epoch 只是本次事务快照；本方法不证明 epoch 在事务结束后仍未变化。调用方必须持有
     * control/generation/dirty 的治理锁，并在激活前再次比较 epoch 与已保存 checksum。
     */
    fun verify(generationId: Long, fenceSourceId: Long, baselineCount: Long, mutationEpoch: Long): FeedbackListProjectionVerificationResult {
        if (generationId <= 0 || fenceSourceId < 0 || baselineCount < 0 || mutationEpoch < 0) {
            invalidProjectionCommand("FEEDBACK_LIST_PROJECTION_VERIFICATION_INPUT_INVALID")
        }
        val expectedProjection = DigestCounter(objectMapper)
        val expectedMember = DigestCounter(objectMapper)
        val expectedAnalysis = DigestCounter(objectMapper)
        val expectedIdentity = DigestCounter(objectMapper)
        var sourceCount = 0L
        var rejectedCount = 0L
        var cursor = 0L
        var applicationNames: List<String>? = null
        while (true) {
            val ids = repository.nextSourceIds(cursor, fenceSourceId, FeedbackListProjectionRepository.MAX_BATCH_SIZE)
            if (ids.isEmpty()) break
            val sources = repository.sources(ids)
            val contents = repository.contents(ids)
            val eligible = ids.mapNotNull { id -> sources[id]?.takeIf { assembler.isSafelyEligible(it, contents[id]) } }
            val auxiliary = if (eligible.isEmpty()) {
                repository.auxiliary(emptyList())
            } else {
                val names = applicationNames ?: repository.applicationNames().also { applicationNames = it }
                repository.auxiliary(eligible, names)
            }
            eligible.forEach { source ->
                val snapshot = assembler.assemble(generationId, source, contents.getValue(source.id), auxiliary)
                expectedProjection.addProjection(snapshot.projection)
                snapshot.clusterMember?.let(expectedMember::addMember)
                snapshot.clusterMember?.let(expectedAnalysis::addAnalysisMember)
                snapshot.clusterIdentities.forEach(expectedIdentity::addIdentity)
            }
            sourceCount += ids.size.toLong()
            rejectedCount += (ids.size - eligible.size).toLong()
            cursor = ids.last()
        }
        if (sourceCount != baselineCount) projectionCatchUpRequired("FEEDBACK_LIST_PROJECTION_BASELINE_COUNT_CHANGED")

        val actualProjection = scanProjections(generationId)
        val (actualMember, actualAnalysis) = scanMembers(generationId)
        val actualIdentity = scanIdentities(generationId)
        if (actualProjection.count != expectedProjection.count || actualProjection.hex() != expectedProjection.hex()) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CONTENT_MISMATCH")
        if (actualMember.count != expectedMember.count || actualMember.hex() != expectedMember.hex()) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_MEMBER_MISMATCH")
        if (actualAnalysis.count != expectedAnalysis.count || actualAnalysis.hex() != expectedAnalysis.hex()) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_ANALYSIS_MISMATCH")
        if (actualIdentity.count != expectedIdentity.count || actualIdentity.hex() != expectedIdentity.hex()) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_ANALYSIS_IDENTITY_MISMATCH")
        if (expectedProjection.count + rejectedCount != baselineCount) projectionOperationFailed("FEEDBACK_LIST_PROJECTION_RECONCILIATION_MISMATCH")
        return FeedbackListProjectionVerificationResult(
            generationId, mutationEpoch, baselineCount, expectedProjection.count, rejectedCount,
            expectedMember.count, expectedProjection.hex(), expectedMember.hex(), expectedAnalysis.hex(),
            expectedIdentity.hex(), expectedIdentity.count
        )
    }

    private fun scanProjections(generationId: Long): DigestCounter {
        val digest = DigestCounter(objectMapper)
        var cursor = 0L
        while (true) {
            val rows = repository.projectionPage(generationId, cursor, FeedbackListProjectionRepository.MAX_BATCH_SIZE)
            if (rows.isEmpty()) return digest
            rows.forEach(digest::addProjection)
            cursor = rows.last().sourceId
        }
    }

    private fun scanMembers(generationId: Long): Pair<DigestCounter, DigestCounter> {
        val memberDigest = DigestCounter(objectMapper)
        val analysisDigest = DigestCounter(objectMapper)
        var cursor = 0L
        while (true) {
            val rows = repository.memberPage(generationId, cursor, FeedbackListProjectionRepository.MAX_BATCH_SIZE)
            if (rows.isEmpty()) return memberDigest to analysisDigest
            rows.forEach(memberDigest::addMember)
            rows.forEach(analysisDigest::addAnalysisMember)
            cursor = rows.last().sourceId
        }
    }

    private fun scanIdentities(generationId: Long): DigestCounter {
        val digest = DigestCounter(objectMapper)
        var sourceCursor = 0L
        var identityCursor = ""
        while (true) {
            val rows = repository.identityPage(
                generationId, sourceCursor, identityCursor, FeedbackListProjectionRepository.MAX_BATCH_SIZE
            )
            if (rows.isEmpty()) return digest
            rows.forEach(digest::addIdentity)
            val last = rows.last()
            sourceCursor = last.sourceId
            identityCursor = identityKey(last)
        }
    }

    private fun identityKey(row: FeedbackClusterIdentityProjectionPo): String = listOf(
        row.identityScope, row.identityType, row.fingerprintKeyId,
        row.fingerprint.joinToString("") { "%02X".format(it) }
    ).joinToString(":")

    /**
     * 按 Mapper 保证的稳定 keyset 顺序，生成与字段语义有关的长度前缀 SHA-256 摘要。
     *
     * generation ID 被排除，使同一业务快照可用于激活与回滚候选对账；对象键排序、十进制去除无意义尾零、
     * 时间固定到九位小数，JSON 字符串先解析为结构。长度前缀防止相邻标量连接后产生边界歧义。
     */
    private class DigestCounter(private val mapper: ObjectMapper) {
        private val digest = MessageDigest.getInstance("SHA-256")
        var count: Long = 0
            private set

        fun addProjection(row: FeedbackListProjectionPo) {
            val node = objectNode(row, setOf("generationId"), JSON_FIELDS)
            normalizeDate(node, "faultOccurredAt", row.faultOccurredAt)
            normalizeDate(node, "sourceEventTime", row.sourceEventTime)
            normalizeDate(node, "reportedAt", row.reportedAt)
            normalizeDate(node, "sourceUpdateTime", row.sourceUpdateTime)
            normalizeDate(node, "platformUpdateTime", row.platformUpdateTime)
            normalizeDate(node, "ingestTime", row.ingestTime)
            normalizeDate(node, "jiraResolvedAt", row.jiraResolvedAt)
            addNode(node)
        }

        fun addMember(row: FeedbackClusterMemberProjectionPo) {
            val legacy = mapper.createObjectNode()
            legacy.put("sourceId", row.sourceId)
            legacy.put("clusterId", row.clusterId)
            legacy.put("sourceChannel", row.sourceChannel)
            legacy.put("functionModule", row.functionModule)
            legacy.put("provinceCode", row.provinceCode)
            legacy.put("cityCode", row.cityCode)
            addNode(legacy)
        }

        fun addAnalysisMember(row: FeedbackClusterMemberProjectionPo) {
            val node = objectNode(row, setOf("generationId"), emptySet())
            normalizeDate(node, "reportedAt", row.reportedAt)
            addNode(node)
        }

        fun addIdentity(row: FeedbackClusterIdentityProjectionPo) {
            val node = objectNode(row, setOf("generationId"), emptySet())
            normalizeDate(node, "reportedAt", row.reportedAt)
            addNode(node)
        }
        fun hex(): String = digest.clone().let { clone ->
            (clone as MessageDigest).digest().joinToString("") { "%02x".format(it) }
        }

        private fun objectNode(value: Any, excluded: Set<String>, jsonFields: Set<String>): ObjectNode {
            val node = mapper.valueToTree<ObjectNode>(value)
            excluded.forEach(node::remove)
            jsonFields.forEach { field ->
                val current = node.get(field)
                if (current != null && current.isTextual) node.set<JsonNode>(field, mapper.readTree(current.textValue()))
            }
            return node
        }

        private fun normalizeDate(node: ObjectNode, field: String, value: LocalDateTime?) {
            if (value == null) node.putNull(field) else node.put(field, DATE_FORMAT.format(value))
        }

        private fun addNode(node: ObjectNode) {
            val canonical = canonical(node)
            digest.update(canonical.length.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(canonical.toByteArray(StandardCharsets.UTF_8))
            count++
        }

        private fun canonical(node: JsonNode): String = when {
            node.isNull -> "N"
            node.isBoolean -> if (node.booleanValue()) "B1" else "B0"
            node.isNumber -> "D${BigDecimal(node.asText()).stripTrailingZeros().toPlainString()}"
            node.isTextual -> "S${node.textValue().length}:${node.textValue()}"
            node.isBinary -> node.binaryValue().let { bytes ->
                "X${bytes.size}:${bytes.joinToString("") { "%02X".format(it) }}"
            }
            node.isArray -> node.joinToString(prefix = "A[", postfix = "]", separator = "") { child -> canonical(child) }
            node.isObject -> node.fieldNames().asSequence().toList().sorted().joinToString(prefix = "O{", postfix = "}", separator = "") { name ->
                "K${name.length}:$name${canonical(node.get(name))}"
            }
            else -> projectionOperationFailed("FEEDBACK_LIST_PROJECTION_CANONICAL_TYPE_INVALID")
        }

        companion object {
            val JSON_FIELDS = setOf("jiraImportBatchCodes", "channelMetaJson", "diagnosticTypes")
            val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS")
        }
    }
}
