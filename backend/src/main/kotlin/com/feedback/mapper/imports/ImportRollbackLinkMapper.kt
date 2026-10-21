package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportRollbackLinkPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportRollbackLinkMapper {
    fun findActiveBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ImportRollbackLinkPo>
    fun deactivateBySourceIds(@Param("sourceIds") sourceIds: List<Long>): Int
    fun findActiveMainByClusterIds(@Param("clusterIds") clusterIds: List<Long>): List<ImportRollbackLinkPo>
}
