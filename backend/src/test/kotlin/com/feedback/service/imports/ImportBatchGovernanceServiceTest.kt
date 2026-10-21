package com.feedback.service.imports

import com.feedback.common.exception.ClientException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.feedback.repository.imports.ImportGovernanceRepository
import com.feedback.model.po.imports.ImportGovernanceBatchPo
import com.feedback.mapper.imports.ImportGovernanceMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class ImportBatchGovernanceServiceTest {
    @Test
    fun `cleanup archives covered and no change batches while retaining facts`() {
        val repository=mockk<ImportGovernanceRepository>()
        every { repository.cleanup(9, any(), any()) } returns Triple(6,12,14)

        val result=ImportBatchGovernanceService(repository).cleanup(9,"清理已被权威补数覆盖的重复批次")

        assertEquals(6,result.supersededBatches)
        assertEquals(12,result.noChangeBatches)
        assertEquals(14,result.activeBatches)
        assertTrue(result.preservedAuditFacts)
        verify(exactly=1) { repository.cleanup(9,"清理已被权威补数覆盖的重复批次",any()) }
    }

    @Test
    fun `cleanup requires an auditable reason`() {
        val repository=mockk<ImportGovernanceRepository>()
        assertThrows<ClientException> { ImportBatchGovernanceService(repository).cleanup(9,"太短") }
        verify(exactly=0) { repository.cleanup(any(),any(),any()) }
    }

    @Test
    fun `repository selects newest covering backfill without deleting audit facts`() {
        val mapper=mockk<ImportGovernanceMapper>()
        val hash=byteArrayOf(1,2,3)
        val start=LocalDateTime.parse("2026-01-01T00:00:00")
        fun row(id:Long,trigger:String?,from:LocalDateTime,to:LocalDateTime,status:String="COMPLETED")=
            ImportGovernanceBatchPo(id,"JIRA",trigger,hash,"v1",from,to,status,"MATCHED","ACTIVE",10)
        every { mapper.findApiBatches() } returns listOf(
            row(1,null,start.plusDays(1),start.plusDays(2)),
            row(2,"BACKFILL",start,start.plusDays(3)),
            row(3,"BACKFILL",start.minusDays(1),start.plusDays(4))
        )
        every { mapper.supersede(any(),any(),eq(9),any()) } answers {
            val rows=firstArg<List<com.feedback.model.po.imports.ImportSupersedePo>>()
            assertEquals(listOf(1L to 3L,2L to 3L),rows.map { it.oldId to it.newerId })
            rows.size
        }
        every { mapper.archiveNoChange(any(),eq(9),any()) } returns 0
        every { mapper.countActive() } returns 2

        val result=ImportGovernanceRepository(mapper).cleanup(9,"清理已被权威补数覆盖的重复批次",start)

        assertEquals(Triple(2,0,2),result)
    }
}
