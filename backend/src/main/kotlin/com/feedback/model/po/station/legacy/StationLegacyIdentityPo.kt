package com.feedback.model.po.station.legacy

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** StationLegacyIdentityPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationLegacyIdentityPo(
    var sourceId: Long = 0,
    var identityType: String = "",
    var identityScope: String = "PERSON",
    var fingerprint: ByteArray = ByteArray(0),
    var fingerprintKeyId: String = "",
    var contactCiphertext: ByteArray? = null,
    var contactNonce: ByteArray? = null,
    var contactTag: ByteArray? = null,
    var contactKeyId: String? = null,
    var contactAlgorithm: String? = null,
    var contactAuthorizationBasis: String = "CHANNEL_GOVERNANCE",
    var consentedAt: LocalDateTime = LocalDateTime.now(),
    var expiresAt: LocalDateTime = LocalDateTime.of(2099, 12, 31, 23, 59, 59)
)
