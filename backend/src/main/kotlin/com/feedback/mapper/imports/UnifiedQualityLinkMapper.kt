package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityLinkPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface UnifiedQualityLinkMapper {
    fun findActiveBySourceIds(@Param("sourceIds") sourceIds:List<Long>):List<UnifiedQualityLinkPo>
    fun findActiveByClusterIds(@Param("clusterIds") clusterIds:List<Long>):List<UnifiedQualityLinkPo>
}
