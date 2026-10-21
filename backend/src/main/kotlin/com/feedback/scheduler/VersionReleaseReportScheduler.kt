package com.feedback.scheduler

import com.feedback.security.WebPrincipal
import com.feedback.repository.report.VersionReleaseScheduleRepository
import com.feedback.service.report.GenerateReportRequest
import com.feedback.service.report.ReportGenerationOrchestrator
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class VersionReleaseReportScheduler(private val repository:VersionReleaseScheduleRepository,private val generation:ReportGenerationOrchestrator) {
 fun due()=asSystem { repository.due().forEach{row->
  val parts=row.configKey.split(':',limit=3);val packageName=row.packageName?.takeIf{it!="null"}?:parts.getOrNull(1)?:return@forEach;val version=row.version?.takeIf{it!="null"}?:parts.getOrNull(2)?:return@forEach;val released=row.releasedAt;generation.generate(GenerateReportRequest("VERSION_RELEASE",released,released.plusDays(2).coerceAtMost(LocalDate.now()),packageName,version,released),null,"SCHEDULED")
 }}
 private fun <T> asSystem(action:()->T):T{val previous=SecurityContextHolder.getContext();val context=SecurityContextHolder.createEmptyContext();val principal=WebPrincipal(0,"system-version-report","","System Version Report",setOf("ADMIN"),emptySet());context.authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.authorities);SecurityContextHolder.setContext(context);return try{action()}finally{SecurityContextHolder.setContext(previous)}}
}
