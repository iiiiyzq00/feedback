package com.feedback.service.imports

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

class ImportFileParserTest {
    private val parser = ImportFileParser()

    @Test
    fun `parses csv headers and quoted cells`() {
        val csv = """
            id,title,description
            J-1,"AI, 助教异常","无法回复"
            J-2,课程播放,卡顿
        """.trimIndent().toByteArray()

        val parsed = parser.parse("jira.csv", csv)

        assertEquals(1, parsed.sheets.size)
        assertEquals(listOf("id", "title", "description"), parsed.sheets.single().header)
        assertEquals(2, parsed.totalRows)
        assertEquals("AI, 助教异常", parsed.sheets.single().dataRows.first()[1])
    }

    @Test fun `streams xlsx and preserves sparse columns`() {
        val bytes=ByteArrayOutputStream().use { output -> XSSFWorkbook().use { book ->
            val sheet=book.createSheet("Jira导入")
            sheet.createRow(0).apply{createCell(0).setCellValue("id");createCell(2).setCellValue("description")}
            sheet.createRow(1).apply{createCell(0).setCellValue("J-1");createCell(2).setCellValue("页面卡顿")}
            book.write(output)
        };output.toByteArray() }

        val parsed=parser.parse("jira.xlsx",bytes).sheets.single()

        assertEquals("Jira导入",parsed.name)
        assertEquals(listOf("id","","description"),parsed.header)
        assertEquals(listOf("J-1","","页面卡顿"),parsed.dataRows.single())
    }
}
