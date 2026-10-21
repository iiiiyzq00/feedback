package com.feedback.service.imports

import com.feedback.security.WebPrincipal
import com.feedback.repository.imports.ImportReconciliationData
import com.feedback.repository.imports.ImportReconciliationRecord
import com.feedback.repository.imports.ImportReconciliationRepository
import com.feedback.repository.auth.SystemAuditMutationRepository
import com.feedback.model.po.imports.ImportReconciliationBatchPo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

class ImportReconciliationExportServiceTest {
    private val repository = mockk<ImportReconciliationRepository>()
    private val audits = mockk<SystemAuditMutationRepository>(relaxed = true)
    private val service = ImportReconciliationExportService(repository, jacksonObjectMapper(), fallbackAudits = audits)

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `export keeps row failure evidence and produces readable workbook`() {
        every { repository.load("batch-1") } returns ImportReconciliationData(
            ImportReconciliationBatchPo(7,"JIRA","PARTIAL_SUCCESS","MATCHED",LocalDateTime.now()),
            listOf(ImportReconciliationRecord(
                "JIRA",null,null,null,null,null,null,"INVALID","INVALID_DATE","来源事件时间格式非法",0,0
            ))
        )
        val principal = WebPrincipal(1, "admin", "", "Admin", setOf("ADMIN"), setOf("feedback:export"))
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)

        val (_, bytes) = service.export("batch-1")

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheet("对账报告")
            assertNotNull(sheet)
            assertEquals("fail_code", sheet.getRow(0).getCell(8).stringCellValue)
            assertEquals("INVALID_DATE", sheet.getRow(1).getCell(8).stringCellValue)
            assertEquals("来源事件时间格式非法", sheet.getRow(1).getCell(9).stringCellValue)
            assertTrue(sheet.getColumnWidth(3) >= 40 * 256)
            assertEquals(1, sheet.paneInformation.horizontalSplitPosition)
            assertTrue(sheet.ctWorksheet.isSetAutoFilter)
        }
    }
}
