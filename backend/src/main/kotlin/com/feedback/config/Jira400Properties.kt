package com.feedback.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.time.ZoneId

@Component
@ConfigurationProperties(prefix = "app.jira400")
class Jira400Properties {
    var enabled: Boolean = false
    var connectionKey: String = "jira400-primary"
    var baseUrl: String = ""
    var allowedHosts: List<String> = emptyList()
    var token: String = ""
    var authType: String = "BEARER"
    var authHeader: String = "Authorization"
    var tokenPrefix: String = "Bearer "
    var searchPath: String = "/rest/api/2/search"
    var httpMethod: String = "POST"
    var jql: String = ""
    var pageSize: Int = 100
    var maxIssuesPerRun: Int = 5_000
    var initialLookbackDays: Int = 7
    var overlapMinutes: Int = 5
    var requestTimeoutSeconds: Long = 30
    var scheduleDelayMs: Long = 300_000
    var workerDelayMs: Long = 1_000
    var scheduleIntervalMinutes: Int = 30
    var timeZone: String = "Asia/Shanghai"
    var allowHttpForLoopback: Boolean = false

    fun readinessErrors(): List<String> = buildList {
        if (!enabled) add("JIRA_API_DISABLED")
        if (connectionKey.isBlank() || connectionKey.length > 50) add("JIRA_CONNECTION_KEY_INVALID")
        if (baseUrl.isBlank()) add("JIRA_BASE_URL_MISSING") else runCatching { URI(baseUrl) }.fold(
            onSuccess = { uri ->
                val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
                if (uri.host.isNullOrBlank()) add("JIRA_BASE_URL_INVALID")
                if (uri.scheme != "https" && !(allowHttpForLoopback && loopback)) add("JIRA_BASE_URL_HTTPS_REQUIRED")
                if (uri.userInfo != null || uri.query != null || uri.fragment != null) add("JIRA_BASE_URL_INVALID")
                val allowlist = allowedHosts.map { it.trim().lowercase() }.filter(String::isNotBlank)
                if (allowlist.isEmpty()) add("JIRA_ALLOWED_HOSTS_MISSING")
                else if (uri.host?.lowercase() !in allowlist) add("JIRA_HOST_NOT_ALLOWED")
            },
            onFailure = { add("JIRA_BASE_URL_INVALID") }
        )
        if (token.isBlank()) add("JIRA_TOKEN_MISSING")
        if (authHeader.isBlank() || authHeader.any { it <= ' ' || it == ':' }) add("JIRA_AUTH_HEADER_INVALID")
        if (authType.uppercase() !in setOf("BEARER", "TOKEN", "BASIC")) add("JIRA_AUTH_TYPE_INVALID")
        if (tokenPrefix.any { it == '\r' || it == '\n' }) add("JIRA_TOKEN_PREFIX_INVALID")
        if (!searchPath.startsWith('/') || searchPath.contains("..")) add("JIRA_SEARCH_PATH_INVALID")
        if (httpMethod.uppercase() !in setOf("GET", "POST")) add("JIRA_HTTP_METHOD_INVALID")
        if (jql.isBlank()) add("JIRA_JQL_MISSING")
        if (Regex("(?i)\\border\\s+by\\b").containsMatchIn(jql)) add("JIRA_JQL_ORDER_NOT_ALLOWED")
        if (pageSize !in 1..1000) add("JIRA_PAGE_SIZE_INVALID")
        if (maxIssuesPerRun !in pageSize..50_000) add("JIRA_MAX_ISSUES_INVALID")
        if (initialLookbackDays !in 1..3650) add("JIRA_INITIAL_LOOKBACK_INVALID")
        if (overlapMinutes !in 0..1440) add("JIRA_OVERLAP_INVALID")
        if (requestTimeoutSeconds !in 1..120) add("JIRA_TIMEOUT_INVALID")
        if (scheduleIntervalMinutes !in 1..1440) add("JIRA_SCHEDULE_INTERVAL_INVALID")
        if (workerDelayMs !in 250..60_000) add("JIRA_WORKER_DELAY_INVALID")
        if (runCatching { ZoneId.of(timeZone) }.isFailure) add("JIRA_TIME_ZONE_INVALID")
    }

    fun ready(): Boolean = readinessErrors().isEmpty()
}
