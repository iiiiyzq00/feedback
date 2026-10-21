package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportQualityLinkPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportQualityLinkMapper {
    fun findActiveBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ImportQualityLinkPo>
    fun findActiveByClusterIds(@Param("clusterIds") clusterIds: List<Long>): List<ImportQualityLinkPo>
}
