package com.feedback.mapper.normalization

import com.feedback.model.po.normalization.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface EnumMappingConfigMapper {
    fun findActiveJson(@Param("configKey") configKey: String): String?
}

