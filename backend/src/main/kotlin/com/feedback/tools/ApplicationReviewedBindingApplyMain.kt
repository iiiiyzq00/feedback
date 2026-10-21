package com.feedback.tools

import com.feedback.audit.BusinessAuditService
import com.feedback.repository.audit.BusinessAuditRepository
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityReviewCandidateRepositoryImpl
import com.feedback.manager.applicationidentity.ApplicationIdentityReviewedBindingWorker
import com.feedback.job.applicationidentity.ApplicationReviewedBindingApplyCommandRunner
import com.feedback.service.impl.applicationidentity.ApplicationReviewedBindingBatchServiceImpl
import com.feedback.service.impl.applicationidentity.ApplicationReviewedBindingPreflightServiceImpl
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

/** APPLY 专用最小上下文；只装配审核绑定仓储、Worker 与防篡改审计链。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@MapperScan("com.feedback.mapper")
@Import(
    MyBatisApplicationIdentityRepositoryImpl::class,
    MyBatisApplicationIdentityReviewCandidateRepositoryImpl::class,
    BusinessAuditRepository::class,
    BusinessAuditService::class,
    ApplicationIdentityReviewedBindingWorker::class,
    ApplicationReviewedBindingPreflightServiceImpl::class,
    ApplicationReviewedBindingBatchServiceImpl::class,
    ApplicationReviewedBindingApplyCommandRunner::class
)
class ApplicationReviewedBindingApplyCliConfiguration

/** 启动一次性 APPLY 进程，并以 Spring 退出码结束，避免误启动 HTTP 服务。 */
fun main(args: Array<String>) {
    val context = SpringApplicationBuilder(ApplicationReviewedBindingApplyCliConfiguration::class.java)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.flyway.enabled=false",
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
