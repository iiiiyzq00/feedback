package com.feedback.config

import com.feedback.security.station.StationUcAuthenticator
import com.feedback.repository.remote.station.StationUserDirectory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.boot.autoconfigure.AutoConfiguration

/** Station 外部端口装配配置；生产环境必须显式提供 UC 认证与用户目录实现。 */
@AutoConfiguration
class StationExternalPortConfiguration {

/** 装配 Station UC 认证端口；生产未提供实现时返回明确失败关闭适配器。 */
    @Bean
    @ConditionalOnMissingBean(StationUcAuthenticator::class)
    fun stationUcAuthenticator(): StationUcAuthenticator =
        StationUcAuthenticator { _, _, _ ->
            configFailed("STATION_UC_AUTHENTICATOR_NOT_CONFIGURED")
        }

/** 装配 Station 用户目录端口；生产未提供实现时拒绝联系方式查询。 */
    @Bean
    @ConditionalOnMissingBean(StationUserDirectory::class)
    fun stationUserDirectory(): StationUserDirectory =
        StationUserDirectory { _, _ ->
            configFailed("STATION_UC_USER_DIRECTORY_NOT_CONFIGURED")
        }
}
