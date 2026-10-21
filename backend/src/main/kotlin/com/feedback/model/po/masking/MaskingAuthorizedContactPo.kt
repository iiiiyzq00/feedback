package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MaskingAuthorizedContactPo(
    var id: Long = 0, var identityType: String = "", var contactCiphertext: ByteArray = ByteArray(0),
    var contactNonce: ByteArray = ByteArray(0), var contactTag: ByteArray = ByteArray(0)
)

