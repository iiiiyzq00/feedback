package com.feedback.tools

import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentitySafeTextRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentitySourceRepositoryImpl
import com.feedback.repository.impl.applicationidentity.MyBatisPrimaryProblemFactRepositoryImpl
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.cluster.ClusterShadowGenerationRepository
import com.feedback.manager.applicationidentity.ApplicationIdentityResolver
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatcher
import com.feedback.service.cluster.ClusterShadowGenerationRunner
import com.feedback.service.cluster.ClusterShadowGenerationService
import com.feedback.service.cluster.HistoricalApplicationFactReader
import com.feedback.service.cluster.HistoricalClusterShadowInputReader
import com.feedback.service.sync.BgeM3OnnxEmbeddingProvider
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.WebApplicationType
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.system.exitProcess

/** 只装配隔离候选 generation 所需组件；不扫描或执行其他业务 Runner。 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@MapperScan("com.feedback.mapper")
@Import(
    MyBatisApplicationIdentitySourceRepositoryImpl::class,
    MyBatisApplicationIdentityRepositoryImpl::class,
    MyBatisApplicationIdentitySafeTextRepositoryImpl::class,
    MyBatisPrimaryProblemFactRepositoryImpl::class,
    ApplicationIdentityResolver::class,
    CanonicalApplicationTextMatcher::class,
    ClusterProfileFactRepository::class,
    ClusterMutationRepository::class,
    HistoricalApplicationFactReader::class,
    HistoricalClusterShadowInputReader::class,
    ClusterShadowGenerationRepository::class,
    ClusterShadowGenerationService::class,
    ClusterShadowGenerationRunner::class,
    BgeM3OnnxEmbeddingProvider::class
)
class ClusterShadowGenerationCliConfiguration

fun main(args: Array<String>) {
    val context = SpringApplicationBuilder(ClusterShadowGenerationCliConfiguration::class.java)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.flyway.enabled=false",
            "app.cluster.shadow-generation.enabled=true",
            "spring.main.banner-mode=off"
        )
        .run(*args)
    exitProcess(SpringApplication.exit(context))
}
