package com.feedback.manager.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
import org.springframework.stereotype.Component

/**
 * 纯应用身份规则边界。它只比较规范化 alias 与调用方提供的活动映射，不访问数据库或网络。
 * 展示名永远不能成为身份关联证据；已落到不同 identity 的 alias 必须失败关闭为歧义。
 */
@Component
class ApplicationIdentityResolver {
    /** 批量 alias 映射仅在调用方给定范围内解析；跨 identity 证据失败关闭为歧义。 */
    fun resolve(
        input: ApplicationIdentitySourceInput,
        activeAliases: Map<ApplicationAliasKey, ActiveApplicationAlias>
    ): ApplicationResolution {
        if (input.sourceId <= 0) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_SOURCE_ID_INVALID")
        }
        if (!input.aliases.all { it.sourceChannel == input.sourceChannel }) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_ALIAS_CHANNEL_MISMATCH")
        }
        val keys = input.aliases.mapNotNull(ApplicationAliasInput::normalized).distinct()
        if (keys.isEmpty()) {
            return ApplicationResolution.Unresolved
        }

        val unexpected = activeAliases.keys - keys.toSet()
        if (unexpected.isNotEmpty()) {
            throw ServiceException(
                ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED,
                "APPLICATION_ALIAS_LOOKUP_SCOPE_INVALID"
            )
        }
        val mapped = keys.mapNotNull(activeAliases::get)
        val identities = mapped.map { it.identity }.distinctBy { it.id }
        if (identities.size > 1) {
            return ApplicationResolution.Ambiguous(keys, identities.map { it.publicId }.toSet())
        }
        if (identities.size == 1) {
            val identity = identities.single()
            return ApplicationResolution.Resolved(identity, keys, keys.filterNot(activeAliases::containsKey))
        }
        return ApplicationResolution.NewIdentity(keys, displayName(input, keys))
    }

    /** 新 identity 展示名优先来源展示证据，再退回 package/alias；该值不参与关联。 */
    private fun displayName(input: ApplicationIdentitySourceInput, keys: List<ApplicationAliasKey>): String {
        val evidence = input.displayNameEvidence?.trim()?.takeIf(String::isNotEmpty)?.take(200)
        if (evidence != null) {
            return evidence
        }
        return keys.firstOrNull { it.aliasType == ApplicationAliasType.SDK_PACKAGE_NAME }?.normalizedAlias
            ?: keys.first().normalizedAlias.take(200)
    }
}
