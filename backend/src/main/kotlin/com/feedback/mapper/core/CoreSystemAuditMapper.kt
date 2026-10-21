package com.feedback.mapper.core

import com.feedback.model.po.core.*
import com.feedback.model.sdk.ChannelSdk
import com.feedback.model.feedback.FeedbackIdentity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface CoreSystemAuditMapper {
    fun insert(@Param("row") row: CoreSystemAuditPo): Int
    fun findAll(): List<CoreSystemAuditPo>
}

