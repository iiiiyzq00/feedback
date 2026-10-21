package com.feedback.model.po.feedback.diagnostics

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class FeedbackDiagnosticIdentityPo(
    var id: Long = 0, var sourceId: Long = 0, var identityType: String = "", var contactCiphertext: ByteArray? = null,
    var contactNonce: ByteArray? = null, var contactTag: ByteArray? = null,
    var contactKeyId: String? = null, var contactAlgorithm: String? = null
)

/** 站内反馈记录的来源用户引用与认证快照；只有 STATION_FEEDBACK 渠道读取。 */

