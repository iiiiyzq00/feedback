package com.feedback.model.applicationidentity

import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.ApplicationResolutionStatus

/** 纯解析器的封闭结果；没有持久化或审计副作用。 */
sealed interface ApplicationResolution {
    val status: ApplicationResolutionStatus
    val keys: List<ApplicationAliasKey>

    /** 已有 identity 唯一命中，并返回同源可自动绑定的缺失 alias。 */
    data class Resolved(
        val identity: ApplicationIdentitySnapshot,
        override val keys: List<ApplicationAliasKey>,
        val aliasesToBind: List<ApplicationAliasKey>
    ) : ApplicationResolution {
        override val status = ApplicationResolutionStatus.RESOLVED
    }

    /** 本批 alias 尚无映射，可在快照复核后保守创建 identity。 */
    data class NewIdentity(
        override val keys: List<ApplicationAliasKey>,
        val displayName: String
    ) : ApplicationResolution {
        override val status = ApplicationResolutionStatus.RESOLVED
    }

    /** 没有任何权威 alias 的失败关闭结果。 */
    data object Unresolved : ApplicationResolution {
        override val status = ApplicationResolutionStatus.UNRESOLVED
        override val keys = emptyList<ApplicationAliasKey>()
    }

    /** alias 指向多个 identity 的歧义结果，禁止自动合并。 */
    data class Ambiguous(
        override val keys: List<ApplicationAliasKey>,
        val identityPublicIds: Set<String>
    ) : ApplicationResolution {
        override val status = ApplicationResolutionStatus.AMBIGUOUS
    }
}

/** 影子批次结果；只包含计数、游标和规则版本。 */
data class ApplicationIdentityShadowBatchResult(
    val processedCount: Int,
    val resolvedCount: Int,
    val unresolvedCount: Int,
    val ambiguousCount: Int,
    val createdIdentityCount: Int,
    val createdAliasCount: Int,
    val nextSourceId: Long,
    val mayHaveMore: Boolean,
    val resolutionRuleVersion: String
)
