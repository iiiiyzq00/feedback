package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportQualityBatchPo
import com.feedback.model.po.imports.ImportQualityStoredPo
import com.feedback.model.po.imports.ImportQualityUpdatePo
import org.apache.ibatis.annotations.Mapper

@Mapper interface ImportQualityBatchMapper {
    fun findBase(id: Long): ImportQualityBatchPo?
    fun updateSnapshot(row: ImportQualityUpdatePo): Int
    fun findStored(id: Long): ImportQualityStoredPo?
}
