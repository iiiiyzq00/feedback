package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface InAppIncidentLinkMapper {
    fun byAlerts(@Param("ids") ids: List<Long>): List<InAppIncidentLinkPo>
    fun counts(@Param("ids") ids: List<Long>): List<InAppIncidentCountPo>
}

