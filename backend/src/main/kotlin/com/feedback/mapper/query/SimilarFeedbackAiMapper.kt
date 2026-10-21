package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface SimilarFeedbackAiMapper { fun findVectorSynced(@Param("sourceIds") sourceIds: List<Long>): List<Long> }

