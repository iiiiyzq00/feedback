package com.feedback.model.query.problem.analysis

import java.time.LocalDateTime

data class AnalysisCriteria(
    val allowedChannels: List<String>, val scopeModules: List<String>?, val scopeRegions: List<String>?,
    val startTime: LocalDateTime?, val endTime: LocalDateTime?
)
