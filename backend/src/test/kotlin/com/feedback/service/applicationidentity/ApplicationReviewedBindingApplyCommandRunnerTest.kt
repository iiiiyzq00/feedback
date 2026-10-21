package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.exception.ClientException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.ApplicationArguments
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class ApplicationReviewedBindingApplyCommandRunnerTest {
    private val preflight = mockk<ApplicationReviewedBindingPreflightService>()
    private val batch = mockk<ApplicationReviewedBindingBatchService>()
    private val json = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `changed command package fails before preflight or write`(@TempDir dir: Path) {
        val path = ownerOnlyPackage(dir)
        val runner = runner(path, "0".repeat(64))

        val error = assertThrows(ClientException::class.java) { runner.run(mockk<ApplicationArguments>()) }

        assertEquals("APPLICATION_REVIEW_APPLY_COMMAND_FILE_CHANGED", error.reasonCode)
        verify(exactly = 0) { preflight.check(any()) }
        verify(exactly = 0) { batch.createAll(any(), any()) }
    }

    @Test
    fun `validated package executes one batch and emits aggregate only`(@TempDir dir: Path) {
        val path = ownerOnlyPackage(dir)
        val sha = sha256(Files.readAllBytes(path))
        every { preflight.check(any()) } returns ApplicationReviewedBindingPreflightReport(4, 3, 1, 207, 0, true)
        every { batch.createAll(any(), 99) } answers {
            firstArg<List<ApplicationReviewedBindingCommand>>().mapIndexed { index, command ->
                ApplicationReviewedBindingResult(
                    command.requestId, command.decision,
                    "aaaaaaaa-aaaa-4aaa-8aaa-${index.toString().padStart(12, '0')}",
                    "bbbbbbbb-bbbb-4bbb-8bbb-${index.toString().padStart(12, '0')}",
                    1, 1, command.expectedSourceCount, false
                )
            }
        }
        val stdout = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            runner(path, sha).run(mockk<ApplicationArguments>())
        } finally {
            System.setOut(previous)
        }

        val emitted = stdout.toString(Charsets.UTF_8)
        val result = json.readTree(emitted)
        assertEquals(207, result.path("affectedSourceCount").asInt())
        assertEquals(0, result.path("replayedCount").asInt())
        listOf("applicationId", "aliasId", "requestId", "candidateDisplayName").forEach {
            assertFalse(emitted.contains(it), it)
        }
        verify(exactly = 1) { batch.createAll(any(), 99) }
    }

    private fun ownerOnlyPackage(dir: Path): Path {
        val commands = listOf(
            command(1, "优学练习", 60, ApplicationReviewedBindingDecision.NEW_APPLICATION),
            command(2, "识字词", 54, ApplicationReviewedBindingDecision.NEW_APPLICATION),
            command(3, "自然拼读", 46, ApplicationReviewedBindingDecision.NEW_APPLICATION),
            command(4, "希沃家长圈", 47, ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING)
        )
        val file = PreparedReviewedBindingCommandFile(
            ApplicationReviewedBindingPreflightCommandRunner.SCHEMA_VERSION,
            commands.map { PreparedReviewedBindingCommand(it, reviewedBindingCommandFingerprint(it)) }
        )
        val path = dir.resolve("commands.json")
        json.writeValue(path.toFile(), file)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        return path
    }

    private fun command(index: Int, name: String, count: Long, decision: ApplicationReviewedBindingDecision) =
        ApplicationReviewedBindingCommand(
            name, count, decision,
            if (decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING) "cccccccc-cccc-4ccc-8ccc-cccccccccccc" else null,
            if (decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING) 1 else null,
            "dddddddd-dddd-4ddd-8ddd-${index.toString().padStart(12, '0')}",
            "业务审核已确认应用身份边界"
        )

    private fun runner(path: Path, sha: String) = ApplicationReviewedBindingApplyCommandRunner(
        preflight,
        batch,
        json,
        ApplicationReviewedBindingApplyCommandRunner.CONFIRMATION,
        path.toString(),
        sha,
        99
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
