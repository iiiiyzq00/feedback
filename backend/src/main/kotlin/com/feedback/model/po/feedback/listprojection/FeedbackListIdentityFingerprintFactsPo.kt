package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListIdentityFingerprintFactsPo(
    var sourceId: Long = 0,
    var identityScope: String = "",
    var identityType: String = "",
    var fingerprintKeyId: String = "",
    var fingerprint: ByteArray = byteArrayOf()
)

