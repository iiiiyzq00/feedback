package com.feedback.repository.report

import com.feedback.mapper.report.ReportFreshnessMapper
import org.springframework.stereotype.Repository

@Repository
class ReportFreshnessRepository(private val mapper: ReportFreshnessMapper) {
    fun latest(channels: Set<String>) = if (channels.isEmpty()) emptyMap() else
        mapper.latestByChannels(channels).associate { it.sourceChannel to it.latest }
}
