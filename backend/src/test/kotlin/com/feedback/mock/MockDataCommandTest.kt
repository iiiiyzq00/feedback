package com.feedback.mock

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.apache.poi.ss.usermodel.WorkbookFactory

class MockDataCommandTest {
    @TempDir lateinit var temp: Path
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `sdk generator is deterministic and covers required dimensions`() {
        val one = options("generate-sdk-mock", 500, temp.resolve("one"))
        val two = options("generate-sdk-mock", 500, temp.resolve("two"))
        MockFixtureGenerator().generate(one)
        MockFixtureGenerator().generate(two)
        val first = one.output.resolve("sdk-500.ndjson")
        val second = two.output.resolve("sdk-500.ndjson")
        assertEquals(hash(first), hash(second))
        val manifest = mapper.readTree(one.output.resolve("manifest.json").toFile())
        assertEquals(500, manifest["record_count"].asInt())
        assertEquals(3, manifest["distribution"]["packages"].size())
        assertEquals(5, manifest["distribution"]["modules"].size())
        assertEquals(4, manifest["distribution"]["severity"].size())
        assertTrue(manifest["expected_outcomes"].has("REJECT_SIGNATURE"))
        assertTrue(manifest["expected_outcomes"].has("REVIEW_NEEDED"))
    }

    @Test
    fun `table generator writes standard files variants and manifest`() {
        val options = MockDataCommand.Options("generate-table-mock", 200, 20260804, "JIRA", temp.resolve("jira"), "both", "mixed")
        MockFixtureGenerator().generate(options)
        assertTrue(Files.size(options.output.resolve("jira-200.csv")) > 0)
        assertTrue(Files.size(options.output.resolve("jira-200.xlsx")) > 0)
        val header = Files.readAllLines(options.output.resolve("jira-200.csv")).first().removePrefix("\uFEFF")
        assertEquals(TableSchema.JIRA.headers.joinToString(",") { "\"$it\"" }, header)
        assertTrue(Files.exists(options.output.resolve("fixture-expectations.csv")))
        WorkbookFactory.create(options.output.resolve("jira-200.xlsx").toFile()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertEquals(201, sheet.physicalNumberOfRows)
            assertEquals(TableSchema.JIRA.headers, sheet.getRow(0).map { it.stringCellValue })
        }
        assertTrue(Files.exists(options.output.resolve("variants/missing-required.csv")))
        assertTrue(Files.exists(options.output.resolve("variants/channel-mismatch-quality.csv")))
        assertEquals(200, mapper.readTree(options.output.resolve("manifest.json").toFile())["record_count"].asInt())
    }

    @Test
    fun `invalid channel fails closed before writing`() {
        assertThrows(IllegalArgumentException::class.java) {
            MockDataCommand.Options.parse(arrayOf("--task=generate-table-mock", "--channel=UNKNOWN", "--count=10"))
        }
    }

    private fun options(task: String, count: Int, output: Path) = MockDataCommand.Options(task, count, 20260804, null, output, "ndjson", "mixed")
    private fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
}
