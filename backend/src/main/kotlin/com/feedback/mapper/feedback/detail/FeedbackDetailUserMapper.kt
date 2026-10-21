package com.feedback.mapper.feedback.detail

import com.feedback.model.po.feedback.detail.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Mapper interface FeedbackDetailUserMapper { fun findByIds(@Param("ids") ids: Set<Long>): List<FeedbackDetailUserPo> }
/** 字段转换历史读取及详情/外链访问审计写入边界。 */

