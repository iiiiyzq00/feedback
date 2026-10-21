package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.exception.ClientException
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.ApplicationArguments
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class ApplicationReviewedBindingPreflightCommandRunnerTest {
    private val preflight = mockk<ApplicationReviewedBindingPreflightService>()
    private val json = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `missing confirmation performs no read and creates no command file`(@TempDir dir: Path) {
        val input = dir.resolve("input.json")
        Files.writeString(input, "{}")
        val output = dir.resolve("commands.json")

        val error = assertThrows(ClientException::class.java) {
            runner("", input, output).run(mockk<ApplicationArguments>())
        }

        assertEquals("APPLICATION_PREFLIGHT_CONFIRMATION_REQUIRED", error.reasonCode)
        assertFalse(Files.exists(output))
        verify(exactly = 0) { preflight.prepare(any()) }
        verify(exactly = 0) { preflight.check(any()) }
    }

    @Test
    fun `four approved decisions produce owner only exact command package`(@TempDir dir: Path) {
        val input = dir.resolve("input.json")
        json.writeValue(input.toFile(), ReviewedBindingPreflightFile(
            confirmation = ApplicationReviewedBindingPreflightCommandRunner.CONFIRMATION,
            decisions = listOf(
                decision("优学练习", 60, "NEW_APPLICATION"),
                decision("识字词", 54, "NEW_APPLICATION"),
                decision("自然拼读", 46, "NEW_APPLICATION"),
                decision("希沃家长圈", 47, "ALIAS_OF_EXISTING", "希沃家长圈")
            )
        ))
        val target = ApplicationIdentitySnapshot(7, "22222222-2222-4222-8222-222222222222", "希沃家长圈", 9)
        every { preflight.prepare(any()) } answers {
            firstArg<List<ApplicationReviewedBindingPreparation>>().mapIndexed { index, preparation ->
                ApplicationReviewedBindingCommand(
                    preparation.candidateDisplayName,
                    preparation.expectedSourceCount,
                    preparation.decision,
                    target.takeIf { preparation.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING }?.publicId,
                    target.takeIf { preparation.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING }?.mappingVersion,
                    "bbbbbbbb-bbbb-4bbb-8bbb-${(index + 1).toString().padStart(12, '0')}",
                    preparation.reason
                )
            }
        }
        every { preflight.check(any()) } answers {
            val commands = firstArg<List<ApplicationReviewedBindingCommand>>()
            assertEquals(target.publicId, commands.single { it.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING }.targetApplicationId)
            ApplicationReviewedBindingPreflightReport(4, 3, 1, 207, 0, true)
        }
        val output = dir.resolve("commands.json")
        val stdout = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            runner(ApplicationReviewedBindingPreflightCommandRunner.CONFIRMATION, input, output)
                .run(mockk<ApplicationArguments>())
        } finally {
            System.setOut(previous)
        }

        val prepared = json.readValue(output.toFile(), PreparedReviewedBindingCommandFile::class.java)
        assertEquals(4, prepared.commands.size)
        assertEquals(4, prepared.commands.map { it.command.requestId }.distinct().size)
        assertTrue(prepared.commands.all { it.commandFingerprintHex == reviewedBindingCommandFingerprint(it.command) })
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(output))
        val emitted = stdout.toString(Charsets.UTF_8).trim()
        val result = json.readTree(emitted)
        assertEquals(207, result.path("expectedAffectedSourceCount").asInt())
        listOf("targetApplicationId", "candidateDisplayName", "requestId").forEach {
            assertFalse(emitted.contains(it), it)
        }
    }

    @Test
    fun `ambiguous target name fails before preflight and output`(@TempDir dir: Path) {
        val input = dir.resolve("input.json")
        json.writeValue(input.toFile(), ReviewedBindingPreflightFile(
            confirmation = ApplicationReviewedBindingPreflightCommandRunner.CONFIRMATION,
            decisions = listOf(decision("希沃家长圈", 47, "ALIAS_OF_EXISTING", "希沃家长圈"))
        ))
        every { preflight.prepare(any()) } throws com.feedback.common.exception.ServiceException(
            com.feedback.common.ErrorCode.APPLICATION_IDENTITY_CONFLICT,
            "APPLICATION_PREFLIGHT_TARGET_NAME_NOT_UNIQUE"
        )
        val output = dir.resolve("commands.json")

        val error = assertThrows(Exception::class.java) {
            runner(ApplicationReviewedBindingPreflightCommandRunner.CONFIRMATION, input, output)
                .run(mockk<ApplicationArguments>())
        }

        assertEquals(
            "APPLICATION_PREFLIGHT_TARGET_NAME_NOT_UNIQUE",
            (error as com.feedback.common.exception.ServiceException).reasonCode
        )
        assertFalse(Files.exists(output))
        verify(exactly = 0) { preflight.check(any()) }
    }

    private fun runner(confirmation: String, input: Path, output: Path) =
        ApplicationReviewedBindingPreflightCommandRunner(
            preflight, json, confirmation, input.toString(), output.toString()
        )

    private fun decision(
        name: String,
        count: Long,
        decision: String,
        target: String? = null
    ) = ReviewedBindingPreflightDecision(name, count, decision, target, "业务审核已确认应用身份边界")
}
