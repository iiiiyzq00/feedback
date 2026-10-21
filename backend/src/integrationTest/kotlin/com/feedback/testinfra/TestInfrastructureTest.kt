package com.feedback.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import io.restassured.RestAssured.given
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TestInfrastructureTest {
    @Test
    fun `fixtures are deterministic and channel independent`() {
        val first = TestDataFixtures()
        val second = TestDataFixtures()
        assertEquals(first.sdk(), second.sdk())
        assertEquals(first.jira(), second.jira())
        assertEquals(first.deterministicUuid("cluster-1"), second.deterministicUuid("cluster-1"))
        assertTrue(first.quality().attributes["source_record_id"].toString().startsWith("QUALITY-"))
        assertTrue(first.redblack().attributes["source_record_id"].toString().startsWith("REDBLACK-"))
    }

    @Test
    fun `eventually polls to accepted state without fixed sleep`() {
        val attempts = AtomicInteger()
        val state = Eventually.await("worker completion", timeout = Duration.ofSeconds(1), probe = {
            if (attempts.incrementAndGet() >= 3) "COMPLETED" else "PROCESSING"
        }, accepted = { it == "COMPLETED" })
        assertEquals("COMPLETED", state)
    }

    @Test
    fun `eventually timeout contains state diagnostics`() {
        val error = assertThrows(AwaitTimeoutException::class.java) {
            Eventually.await<String>("stuck task", timeout = Duration.ofMillis(40), diagnostics = { "status=PROCESSING,retry=2" }, probe = { "PROCESSING" }, accepted = { false })
        }
        assertTrue(error.message!!.contains("status=PROCESSING,retry=2"))
        assertTrue(error.message!!.contains("attempts="))
    }

    @Test
    fun `api contract validates real http envelope`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"code":200,"message":"success","data":{"status":"UP"}}"""))
            server.start()
            ApiContract.configure(server.port)
            ApiContract.success(given().get("/health").then()).body("data.status", org.hamcrest.Matchers.equalTo("UP"))
            assertEquals("/api/v1/health", server.takeRequest().path)
            ApiContract.reset()
        }
    }
}
