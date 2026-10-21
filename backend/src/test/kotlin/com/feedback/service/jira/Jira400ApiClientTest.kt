package com.feedback.service.jira

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.Jira400Properties
import com.feedback.common.exception.RemoteException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class Jira400ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var properties: Jira400Properties

    @BeforeEach fun setUp() {
        server = MockWebServer().also { it.start() }
        properties = Jira400Properties().apply {
            enabled = true
            baseUrl = server.url("/").toString().removeSuffix("/")
            token = "test-token-never-log"
            jql = "project = DEMO"
            allowHttpForLoopback = true
            allowedHosts = listOf("localhost")
        }
    }

    @AfterEach fun tearDown() = server.shutdown()

    @Test
    fun `posts bounded read-only search with token and parses page`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"startAt":0,"total":1,"issues":[{"id":"10001","key":"DEMO-1","fields":{"summary":"闪退","description":"打开即闪退","updated":"2026-08-14T10:00:00.000+0800"}}]}"""
        ))
        val page = Jira400ApiClient(properties, jacksonObjectMapper()).search("project = DEMO", 0, 100, listOf("summary", "updated"))
        assertEquals("DEMO-1", page.issues.single().key)
        assertEquals(true, page.isLast)
        val request = server.takeRequest()
        assertEquals("Bearer test-token-never-log", request.getHeader("Authorization"))
        assertEquals("/rest/api/2/search", request.path)
        val body = jacksonObjectMapper().readTree(request.body.readUtf8())
        assertEquals("project = DEMO", body.path("jql").asText())
        assertEquals(100, body.path("maxResults").asInt())
        assertEquals(listOf("summary", "updated"), body.path("fields").map { it.asText() })
    }

    @Test
    fun `maps rate limit to safe error without response body`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("secret upstream response"))
        val error = assertThrows(RemoteException::class.java) {
            Jira400ApiClient(properties, jacksonObjectMapper()).search("project = DEMO", 0, 100, listOf("summary"))
        }
        assertEquals("JIRA_RATE_LIMITED", error.errorCode.name)
        assertEquals(30L, error.details["retryAfterSeconds"])
        assert(!error.message.orEmpty().contains("secret upstream response"))
    }
}
