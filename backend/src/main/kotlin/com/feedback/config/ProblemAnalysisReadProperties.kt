package com.feedback.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** 反馈问题分析安全投影读开关；默认关闭，关闭或校验失败时不得回退旧 SQL。 */
@Component
@ConfigurationProperties(prefix = "app.phase-one.problem-analysis-projection")
class ProblemAnalysisReadProperties {
    var readEnabled: Boolean = false
}
