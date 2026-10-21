package com.feedback.service.normalization

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import org.springframework.stereotype.Component
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import com.feedback.repository.normalization.EnumMappingConfigRepository

data class EnumMappingSnapshot(
    val channel: String,
    val version: String,
    val mappings: Map<String, Map<String, String>>
)

@Component
class EnumMappingRegistry(
    private val repository: EnumMappingConfigRepository?=null,
    private val objectMapper:ObjectMapper?=null
) {
    fun snapshot(channel: String): EnumMappingSnapshot {
        val normalizedChannel = channel.uppercase()
        val fields = GENERIC_FIELDS + SPECIAL_FIELDS[normalizedChannel].orEmpty()
        val mappings = fields.associateWith { field -> configured(normalizedChannel, field, fallback(field)) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(normalizedChannel.toByteArray())
        mappings.toSortedMap().forEach { (field, aliases) ->
            digest.update(0); digest.update(field.toByteArray())
            aliases.toSortedMap().forEach { (source, target) ->
                digest.update(31); digest.update(source.toByteArray()); digest.update(30); digest.update(target.toByteArray())
            }
        }
        val suffix = digest.digest().take(6).joinToString("") { "%02x".format(it) }
        return EnumMappingSnapshot(normalizedChannel, "enumset-$suffix", mappings)
    }

    fun normalize(channel: String, value: (String) -> String?, snapshot: EnumMappingSnapshot = snapshot(channel)): NormalizedImportFields {
        if(snapshot.channel != channel.uppercase()) invalid("IMPORT_ENUM_SNAPSHOT_CHANNEL_MISMATCH")
        val errors = mutableListOf<String>()
        val conversions = mutableListOf<FieldConversion>()
        fun mapped(field: String, raw: String?, aliases: Map<String, String>): String? {
            if (raw.isNullOrBlank()) return null
            val normalized = aliases[key(raw)]
            if (normalized == null) {
                errors += "UNMAPPED_${field.uppercase()}"
                conversions += FieldConversion(targetField(field), raw.take(500), null, "UNMAPPED", "未命中受控枚举")
            } else if (raw.trim() != normalized) {
                conversions += FieldConversion(targetField(field), raw.take(500), normalized, "AUTO_MAPPING", "受控枚举标准化")
            }
            return normalized
        }

        fun aliases(field: String) = snapshot.mappings[field] ?: invalid("IMPORT_ENUM_SNAPSHOT_FIELD_MISSING")
        val genericSeverity = mapped("severity_level", value("severity_level"), aliases("severity_level"))
        val genericStatus = mapped("processing_status", value("processing_status"), aliases("processing_status"))
        val genericCategory = mapped("issue_category", value("issue_category"), aliases("issue_category"))
        val genericSentiment = mapped("user_sentiment", value("user_sentiment"), aliases("user_sentiment"))
        return NormalizedImportFields(
            functionModule = mapped("function_module", value("function_module"), aliases("function_module")),
            problemType = mapped("problem_type", value("standard_problem_type"), aliases("problem_type")),
            severityLevel = when (channel) {
                "JIRA" -> mapped("priority", value("priority"), aliases("priority")) ?: genericSeverity
                else -> genericSeverity
            },
            issueCategory = when (channel) {
                "JIRA" -> mapped("jira_issue_type", value("problem_type"), aliases("jira_issue_type")) ?: genericCategory
                "QUALITY" -> mapped("application_type", value("application_type"), aliases("application_type")) ?: genericCategory
                else -> genericCategory
            },
            userSentiment = when (channel) {
                "REDBLACK" -> mapped("emotion", value("emotion"), aliases("emotion")) ?: genericSentiment
                else -> genericSentiment
            },
            processingStatus = when (channel) {
                "JIRA" -> mapped("jira_status", value("status"), aliases("jira_status")) ?: genericStatus ?: "OPEN"
                else -> genericStatus ?: "OPEN"
            },
            errorCodes = errors.distinct(),
            conversions = conversions.distinctBy { listOf(it.fieldName, it.oldValue, it.newValue, it.changeType) }
        )
    }

    /** 生效配置以 import.enum.<CHANNEL>.<field> 为键，值是一个「别名 → 目标值」的 JSON 对象。 */
    private fun configured(channel:String,field:String,fallback:Map<String,String>):Map<String,String> {
        val db=repository?:return fallback
        val mapper=objectMapper?:return fallback
        val row=db.activeJson("import.enum.${channel.uppercase()}.$field") ?: return fallback
        val raw=mapper.readValue(row,object:TypeReference<Map<String,String>>() {})
        val allowed=fallback.values.toSet()
        if(raw.values.any { it !in allowed }) failed("IMPORT_ENUM_MAPPING_TARGET_INVALID")
        return fallback + raw.mapKeys { canonicalKey(it.key) }
    }

    private fun targetField(input: String): String = when (input) {
        "priority" -> "severity_level"
        "jira_issue_type", "application_type" -> "issue_category"
        "emotion" -> "user_sentiment"
        "jira_status" -> "processing_status"
        else -> input
    }

    private fun key(value: String): String = canonicalKey(value)

    companion object {
        const val VERSION = "enum-map-v2"
        private val GENERIC_FIELDS = setOf("function_module", "problem_type", "severity_level", "processing_status", "issue_category", "user_sentiment")
        private val SPECIAL_FIELDS = mapOf(
            "JIRA" to setOf("priority", "jira_issue_type", "jira_status"),
            "QUALITY" to setOf("application_type"),
            "REDBLACK" to setOf("emotion")
        )

        private fun canonicalKey(value: String): String = value.trim().uppercase().replace(Regex("[\\s_\\-/]+"), "")

        private fun aliases(vararg entries: Pair<String, String>): Map<String, String> =
            entries.associate { canonicalKey(it.first) to it.second }

        private val FUNCTION_MODULE_VALUES = listOf(
            "AI_TUTOR", "AI_HOMEWORK_CHECK", "AI_ESSAY_CHECK", "SYNC_COURSE", "EXERCISE_PRACTICE",
            "WRONG_QUESTION_BOOK", "VIDEO_PLAYBACK", "TEXTBOOK_READING", "PARENT_CONTROL", "DEVICE_SETTINGS",
            "NETWORK_CONNECTION", "CAMERA_MIC", "SCREEN_DISPLAY", "APP_STORE", "VIDEO_CALL",
            "SCREEN_OFF_STUDY", "POSTURE_MONITOR", "HEALTH_OVERVIEW", "DRAWING_CANVAS", "OTHER"
        )
        private val PROBLEM_TYPE_VALUES = listOf(
            "CRASH", "FREEZE", "LAG", "NO_RESPONSE", "CANT_START", "AUTO_SHUTDOWN", "SCREEN_ISSUE", "TOUCH_FAIL",
            "MIC_FAIL", "CAMERA_FAIL", "SPEAKER_ISSUE", "NETWORK_OFFLINE", "FEATURE_MISSING", "FEATURE_INVALID",
            "WRONG_RESULT", "CONTENT_ERROR", "CANT_DOWNLOAD", "CANT_LOGIN", "DATA_LOST", "PERMISSION_DENIED",
            "UI_DISPLAY_ERROR", "PROCESS_COMPLEX", "FEATURE_REQUEST", "CONTENT_REQUEST", "IMPROVEMENT_SUGGEST",
            "CUSTOMER_SERVICE", "HARDWARE_DEFECT", "OTHER"
        )
        private val FUNCTION_MODULE = FUNCTION_MODULE_VALUES.associate { canonicalKey(it) to it } + aliases(
            "AI助教" to "AI_TUTOR", "作业批改" to "AI_HOMEWORK_CHECK", "作文批改" to "AI_ESSAY_CHECK",
            "同步课程" to "SYNC_COURSE", "练习" to "EXERCISE_PRACTICE", "错题本" to "WRONG_QUESTION_BOOK",
            "视频播放" to "VIDEO_PLAYBACK", "网络" to "NETWORK_CONNECTION", "相机麦克风" to "CAMERA_MIC",
            "屏幕" to "SCREEN_DISPLAY", "应用商店" to "APP_STORE", "设置" to "DEVICE_SETTINGS",
            "网络相关" to "NETWORK_CONNECTION", "网络稳定性" to "NETWORK_CONNECTION",
            "用户网络环境" to "NETWORK_CONNECTION", "网络卡顿" to "NETWORK_CONNECTION", "WiFi模组" to "NETWORK_CONNECTION",
            "麦克风" to "CAMERA_MIC", "硬件麦克风" to "CAMERA_MIC",
            "管控相关" to "PARENT_CONTROL", "系统_管控相关" to "PARENT_CONTROL",
            "健康总览" to "HEALTH_OVERVIEW", "健康" to "HEALTH_OVERVIEW",
            "绘画画布" to "DRAWING_CANVAS", "画布" to "DRAWING_CANVAS", "其他" to "OTHER"
        )
        private val PROBLEM_TYPE = PROBLEM_TYPE_VALUES.associate { canonicalKey(it) to it } + aliases(
            "闪退" to "CRASH", "崩溃" to "CRASH", "卡死" to "FREEZE", "卡顿" to "LAG", "无响应" to "NO_RESPONSE",
            "无法启动" to "CANT_START", "网络断开" to "NETWORK_OFFLINE", "结果错误" to "WRONG_RESULT",
            "内容错误" to "CONTENT_ERROR", "无法下载" to "CANT_DOWNLOAD", "无法登录" to "CANT_LOGIN",
            "数据丢失" to "DATA_LOST", "权限不足" to "PERMISSION_DENIED", "功能建议" to "FEATURE_REQUEST",
            "硬件故障" to "HARDWARE_DEFECT", "其他" to "OTHER"
        )
        private val SEVERITY = aliases(
            "S0" to "S0", "S1" to "S1", "S2" to "S2", "S3" to "S3", "S4" to "S4",
            "灾难" to "S0", "严重" to "S1", "一般" to "S2", "轻微" to "S3", "表扬" to "S4"
        )
        private val PROCESSING_STATUS = aliases(
            "OPEN" to "OPEN", "待处理" to "OPEN", "IN_PROGRESS" to "IN_PROGRESS", "处理中" to "IN_PROGRESS",
            "RESOLVED" to "RESOLVED", "已解决" to "RESOLVED", "CLOSED" to "CLOSED", "已关闭" to "CLOSED"
        )
        private val ISSUE_CATEGORY = aliases(
            "BUG" to "BUG", "缺陷" to "BUG", "HARDWARE" to "HARDWARE", "硬件" to "HARDWARE",
            "CONTENT_ERROR" to "CONTENT_ERROR", "内容错误" to "CONTENT_ERROR", "DEMAND" to "DEMAND", "需求" to "DEMAND",
            "CONSULT" to "CONSULT", "咨询" to "CONSULT", "PRAISE" to "PRAISE", "表扬" to "PRAISE",
            "COMPLAINT" to "COMPLAINT", "投诉" to "COMPLAINT"
        )
        private val SENTIMENT = aliases(
            "POSITIVE" to "POSITIVE", "正面" to "POSITIVE", "正向" to "POSITIVE", "红榜" to "POSITIVE",
            "NEUTRAL" to "NEUTRAL", "中性" to "NEUTRAL", "NEGATIVE" to "NEGATIVE", "负面" to "NEGATIVE",
            "负向" to "NEGATIVE", "黑榜" to "NEGATIVE"
        )
        private val JIRA_PRIORITY = aliases(
            "HIGHEST" to "S0", "BLOCKER" to "S0", "HIGH" to "S1", "CRITICAL" to "S1",
            "MEDIUM" to "S2", "MAJOR" to "S2", "LOW" to "S3", "MINOR" to "S3", "LOWEST" to "S4", "TRIVIAL" to "S4"
        )
        private val JIRA_STATUS = aliases(
            "OPEN" to "OPEN", "TO_DO" to "OPEN", "待办" to "OPEN", "IN_PROGRESS" to "IN_PROGRESS", "进行中" to "IN_PROGRESS",
            "RESOLVED" to "RESOLVED", "DONE" to "RESOLVED", "已解决" to "RESOLVED", "CLOSED" to "CLOSED", "已关闭" to "CLOSED"
        )
        private val JIRA_ISSUE_TYPE = aliases(
            "BUG" to "BUG", "DEFECT" to "BUG", "缺陷" to "BUG", "故障" to "BUG",
            "STORY" to "DEMAND", "EPIC" to "DEMAND", "需求" to "DEMAND",
            "TASK" to "CONSULT", "SUBTASK" to "CONSULT", "任务" to "CONSULT", "咨询" to "CONSULT"
        )
        private val QUALITY_APPLICATION_TYPE = aliases(
            "换机" to "BUG", "维修" to "BUG", "报修" to "BUG", "投诉" to "COMPLAINT", "咨询" to "CONSULT"
        )
        private val REDBLACK_EMOTION = SENTIMENT

        private fun fallback(field: String): Map<String, String> = when (field) {
            "function_module" -> FUNCTION_MODULE
            "problem_type" -> PROBLEM_TYPE
            "severity_level" -> SEVERITY
            "processing_status" -> PROCESSING_STATUS
            "issue_category" -> ISSUE_CATEGORY
            "user_sentiment" -> SENTIMENT
            "priority" -> JIRA_PRIORITY
            "jira_status" -> JIRA_STATUS
            "jira_issue_type" -> JIRA_ISSUE_TYPE
            "application_type" -> QUALITY_APPLICATION_TYPE
            "emotion" -> REDBLACK_EMOTION
            else -> failed("IMPORT_ENUM_FIELD_UNSUPPORTED")
        }

        private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
        private fun failed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
    }
}

data class NormalizedImportFields(
    val functionModule: String?, val problemType: String?, val severityLevel: String?,
    val issueCategory: String?, val userSentiment: String?, val processingStatus: String,
    val errorCodes: List<String>, val conversions: List<FieldConversion> = emptyList()
)

data class FieldConversion(
    val fieldName: String,
    val oldValue: String,
    val newValue: String?,
    val changeType: String,
    val reason: String
)
