package com.feedback.model.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import java.text.Normalizer
import java.util.Locale

/** 来源渠道的稳定数据库与任务编码；未知编码在持久化边界失败关闭。 */
enum class ApplicationSourceChannel(val code: String) {
    SDK("SDK"),
    STATION_FEEDBACK("STATION_FEEDBACK"),
    JIRA("JIRA"),
    QUALITY("QUALITY"),
    REDBLACK("REDBLACK");

    companion object {
        /** 将数据库/任务字符串严格转换为封闭渠道类型，不静默接纳未知值。 */
        fun fromCode(code: String): ApplicationSourceChannel = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_SOURCE_CHANNEL_INVALID")
    }
}

/** alias 类型的稳定数据库与 HTTP 编码。 */
enum class ApplicationAliasType(val code: String) {
    SDK_APP_ID("SDK_APP_ID"),
    SDK_PACKAGE_NAME("SDK_PACKAGE_NAME"),
    STATION_SOURCE_APP_ID("STATION_SOURCE_APP_ID");

    companion object {
        /** 严格解析持久化编码，避免未知 alias 类型进入匹配规则。 */
        fun fromCode(code: String): ApplicationAliasType = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_ALIAS_TYPE_INVALID")
    }
}

/** identity 生命周期状态；code 必须与既有数据库和 HTTP 响应兼容。 */
enum class ApplicationIdentityLifecycleStatus(val code: String) {
    ACTIVE("ACTIVE"),
    RETIRED("RETIRED");

    companion object {
        /** 严格解析数据库状态，历史未知值按来源完整性错误处理。 */
        fun fromCode(code: String): ApplicationIdentityLifecycleStatus = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_IDENTITY_STATUS_INVALID")
    }
}

/** alias 版本状态；活动版本与被替代历史版本互斥。 */
enum class ApplicationAliasBindingStatus(val code: String) {
    ACTIVE("ACTIVE"),
    SUPERSEDED("SUPERSEDED");

    companion object {
        /** 严格解析 alias 状态，不允许未知值被当成活动绑定。 */
        fun fromCode(code: String): ApplicationAliasBindingStatus = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_ALIAS_BINDING_STATUS_INVALID")
    }
}

/** alias 证据类型；展示名不属于绑定证据。 */
enum class ApplicationAliasEvidenceType(val code: String) {
    AUTO_SAME_SOURCE("AUTO_SAME_SOURCE"),
    MANUAL_REBIND("MANUAL_REBIND");

    companion object {
        /** 严格解析证据编码，防止未知证据被误认为人工审核。 */
        fun fromCode(code: String): ApplicationAliasEvidenceType = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_ALIAS_EVIDENCE_TYPE_INVALID")
    }
}

/** 应用解析状态的稳定事实表与 HTTP 编码。 */
enum class ApplicationResolutionStatus(val code: String) {
    RESOLVED("RESOLVED"),
    UNRESOLVED("UNRESOLVED"),
    AMBIGUOUS("AMBIGUOUS");

    companion object {
        /** 严格解析事实表编码，未知状态不参与后续分类。 */
        fun fromCode(code: String): ApplicationResolutionStatus = entries.firstOrNull { it.code == code }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_RESOLUTION_STATUS_INVALID")
    }
}

/** 主问题分类初始状态；应用身份影子阶段只能写入待分类。 */
enum class PrimaryProblemClassificationStatus(val code: String) {
    PENDING("PENDING")
}

/** 规范化 alias 的跨表业务键；字符串仅在此已校验值对象内传播。 */
data class ApplicationAliasKey(
    val sourceChannel: ApplicationSourceChannel,
    val aliasType: ApplicationAliasType,
    val normalizedAlias: String
)

/** 原始来源 alias；规范化在事务外执行，并保持既有 NFKC/大小写规则。 */
data class ApplicationAliasInput(
    val sourceChannel: ApplicationSourceChannel,
    val aliasType: ApplicationAliasType,
    val rawAlias: String?
) {
    /** 将开放文本转换为有界业务键；空白表示没有可用身份事实。 */
    fun normalized(): ApplicationAliasKey? {
        val canonical = rawAlias
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        if (canonical.length > MAX_ALIAS_LENGTH) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_ALIAS_TOO_LONG")
        }
        val value = when (aliasType) {
            ApplicationAliasType.SDK_PACKAGE_NAME -> canonical.lowercase(Locale.ROOT)
            ApplicationAliasType.SDK_APP_ID,
            ApplicationAliasType.STATION_SOURCE_APP_ID -> canonical
        }
        return ApplicationAliasKey(sourceChannel, aliasType, value)
    }

    companion object {
        const val MAX_ALIAS_LENGTH = 200
    }
}

/** Service 与 Repository 之间的 identity 快照；不暴露持久化 PO。 */
data class ApplicationIdentitySnapshot(
    val id: Long,
    val publicId: String,
    val displayName: String,
    val mappingVersion: Long,
    val lifecycleStatus: ApplicationIdentityLifecycleStatus = ApplicationIdentityLifecycleStatus.ACTIVE
)

/** 活动 alias 及其 identity 快照，用于批量解析与并发复核。 */
data class ActiveApplicationAlias(
    val publicId: String,
    val key: ApplicationAliasKey,
    val aliasVersion: Long,
    val identity: ApplicationIdentitySnapshot
)

/** 已通过来源表完整性校验的单条应用身份输入。 */
data class ApplicationIdentitySourceInput(
    val sourceId: Long,
    val sourceChannel: ApplicationSourceChannel,
    val aliases: List<ApplicationAliasInput>,
    val displayNameEvidence: String?
)

/** 新建 identity 的领域写模型；RepositoryImpl 负责转换为 PO。 */
data class NewApplicationIdentity(
    val publicId: String,
    val displayName: String,
    val createdBy: Long? = null
)

/** alias 历史读模型，避免 Service 接触 MyBatis PO 和数据库字符串。 */
data class ApplicationIdentityAliasSnapshot(
    val id: Long?,
    val aliasPublicId: String,
    val key: ApplicationAliasKey,
    val activeAliasKeyHex: String?,
    val applicationIdentityId: Long,
    val aliasVersion: Long,
    val bindingStatus: ApplicationAliasBindingStatus,
    val evidenceType: ApplicationAliasEvidenceType,
    val previousBindingId: Long?,
    val requestId: String?,
    val commandFingerprintHex: String?,
    val targetMappingVersionSnapshot: Long?,
    val changedBy: Long?,
    val changeReason: String?
)

/** 人工 alias 版本写模型；稳定字符串只在 RepositoryImpl 转为 PO。 */
data class NewApplicationIdentityAlias(
    val aliasPublicId: String,
    val key: ApplicationAliasKey,
    val activeAliasKeyHex: String,
    val applicationIdentityId: Long,
    val aliasVersion: Long = 1,
    val evidenceType: ApplicationAliasEvidenceType,
    val previousBindingId: Long? = null,
    val requestId: String? = null,
    val commandFingerprintHex: String? = null,
    val targetMappingVersionSnapshot: Long? = null,
    val changedBy: Long? = null,
    val changeReason: String? = null
)

/** 人工审核候选的唯一权威 Station alias 及当前来源计数。 */
data class ReviewedStationCandidate(val key: ApplicationAliasKey, val sourceCount: Long)

/** 仅包含 masking PASS 且未删除的文本；不得记录到应用身份审计。 */
data class ApplicationIdentitySafeText(val title: String?, val description: String?)

/** 主问题应用身份事实领域模型；RepositoryImpl 负责与 PO 字符串互转。 */
data class PrimaryProblemFact(
    val feedbackSourceId: Long,
    val applicationIdentityId: Long?,
    val applicationPublicIdSnapshot: String?,
    val applicationDisplayNameSnapshot: String?,
    val applicationMappingVersion: Long?,
    val applicationResolutionStatus: ApplicationResolutionStatus,
    val classificationStatus: PrimaryProblemClassificationStatus,
    val resolutionRuleVersion: String,
    val resolutionReasonCode: String?,
    val resolvedAliasCount: Int
)
