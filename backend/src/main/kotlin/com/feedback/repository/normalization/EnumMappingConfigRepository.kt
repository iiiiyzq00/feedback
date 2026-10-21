package com.feedback.repository.normalization

import com.feedback.mapper.normalization.EnumMappingConfigMapper
import org.springframework.stereotype.Repository

@Repository
class EnumMappingConfigRepository(private val mapper: EnumMappingConfigMapper) {
    fun activeJson(key: String): String? = mapper.findActiveJson(key)
}
