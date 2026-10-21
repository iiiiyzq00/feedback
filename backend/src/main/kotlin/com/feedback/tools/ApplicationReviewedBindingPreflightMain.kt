package com.feedback.tools

import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityReviewCandidateRepositoryImpl
import com.feedback.job.applicationidentity.ApplicationReviewedBindingPreflightCommandRunner
import com.feedback.service.impl.applicationidentity.ApplicationReviewedBindingPreflightServiceImpl
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

/** 预检专用只读最小上下文；不装配写 Worker、审计写入、聚类、链接或生成 Bean。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@MapperScan("com.feedback.mapper.applicationidentity")
@Import(
    MyBatisApplicationIdentityRepositoryImpl::class,
    MyBatisApplicationIdentityReviewCandidateRepositoryImpl::class,
    ApplicationReviewedBindingPreflightServiceImpl::class,
    ApplicationReviewedBindingPreflightCommandRunner::class
)
class ApplicationReviewedBindingPreflightCliConfiguration

/** 启动一次性只读预检进程，并以 Spring 退出码结束，避免误启动 HTTP 服务。 */
fun main(args: Array<String>) {
    val context = SpringApplicationBuilder(ApplicationReviewedBindingPreflightCliConfiguration::class.java)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.flyway.enabled=false",
            "spring.datasource.hikari.read-only=true",
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
