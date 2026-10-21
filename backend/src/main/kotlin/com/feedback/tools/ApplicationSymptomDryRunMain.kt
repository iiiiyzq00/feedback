package com.feedback.tools

import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentitySafeTextRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentitySourceRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisPrimaryProblemFactRepositoryImpl
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.manager.applicationidentity.ApplicationIdentityResolver
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatcher
import com.feedback.service.cluster.ApplicationSymptomDryRunCommandRunner
import com.feedback.service.cluster.ApplicationSymptomDryRunInputReader
import com.feedback.service.cluster.ApplicationSymptomDryRunReportValidator
import com.feedback.service.cluster.ApplicationSymptomDryRunService
import com.feedback.service.cluster.HistoricalApplicationFactReader
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

/** 收窄的只读应用上下文；不导入任何业务写入器、调度器、Cluster/Link 或代次相关 Bean。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@MapperScan("com.feedback.mapper")
@Import(
    MyBatisApplicationIdentitySourceRepositoryImpl::class,
    MyBatisApplicationIdentityRepositoryImpl::class,
    MyBatisPrimaryProblemFactRepositoryImpl::class,
    MyBatisApplicationIdentitySafeTextRepositoryImpl::class,
    ClusterProfileFactRepository::class,
    ApplicationIdentityResolver::class,
    CanonicalApplicationTextMatcher::class,
    HistoricalApplicationFactReader::class,
    ApplicationSymptomDryRunInputReader::class,
    ApplicationSymptomDryRunService::class,
    ApplicationSymptomDryRunReportValidator::class,
    ApplicationSymptomDryRunCommandRunner::class
)
class ApplicationSymptomDryRunCliConfiguration

fun main(args: Array<String>) {
    val context = SpringApplicationBuilder(ApplicationSymptomDryRunCliConfiguration::class.java)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.flyway.enabled=false",
            "app.cluster.application-symptom-dry-run-enabled=true",
            "app.cluster.application-symptom-dry-run.command-enabled=true",
            "spring.main.banner-mode=off",
            "logging.level.root=ERROR",
            "logging.level.com.feedback.mapper=OFF",
            "logging.level.org.mybatis=OFF",
            "logging.level.org.springframework.jdbc=OFF",
            "mybatis.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
        )
        .run(*args)
    exitProcess(SpringApplication.exit(context))
}
