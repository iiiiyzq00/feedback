package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.ToolObservation
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 参数与观察结果的规范化摘要。
 *
 * 为什么不用 Jackson 直接序列化：摘要必须**与序列化实现无关**。一旦字段顺序、时间格式或
 * 空值处理随库版本变化，同一组参数会算出不同摘要，「同工具同参数复用 Observation」
 * 就去重失效，更糟的是恢复时会把已完整取过的证据当成没取过而重复调用。
 *
 * 因此这里自己定义规范形式：map 键按字典序、时间统一 ISO-8601 毫秒、集合保持传入顺序
 * （顺序在语义上重要，例如时间窗边界）、null 显式写成 `null` 而不是省略。
 *
 * 输出为 SHA-256 十六进制小写，与 `t_rca_tool_call.params_digest` 的
 * `CHECK (params_digest REGEXP '^[0-9a-f]{64}$')` 约束一致。
 */
object RcaCanonicalDigest {

    /** 时间统一到毫秒精度，与 DATETIME(3) 的落库精度保持一致，避免微秒差异造成摘要漂移。 */
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    fun ofParams(params: Map<String, Any?>): String = sha256(canonicalizeValue(params))

    fun ofObservation(observation: ToolObservation): String = sha256(
        canonicalizeValue(
            linkedMapOf<String, Any?>(
                "summary" to observation.summary,
                "windowFrom" to observation.windowFrom,
                "windowTo" to observation.windowTo,
                "timeWatermark" to observation.timeWatermark,
                "subjectRefs" to observation.subjectRefs,
                "firstFaultPoint" to observation.firstFaultPoint?.let {
                    linkedMapOf<String, Any?>(
                        "serviceName" to it.serviceName,
                        "operationName" to it.operationName,
                        "endpoint" to it.endpoint,
                        "statusCode" to it.statusCode,
                        "occurredAt" to it.occurredAt,
                        "relation" to it.relation.storageCode,
                        "errorCode" to it.errorCode
                    )
                },
                "errorSignatureCounts" to observation.errorSignatureCounts,
                "denominator" to observation.denominator,
                "controlGroup" to observation.controlGroup,
                "concentrationRatio" to observation.concentrationRatio,
                "refutesHypotheses" to observation.refutesHypotheses,
                "supportsHypotheses" to observation.supportsHypotheses,
                "newGaps" to observation.newGaps,
                "sourceRef" to observation.sourceRef
            )
        )
    )

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /**
     * 规范形式的值序列化。只支持标量与它们的集合，遇到未支持的复杂类型直接转为类型名，
     * 宁可让摘要不稳定也不要静默产生一个看似合理的错误摘要。
     */
    private fun canonicalizeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value + "\""
        is Number, is Boolean -> value.toString()
        is LocalDateTime -> "\"" + TIME_FORMATTER.format(value) + "\""
        is Map<*, *> -> value.entries
            .map { (key, entryValue) -> key.toString() to entryValue }
            .sortedBy { (key, _) -> key }
            .joinToString(prefix = "{", postfix = "}") { (key, entryValue) ->
                "\"" + key + "\":" + canonicalizeValue(entryValue)
            }

        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalizeValue(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalizeValue(it) }
        else -> "\"<unsupported:" + value.javaClass.name + ">\""
    }
}
