package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper

@Mapper
interface SeverityPolicyMapper {
    fun findActiveJson(): String?
}

