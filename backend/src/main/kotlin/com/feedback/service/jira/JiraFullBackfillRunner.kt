package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import kotlin.system.exitProcess

@Component
@ConditionalOnProperty(name = ["feedback.jira400.backfill.windows"])
class JiraFullBackfillRunner(
    private val service: Jira400SyncService,
    @Value("\${feedback.jira400.backfill.windows}") private val configuredWindows: String
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val windows = configuredWindows.split(',').map(String::trim).filter(String::isNotBlank).map { value ->
            val parts = value.split('/', limit = 2)
            if(parts.size != 2) failed("JIRA_BACKFILL_WINDOW_INVALID")
            LocalDateTime.parse(parts[0]) to LocalDateTime.parse(parts[1])
        }
        if(windows.isEmpty()) failed("JIRA_BACKFILL_WINDOWS_REQUIRED")
        windows.forEach { (from, to) ->
            if(!service.enqueue("BACKFILL", from, to, null).accepted) failed("JIRA_BACKFILL_ENQUEUE_REJECTED")
            service.runQueued()
            val status = service.status()
            if(!(status.status == "IDLE" && status.lastErrorCode == null)) failed("JIRA_BACKFILL_FAILED")
        }
        println("JIRA_FULL_BACKFILL_STAGED windows=${windows.size} lastBatch=${service.status().lastBatchId}")
        exitProcess(0)
    }

    private fun failed(reasonCode:String):Nothing =
        throw ServiceException(ErrorCode.JIRA_SYNC_CONTRACT_INVALID,reasonCode)
}
