package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityContentPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface UnifiedQualityContentMapper {
    fun findActiveBySourceIds(@Param("sourceIds") sourceIds:List<Long>):List<UnifiedQualityContentPo>
    fun findUnsafeBySourceIds(@Param("sourceIds") sourceIds:List<Long>):List<UnifiedQualityContentPo>
}
