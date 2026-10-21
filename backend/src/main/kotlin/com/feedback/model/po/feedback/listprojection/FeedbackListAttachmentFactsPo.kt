package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListAttachmentFactsPo(var sourceId: Long = 0, var attachmentCount: Int = 0, var screenshotCount: Int = 0, var logCount: Int = 0, var videoCount: Int = 0, var updatedAt: LocalDateTime? = null)

