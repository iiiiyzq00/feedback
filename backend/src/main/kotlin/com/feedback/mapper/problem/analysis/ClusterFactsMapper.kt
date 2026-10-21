package com.feedback.mapper.problem.analysis

import com.feedback.model.po.problem.analysis.ClusterFactsPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 簇主表批量读取边界；调用方将其与投影统计在代码中组装。 */
@Mapper
interface ClusterFactsMapper { fun findByIds(@Param("clusterIds") clusterIds: List<Long>): List<ClusterFactsPo> }
