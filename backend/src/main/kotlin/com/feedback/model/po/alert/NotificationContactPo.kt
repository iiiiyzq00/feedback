package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class NotificationContactPo(var ciphertext:ByteArray=byteArrayOf(),var tag:ByteArray=byteArrayOf(),var nonce:ByteArray=byteArrayOf())

