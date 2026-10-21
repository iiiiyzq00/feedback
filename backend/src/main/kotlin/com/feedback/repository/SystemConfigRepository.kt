package com.feedback.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.config.SystemConfig
import com.feedback.mapper.config.SystemConfigMapper
import org.springframework.stereotype.Repository

/** SDK 公共配置读取兼容边界；底层复用版本化系统配置单表 Mapper。 */
@Repository
class SystemConfigRepository(private val mapper: SystemConfigMapper, private val json: ObjectMapper) {
    fun findFirstByConfigKeyAndIsActiveTrueOrderByUpdatedAtDesc(configKey: String): SystemConfig? =
        mapper.findActiveRuntime(configKey, 1).singleOrNull()?.let { row ->
            SystemConfig(row.id, row.configKey, row.configVersion, json.readTree(row.configValue),
                configDesc = row.configDesc, isActive = row.isActive, createdBy = row.createdBy,
                createdAt = row.createdAt, updatedAt = row.updatedAt)
        }
}
