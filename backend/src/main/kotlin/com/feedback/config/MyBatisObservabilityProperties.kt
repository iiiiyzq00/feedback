package com.feedback.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@Validated
@ConfigurationProperties(prefix = "app.mybatis.observability")
class MyBatisObservabilityProperties {
    var enabled: Boolean = true

    @field:Min(1)
    @field:Max(600_000)
    var slowStatementThresholdMs: Long = 500

    /** 仅显式开启时生效：不配置则保持现有 MyBatis/JDBC 超时行为。 */
    @field:Min(1)
    @field:Max(300)
    var defaultStatementTimeoutSeconds: Int? = null
}
