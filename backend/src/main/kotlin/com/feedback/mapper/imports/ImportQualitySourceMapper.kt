package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportQualitySourcePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportQualitySourceMapper {
    fun findByBatch(batchId: Long): List<ImportQualitySourcePo>
    fun findByIds(@Param("ids") ids: List<Long>): List<ImportQualitySourcePo>
}
