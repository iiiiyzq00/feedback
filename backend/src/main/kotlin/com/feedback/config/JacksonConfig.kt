package com.feedback.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.ser.PropertyWriter
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.feedback.security.FieldScopedResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {
    @Bean
    fun objectMapper(): ObjectMapper {
        val kotlinModule = KotlinModule.Builder()
            .configure(KotlinFeature.NullToEmptyCollection, true)
            .configure(KotlinFeature.NullToEmptyMap, true)
            .build()

        return ObjectMapper()
            .registerModule(kotlinModule)
            .registerModule(JavaTimeModule())
            .setFilterProvider(
                SimpleFilterProvider()
                    .addFilter("feedbackDataFieldPolicy", DataFieldPropertyFilter())
                    .setFailOnUnknownId(false)
            )
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}

/** 使用响应在请求开始时携带的策略做纯内存字段过滤；序列化阶段不得访问数据库。 */
private class DataFieldPropertyFilter : SimpleBeanPropertyFilter() {
    override fun serializeAsField(
        pojo: Any,
        generator: com.fasterxml.jackson.core.JsonGenerator,
        provider: com.fasterxml.jackson.databind.SerializerProvider,
        writer: PropertyWriter
    ) {
        val scoped = pojo as? FieldScopedResponse
        if (scoped == null || scoped.fieldPolicy.permits(writer.name)) {
            writer.serializeAsField(pojo, generator, provider)
        } else if (!generator.canOmitFields()) {
            writer.serializeAsOmittedField(pojo, generator, provider)
        }
    }
}
