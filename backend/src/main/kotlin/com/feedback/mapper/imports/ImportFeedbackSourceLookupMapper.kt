package com.feedback.mapper.imports

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ImportFeedbackSourceLookupMapper {
    fun findJiraIdsByKeys(@Param("keys") keys: Collection<String>): List<Long>
}
