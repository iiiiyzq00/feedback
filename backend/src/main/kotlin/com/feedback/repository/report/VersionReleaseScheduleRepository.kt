package com.feedback.repository.report
import com.feedback.mapper.report.VersionReleaseScheduleMapper
import org.springframework.stereotype.Repository
@Repository class VersionReleaseScheduleRepository(private val mapper:VersionReleaseScheduleMapper){fun due()=mapper.due()}
