package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface InAppRuleMapper {
    fun byIds(@Param("ids") ids: List<Long>): List<InAppRulePo>
}

