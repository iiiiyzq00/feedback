package com.feedback.persistence

import org.apache.ibatis.io.Resources
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReportSchedulerMyBatisContractTest {
    @Test
    fun `all schedule run terminal updates require running state`() {
        val xml = Resources.getResourceAsReader(
            "mybatis/mapper/scheduler/ReportScheduleRunMapper.xml",
        ).use { it.readText() }

        assertEquals(3, "AND task_status = 'RUNNING'".toRegex().findAll(xml).count())
    }
}
