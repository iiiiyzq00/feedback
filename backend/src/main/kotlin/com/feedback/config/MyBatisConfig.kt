package com.feedback.config

import org.apache.ibatis.annotations.Mapper
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer
import org.mybatis.spring.annotation.MapperScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Web 应用的唯一全量 Mapper 扫描入口；测试切片不会加载普通业务配置。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = ["com.feedback.mapper"], annotationClass = Mapper::class)
class MyBatisConfig {
    /** 超时策略保持显式开启，本次治理改动就不会悄悄改变生产查询语义。 */
    @Bean
    fun myBatisStatementTimeoutCustomizer(properties: MyBatisObservabilityProperties) = ConfigurationCustomizer { configuration ->
        properties.defaultStatementTimeoutSeconds?.let { configuration.defaultStatementTimeout = it }
    }
}
