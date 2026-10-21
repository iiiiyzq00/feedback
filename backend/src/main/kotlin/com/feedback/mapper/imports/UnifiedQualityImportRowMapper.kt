package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityImportRowPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface UnifiedQualityImportRowMapper {
    fun findObserved(@Param("batchIds") batchIds:List<Long>):List<UnifiedQualityImportRowPo>
    fun findCurrentCandidates(@Param("batchIds") batchIds:List<Long>):List<UnifiedQualityImportRowPo>
    fun findByBusinessHashes(@Param("hashes") hashes:List<ByteArray>):List<UnifiedQualityImportRowPo>
}
