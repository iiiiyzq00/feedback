package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface OutboundPrivacyAuditMapper {
    fun insertReject(@Param("target") target: String, @Param("targetId") targetId: String,
                     @Param("version") version: String, @Param("typesJson") typesJson: String,
                     @Param("fieldCount") fieldCount: Int): Int
    fun insertOverride(@Param("sourceId") sourceId: Long, @Param("version") version: String): Int
}

