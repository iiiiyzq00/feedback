package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MaskingSdkRawPo(
    var encryptedDataKey: ByteArray = ByteArray(0), var payloadCiphertext: ByteArray = ByteArray(0),
    var payloadNonce: ByteArray = ByteArray(0), var payloadTag: ByteArray = ByteArray(0)
)

