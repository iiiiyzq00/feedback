package com.feedback.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

/** SDK 认证配置边界；启动时校验应用密钥目录完整性，配置值不得写入日志。 */
@Component
@ConfigurationProperties(prefix = "app.sdk")
class SdkAuthProperties {
    var appSecrets: Map<String, String> = emptyMap()

/** 校验配置或输入的完整性与安全边界；发现缺失、空白或非法格式时立即失败关闭。 */
    @PostConstruct
    fun validate() {
        if(appSecrets.isEmpty()) configFailed("SDK_APP_SECRET_CONFIGURATION_EMPTY")
        appSecrets.forEach { (appKey, secret) ->
            if(appKey.isBlank()) configFailed("SDK_APP_KEY_INVALID")
            if(secret.length < 16) configFailed("SDK_APP_SECRET_TOO_SHORT")
            if(secret == appKey) configFailed("SDK_APP_SECRET_EQUALS_PUBLIC_KEY")
        }
    }
}
