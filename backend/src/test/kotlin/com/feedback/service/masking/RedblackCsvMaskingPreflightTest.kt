package com.feedback.service.masking

import com.feedback.repository.MaskingAuditRepository
import com.feedback.tools.RedblackReviewRecoveryInput
import com.feedback.tools.RedblackReviewRecoveryPlanner
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RedblackCsvMaskingPreflightTest {
    private val mapper = jacksonObjectMapper()
    private val pipeline = MaskingPipeline(mockk<MaskingAuditRepository>(), mapper, NoopNerMasker)

    @Test
    fun `matched legacy reviews produce safe copies and preserve source deletion boundary`() {
        val configured = System.getenv("REDBLACK_PREFLIGHT_INPUT")
        assumeTrue(!configured.isNullOrBlank(), "REDBLACK_PREFLIGHT_INPUT is required for the real-file preflight")
        val input = Path.of(configured!!)
        assumeTrue(Files.isRegularFile(input), "redblack preflight input must exist")

        val records: List<RedblackReviewRecoveryInput> = mapper.readValue(Files.readString(input))
        assertEquals(25, records.size)
        assertEquals(25, records.map(RedblackReviewRecoveryInput::idHash).distinct().size)
        assertEquals(1, records.count(RedblackReviewRecoveryInput::sourceDeleted))

        val prepared = RedblackReviewRecoveryPlanner(pipeline).prepare(records)
        val eligible = prepared.filter { it.safeHeadline != null && it.safeContent != null }
        assertEquals(24, eligible.size)
        eligible.forEach { record ->
            assertTrue(record.safeHeadline!!.isNotBlank())
            assertTrue(record.safeContent!!.isNotBlank())
            assertFalse(UNMASKED_LONG_DIGITS.containsMatchIn(record.safeHeadline))
            assertFalse(UNMASKED_LONG_DIGITS.containsMatchIn(record.safeContent))
        }
    }

    companion object {
        private val UNMASKED_LONG_DIGITS = Regex("(?<![0-9])[0-9]{10,}(?![0-9])")
    }
}
