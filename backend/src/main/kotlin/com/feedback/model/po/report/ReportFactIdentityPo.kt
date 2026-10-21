package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportFactIdentityPo(var sourceId: Long = 0, var fingerprint: ByteArray = byteArrayOf())

