package com.feedback.config

import com.feedback.common.exception.ServiceException
import com.feedback.security.station.StationUcAuthenticator
import com.feedback.repository.remote.station.StationUserDirectory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class StationExternalPortConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StationExternalPortConfiguration::class.java))

    @Test
    fun `registers fail-closed fallback ports when target implementations are absent`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(StationUcAuthenticator::class.java)
            assertThat(context).hasSingleBean(StationUserDirectory::class.java)
            assertThatThrownBy {
                context.getBean(StationUcAuthenticator::class.java)
                    .authenticate("token", "product", "127.0.0.1")
            }.isInstanceOf(ServiceException::class.java)
            assertThatThrownBy {
                context.getBean(StationUserDirectory::class.java)
                    .findPhone("user", "product")
            }.isInstanceOf(ServiceException::class.java)
        }
    }

    @Test
    fun `keeps target implementations when they are provided`() {
        contextRunner
            .withUserConfiguration(TargetPortConfiguration::class.java)
            .run { context ->
                assertThat(context).hasSingleBean(StationUcAuthenticator::class.java)
                assertThat(context).hasSingleBean(StationUserDirectory::class.java)
                assertThat(context.getBean(StationUcAuthenticator::class.java)
                    .authenticate("token", "product", "127.0.0.1")).isEqualTo("target-user")
                assertThat(context.getBean(StationUserDirectory::class.java)
                    .findPhone("user", "product")).isEqualTo("synthetic-phone")
            }
    }

    @Configuration(proxyBeanMethods = false)
    class TargetPortConfiguration {
        @Bean
        fun targetStationUcAuthenticator(): StationUcAuthenticator =
            StationUcAuthenticator { _, _, _ -> "target-user" }

        @Bean
        fun targetStationUserDirectory(): StationUserDirectory =
            StationUserDirectory { _, _ -> "synthetic-phone" }
    }
}
