package com.feedback.model.feedback

import java.time.LocalDateTime

/** 反馈身份安全事实；持久化只允许通过 MyBatis Repository 边界。 */
data class FeedbackIdentity(
    var id: Long = 0,
    val sourceId: Long, val identityType: String, val identityScope: String,
    val fingerprint: ByteArray, val fingerprintKeyId: String,
    val contactCiphertext: ByteArray? = null, val contactNonce: ByteArray? = null,
    val contactTag: ByteArray? = null, val contactKeyId: String? = null,
    val contactAlgorithm: String? = null, val contactAuthorizationBasis: String? = null,
    val consentedAt: LocalDateTime? = null, val expiresAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
