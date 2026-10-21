package com.feedback.service.normalization

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class FaultOccurrenceTimeBackfillRunner(
    private val applicationService: FaultOccurrenceTimeBackfillApplicationService
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) { backfill() }

    fun backfill(): Int {
        var cursor = 0L
        var updated = 0
        while (true) {
            val result = applicationService.processBatch(cursor)
            if (result.candidateCount == 0) return updated
            updated += result.updatedCount
            cursor = result.nextSourceId
        }
    }
}
