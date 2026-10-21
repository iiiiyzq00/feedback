package com.feedback.config

import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients
import org.springframework.data.elasticsearch.config.EnableElasticsearchAuditing

/**
 * Elasticsearch 配置
 * 开发/测试环境如 ES 不可用则跳过自动配置
 */
@Configuration
@Profile("!test")
class ElasticsearchConfig
