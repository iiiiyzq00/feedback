package com.feedback.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.repository.config.SystemConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 读取已提交的运行时配置，不缓存、不吞掉数据库故障。
 *
 * 配置缺失或确认属于历史坏值时使用调用方显式默认值；数据库异常继续向上传播，使安全和限流调用失败关闭。
 */
@Service
class RuntimeConfigService(private val repository: SystemConfigRepository, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun int(key: String, defaultValue: Int): Int = value(key)?.let { raw ->
        parse(key, raw) { node -> node.takeIf(JsonNode::isIntegralNumber)?.intValue() }
    } ?: defaultValue

    fun string(key: String, defaultValue: String): String = value(key)?.let { raw ->
        parse(key, raw) { node -> node.takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank) }
    } ?: defaultValue

    fun decimal(key: String, defaultValue: BigDecimal): BigDecimal = value(key)?.let { raw ->
        parse(key, raw) { node -> node.takeIf(JsonNode::isNumber)?.decimalValue() }
    } ?: defaultValue

    fun dictionary(key: String): RuntimeStringDictionary = value(key)?.let { raw ->
        parse(key, raw) { node ->
            node.takeIf(JsonNode::isObject)?.fields()?.asSequence()?.associate { it.key to it.value.asText() }
                ?.let(RuntimeStringDictionary::of)
        }
    } ?: RuntimeStringDictionary.EMPTY

    private fun value(key: String): String? {
        val value = repository.activeRuntime(key)?.configValue
        if (value == null) log.warn("Runtime configuration is missing key={}", key)
        return value
    }

    private fun <T> parse(key: String, raw: String, parser: (JsonNode) -> T?): T? = runCatching {
        parser(mapper.readTree(raw))
    }.getOrNull().also { parsed ->
        if (parsed == null) log.warn("Runtime configuration is malformed key={}", key)
    }
}
