package com.feedback.config

import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MyBatisRuntimeGovernanceTest {
    @Test
    fun `statement timeout remains unchanged when rollout property is absent`() {
        val configuration = Configuration().apply { defaultStatementTimeout = 37 }

        MyBatisConfig().myBatisStatementTimeoutCustomizer(MyBatisObservabilityProperties()).customize(configuration)

        assertEquals(37, configuration.defaultStatementTimeout)
    }

    @Test
    fun `explicit rollout property configures the MyBatis default timeout`() {
        val configuration = Configuration()
        val properties = MyBatisObservabilityProperties().apply { defaultStatementTimeoutSeconds = 10 }

        MyBatisConfig().myBatisStatementTimeoutCustomizer(properties).customize(configuration)

        assertEquals(10, configuration.defaultStatementTimeout)
    }
}
