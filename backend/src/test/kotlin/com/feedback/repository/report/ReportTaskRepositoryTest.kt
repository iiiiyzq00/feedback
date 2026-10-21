package com.feedback.repository.report

import com.feedback.common.exception.ServiceException
import com.feedback.mapper.report.ReportActorMapper
import com.feedback.mapper.report.ReportActorPermissionMapper
import com.feedback.mapper.report.ReportActorRoleMapper
import com.feedback.mapper.report.ReportGenerationTaskMapper
import com.feedback.mapper.report.ReportMapper
import com.feedback.mapper.report.ReportNotificationMapper
import com.feedback.mapper.report.ReportOccurrenceMapper
import com.feedback.mapper.report.ReportSupportTaskMapper
import com.feedback.mapper.report.ReportTaskReportMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReportTaskRepositoryTest {
    private val tasks=mockk<ReportGenerationTaskMapper>()
    private val reports=mockk<ReportTaskReportMapper>()
    private val notifications=mockk<ReportNotificationMapper>()
    private val occurrences=mockk<ReportOccurrenceMapper>()
    private val supportTasks=mockk<ReportSupportTaskMapper>()
    private val reportRows=mockk<ReportMapper>()
    private val repository=ReportTaskRepository(
        ReportTaskLifecycleRepository(tasks,reports),
        ReportArtifactCleanupRepository(notifications,occurrences,supportTasks,reportRows),
        ReportPrincipalRepository(mockk<ReportActorMapper>(),mockk<ReportActorRoleMapper>(),mockk<ReportActorPermissionMapper>()),
    )

    @Test fun `report attachment conflict prevents task completion`() {
        every{reports.attach(5,9)} returns 0

        assertThrows(ServiceException::class.java){repository.complete(5,9)}

        verify(exactly=0){tasks.complete(any(),any())}
    }

    @Test fun `lost lease rejects false failure state`() {
        every{tasks.failed(5,"lease-a",any(),any())} returns 0

        assertThrows(ServiceException::class.java){repository.failed(5,IllegalStateException("boom"),"lease-a")}
    }

    @Test fun `cancelled generated report removes delivery attempts before dependencies`() {
        every{notifications.idsByReport(9,0,500)} returns listOf(10,11)
        every{occurrences.deleteDeliveryAttempts(listOf(10,11))} returns 3
        every{notifications.deleteByReport(9)} returns 2
        every{supportTasks.deleteByReport(9)} returns 1
        every{reportRows.delete(9)} returns 1
        every{tasks.markCancelled(5)} returns 1

        repository.cancelCompletedReport(5,9)

        verify{occurrences.deleteDeliveryAttempts(listOf(10,11))}
        verify{tasks.markCancelled(5)}
    }

    @Test fun `notification cleanup conflict prevents cancelled task state`() {
        every{notifications.idsByReport(9,0,500)} returns listOf(10,11)
        every{occurrences.deleteDeliveryAttempts(listOf(10,11))} returns 1
        every{notifications.deleteByReport(9)} returns 1

        assertThrows(ServiceException::class.java){repository.cancelCompletedReport(5,9)}

        verify(exactly=0){reportRows.delete(any())}
        verify(exactly=0){tasks.markCancelled(any())}
    }
}
