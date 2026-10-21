package com.feedback.mapper.core

import com.feedback.model.po.core.*
import com.feedback.model.sdk.ChannelSdk
import com.feedback.model.feedback.FeedbackIdentity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface CoreFeedbackIdentityMapper {
    fun findById(@Param("id") id: Long): FeedbackIdentity?
    fun findBySourceId(@Param("sourceId") sourceId: Long): List<FeedbackIdentity>
    fun findExact(@Param("sourceId") sourceId: Long, @Param("identityType") identityType: String,
                  @Param("identityScope") identityScope: String): FeedbackIdentity?
    fun insert(@Param("row") row: FeedbackIdentity): Int
    fun update(@Param("row") row: FeedbackIdentity): Int
}

