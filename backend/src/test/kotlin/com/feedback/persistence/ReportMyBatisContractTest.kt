package com.feedback.persistence

import org.apache.ibatis.io.Resources
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportMyBatisContractTest {
    @Test fun `report task failure remains lease compare and set`() {
        val xml=xml("ReportGenerationTaskMapper.xml")

        assertTrue(xml.contains("task_status = 'RUNNING' AND lease_holder = #{holder}"))
    }

    @Test fun `report attachment cannot replace another task`() {
        val xml=xml("ReportTaskReportMapper.xml")

        assertTrue(xml.contains("WHERE id = #{report} AND report_task_id IS NULL"))
    }

    @Test fun `idempotency reservation uses stable unique task hash`() {
        val xml=xml("ReportIdempotencyMapper.xml")

        assertTrue(xml.contains("CONCAT('REPORT_KEY:', SHA2(#{row.key}, 256))"))
        assertTrue(xml.contains("record_type = 'REPORT_IDEMPOTENCY'"))
    }

    private fun xml(name:String)=Resources.getResourceAsReader("mybatis/mapper/report/$name").use{it.readText()}
}
