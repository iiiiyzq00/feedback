package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.ExternalLinkPolicy
import com.feedback.security.WebPrincipal
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder

data class ResolvedFeedbackExternalLink(val url: String, val host: String)

/** 外链角色与结构策略；配置通过专用端口取得，不感知 Spring 配置类或持久化对象。 */
@Component
class FeedbackExternalLinkResolver(
    private val configuration: FeedbackExternalLinkConfigurationPort,
    private val policy: ExternalLinkPolicy
) {
    fun mayOpen(channel: String, principal: WebPrincipal): Boolean = policy.mayOpen(channel, principal)

    fun available(facts: FeedbackExternalLinkFacts, principal: WebPrincipal): Boolean =
        mayOpen(facts.channel, principal) && resolve(facts) != null

    fun require(facts: FeedbackExternalLinkFacts): ResolvedFeedbackExternalLink =
        resolve(facts) ?: throw ClientException(ErrorCode.NOT_FOUND, "EXTERNAL_LINK_NOT_CONFIGURED")

    private fun resolve(facts: FeedbackExternalLinkFacts): ResolvedFeedbackExternalLink? {
        val raw = when (facts) {
            is JiraExternalLinkFacts -> {
                val key = facts.jiraKey?.takeIf(String::isNotBlank) ?: return null
                val base = configuration.jiraBaseUrl()?.takeIf(String::isNotBlank) ?: return null
                "${base.trimEnd('/')}/browse/${URLEncoder.encode(key, Charsets.UTF_8)}"
            }
            is RedblackExternalLinkFacts -> facts.url?.takeIf(String::isNotBlank) ?: return null
            is UnsupportedExternalLinkFacts -> return null
        }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        return raw.takeIf {
            uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }?.let { ResolvedFeedbackExternalLink(url = it, host = uri.host) }
    }
}
