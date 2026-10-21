package com.feedback.job

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** 只在本地调度关闭时才启用公司标准的 XXL 处理器。 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(name = ["xxl.job.enabled"], havingValue = "true")
annotation class EnabledXxlJob
