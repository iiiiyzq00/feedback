package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportRollbackClusterStatsPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportRollbackClusterMapper {
    fun updateStatisticsBatch(@Param("rows") rows: List<ImportRollbackClusterStatsPo>): Int
    fun archiveEmpty(@Param("clusterIds") clusterIds: List<Long>): Int
}
