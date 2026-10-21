package com.feedback.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** 反馈列表安全投影读开关；默认关闭，关闭时不得回退任何旧 JDBC/JOIN 路径。 */
@Component
@ConfigurationProperties(prefix = "app.phase-one.list-projection")
class FeedbackListReadProperties {
    var readEnabled: Boolean = false
}
