package com.feedback.mapper.knowledge

import com.feedback.model.po.knowledge.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface KnowledgeSearchMapper {
    fun search(@Param("issue") issue: String, @Param("module") module: String, @Param("limit") limit: Int): List<KnowledgeSearchPo>
}

