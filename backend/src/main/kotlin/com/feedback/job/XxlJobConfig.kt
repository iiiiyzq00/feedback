package com.feedback.job

import com.xxl.job.core.executor.XxlJobExecutor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * XXL-Job 执行器注册，与 study-station-server 使用的执行器模型一致。
 * 每个后端节点使用同一个 appName，因此在 XXL-Job Admin 中注册为
 * 同一个执行器集群。
 */
@Configuration
@ConditionalOnProperty(name = ["xxl.job.enabled"], havingValue = "true")
class XxlJobConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${xxl.job.admin.addresses}")
    private lateinit var adminAddresses: String

    @Value("\${xxl.job.executor.appname}")
    private lateinit var appName: String

    @Value("\${xxl.job.executor.port:-1}")
    private var port: Int = -1

    @Value("\${xxl.job.executor.logpath:logs/xxl-job/jobhandler}")
    private lateinit var logPath: String

    @Bean(initMethod = "start", destroyMethod = "destroy")
    fun xxlJobExecutor(): XxlJobExecutor {
        log.info("Initializing XXL-Job executor appName={}", appName)
        return XxlJobExecutor().apply {
            setAdminAddresses(adminAddresses)
            setAppName(appName)
            setPort(port)
            setLogPath(logPath)
        }
    }
}
