package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface SimilarFeedbackContentMapper {
    fun findSafe(@Param("sourceIds") sourceIds: List<Long>, @Param("modules") modules: Set<String>?): List<Long>
}

