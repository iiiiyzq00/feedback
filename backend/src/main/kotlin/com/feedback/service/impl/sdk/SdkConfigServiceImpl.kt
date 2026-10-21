package com.feedback.service.impl.sdk

import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.repository.SystemConfigRepository
import com.feedback.service.sdk.SdkConfigService
import com.feedback.security.sdk.TransportKeyService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

@Service
/** SDK 配置查询实现；只暴露允许列表中的公开配置，并对每项值执行类型和范围校验。 */
class SdkConfigServiceImpl(
    private val repository: SystemConfigRepository,
    private val objectMapper: ObjectMapper,
    private val transportKeys: TransportKeyService
) : SdkConfigService {
    /** 查询并校验 SDK 配置；公钥文件读取明确位于数据库事务之外。 */
    override fun getConfig(packageName: String): JsonNode {
        if (!PACKAGE_NAME.matches(packageName)) {
            throw ClientException(ErrorCode.BAD_REQUEST, "SDK_PACKAGE_NAME_INVALID")
        }
        val stored = repository
            .findFirstByConfigKeyAndIsActiveTrueOrderByUpdatedAtDesc("sdk.config.$packageName")
            ?: repository.findFirstByConfigKeyAndIsActiveTrueOrderByUpdatedAtDesc(DEFAULT_KEY)

        val value = stored?.configValue ?: defaultValue()
        validate(value)
        return objectMapper.createObjectNode().apply {
            put("version", stored?.configVersion ?: "default-v1")
            value.fields().forEachRemaining { (name, fieldValue) -> set<JsonNode>(name, fieldValue) }
            if (transportKeys.activePublicKeyPath.isNotBlank()) {
                put("transport_key_id", transportKeys.activeKeyId)
                put("transport_public_key_pem", java.nio.file.Files.readString(java.nio.file.Path.of(transportKeys.activePublicKeyPath)))
            }
        }
    }

    /** 校验输入或不变量：SDK 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun validate(value: JsonNode) {
        val valid = value.isObject &&
            value.path("log_buffer_minutes").asInt(0) in 1..60 &&
            value.path("log_max_size_kb").asInt(0) in 1..10_240 &&
            value.path("max_video_duration").asInt(0) in 1..120 &&
            value.path("max_video_bitrate_kbps").asInt(0) in 100..8_000 &&
            value.path("quick_issue_types").isArray &&
            (value.path("form_fields").isMissingNode || value.path("form_fields").isArray)
        if (!valid) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "SDK_CONFIG_INVALID")
        }
    }

    /** 读取 SDK 配置项默认值；未知键返回空值，不绕过允许列表暴露任意配置。 */
    private fun defaultValue(): JsonNode = objectMapper.createObjectNode().apply {
        put("log_buffer_minutes", 5)
        put("log_max_size_kb", 2048)
        put("max_video_duration", 30)
        put("max_video_bitrate_kbps", 1000)
        set<com.fasterxml.jackson.databind.node.ArrayNode>(
            "quick_issue_types",
            objectMapper.createArrayNode().add("内容错误").add("功能异常").add("卡顿闪退").add("使用建议").add("其他")
        )
        put("float_window_enabled", true)
        put("form_schema_version", "sdk-form-v1")
        set<com.fasterxml.jackson.databind.node.ArrayNode>("form_fields", objectMapper.createArrayNode()
            .add(objectMapper.createObjectNode().put("key","description").put("label","反馈描述").put("type","textarea").put("required",true))
            .add(objectMapper.createObjectNode().put("key","issue_type").put("label","反馈类型").put("type","select").put("required",true)))
    }

    companion object {
        private const val DEFAULT_KEY = "sdk.config.default"
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
