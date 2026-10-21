package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ClusterReportContentMapper { fun safeByIds(@Param("ids") ids: List<Long>): List<ClusterReportContentPo> }

