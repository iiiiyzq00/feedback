package com.feedback.model.po.sdk

import java.time.LocalDateTime

/** SdkAuthorizedContactPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class SdkAuthorizedContactPo(
    var ingestId: Long = 0,
    var sourceId: Long? = null,
    var contactType: String = "",
    var fingerprint: ByteArray = ByteArray(0),
    var fingerprintKeyId: String = "",
    var authorized: Boolean = false,
    var contactCiphertext: ByteArray? = null,
    var contactNonce: ByteArray? = null,
    var contactTag: ByteArray? = null,
    var contactKeyId: String? = null,
    var contactAlgorithm: String? = null,
    var authorizationBasis: String? = null,
    var authorizedAt: LocalDateTime? = null,
    var expiresAt: LocalDateTime = LocalDateTime.now()
)
