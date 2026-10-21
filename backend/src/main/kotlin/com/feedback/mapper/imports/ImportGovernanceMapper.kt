package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportGovernanceBatchPo
import com.feedback.model.po.imports.ImportSupersedePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ImportGovernanceMapper {
    fun findApiBatches(): List<ImportGovernanceBatchPo>
    fun supersede(@Param("rows") rows: List<ImportSupersedePo>, @Param("reason") reason: String,
                  @Param("actor") actor: Long, @Param("now") now: LocalDateTime): Int
    fun archiveNoChange(@Param("reason") reason: String, @Param("actor") actor: Long, @Param("now") now: LocalDateTime): Int
    fun countActive(): Int
}
