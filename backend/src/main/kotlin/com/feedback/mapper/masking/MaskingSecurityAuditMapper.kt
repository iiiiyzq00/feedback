package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface MaskingSecurityAuditMapper {
    fun insert(@Param("userId") userId: Long, @Param("operation") operation: String,
               @Param("outcome") outcome: String, @Param("target") target: String,
               @Param("detailJson") detailJson: String, @Param("ip") ip: String?): Int
}

