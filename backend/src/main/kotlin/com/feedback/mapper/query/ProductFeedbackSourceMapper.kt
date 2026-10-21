package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ProductFeedbackSourceMapper { fun findById(@Param("id") id:Long):ProductFeedbackSourcePo? }

