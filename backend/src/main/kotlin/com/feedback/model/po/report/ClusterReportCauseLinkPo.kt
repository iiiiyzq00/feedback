package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportCauseLinkPo(var clusterId: Long = 0, var rcaId: Long? = null, var incidentId: Long? = null)

