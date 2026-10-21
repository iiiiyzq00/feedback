package com.feedback.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.context.annotation.Bean
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import java.time.Clock

@Configuration
@EnableAsync
@EnableCaching
class AppConfig
{
    @Bean
    fun utcClock(): Clock = Clock.systemUTC()

    @Bean
    fun cacheManager(): CacheManager = CaffeineCacheManager("importBatches", "importBatchDetails").apply {
        setCaffeine(Caffeine.newBuilder().maximumSize(500).expireAfterWrite(20, TimeUnit.SECONDS).recordStats())
    }
}
