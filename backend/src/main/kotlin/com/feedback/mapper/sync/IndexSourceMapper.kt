package com.feedback.mapper.sync

import com.feedback.model.po.sync.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IndexSourceMapper { fun findById(@Param("id") id: Long): IndexSourcePo? }

