package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportRollbackSourcePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportRollbackSourceMapper {
    fun findByBatchId(batchId: Long): List<ImportRollbackSourcePo>
    fun findByIds(@Param("ids") ids: List<Long>): List<ImportRollbackSourcePo>
}
