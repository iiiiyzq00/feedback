package com.feedback.service.cluster

import com.feedback.common.exception.ClientException
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ClusterShadowGenerationRunnerTest {
    private val service = mockk<ClusterShadowGenerationService>(relaxed = true)
    private val arguments = mockk<ApplicationArguments>()

    @Test
    fun `缺少隔离写入确认时不调用 generation service`() {
        val stderr = ByteArrayOutputStream()
        val previous = System.err
        val error = try {
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            assertThrows(ClientException::class.java) {
                runner(confirmation = "").run(arguments)
            }
        } finally {
            System.setErr(previous)
        }

        assertEquals("CLUSTER_SHADOW_GENERATION_CONFIRMATION_REQUIRED", error.reasonCode)
        assertEquals(
            "CLUSTER_SHADOW_GENERATION_FAILED:CLUSTER_SHADOW_GENERATION_CONFIRMATION_REQUIRED",
            stderr.toString(Charsets.UTF_8).trim()
        )
        verify(exactly = 0) { service.build(any()) }
        verify(exactly = 0) { service.verify(any()) }
    }

    @Test
    fun `被禁止的旧 generation 永远不得 VERIFY`() {
        val error = assertThrows(ClientException::class.java) {
            runner(
                action = "VERIFY",
                generationKey = ClusterShadowGenerationService.STALE_PRE_GOVERNANCE_GENERATION
            ).run(arguments)
        }

        assertEquals("CLUSTER_SHADOW_GENERATION_VERIFY_FORBIDDEN", error.reasonCode)
        verify(exactly = 0) { service.verify(any()) }
    }

    @Test
    fun `新的隔离 generation 可以 BUILD`() {
        runner().run(arguments)

        verify(exactly = 1) {
            service.build(ClusterShadowGenerationCommand("shadow-11-full-20260909-post-identity", 9))
        }
        verify(exactly = 0) { service.verify(any()) }
    }

    @Test
    fun `PRECHECK 只调用无写入预检`() {
        runner(action = "PRECHECK").run(arguments)

        verify(exactly = 1) {
            service.preflight(ClusterShadowGenerationCommand("shadow-11-full-20260909-post-identity", 9))
        }
        verify(exactly = 0) { service.build(any()) }
        verify(exactly = 0) { service.verify(any()) }
        verify(exactly = 0) { service.abandonStale(any()) }
    }

    @Test
    fun `ABANDON STALE 只能关闭精确批准的旧槽位`() {
        val invalid = assertThrows(ClientException::class.java) {
            runner(action = "ABANDON_STALE").run(arguments)
        }
        assertEquals("CLUSTER_SHADOW_GENERATION_ABANDON_SCOPE_INVALID", invalid.reasonCode)
        verify(exactly = 0) { service.abandonStale(any()) }

        runner(
            action = "ABANDON_STALE",
            generationKey = ClusterShadowGenerationService.STALE_PRE_GOVERNANCE_GENERATION
        ).run(arguments)
        verify(exactly = 1) {
            service.abandonStale(ClusterShadowGenerationCommand(
                ClusterShadowGenerationService.STALE_PRE_GOVERNANCE_GENERATION, 9
            ))
        }
    }

    private fun runner(
        confirmation: String = ClusterShadowGenerationRunner.CONFIRMATION,
        action: String = "BUILD",
        generationKey: String = "shadow-11-full-20260909-post-identity"
    ) = ClusterShadowGenerationRunner(service, confirmation, action, generationKey, 9)
}
