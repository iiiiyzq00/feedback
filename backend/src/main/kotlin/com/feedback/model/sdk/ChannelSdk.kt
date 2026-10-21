package com.feedback.model.sdk

import java.time.LocalDateTime
import java.math.BigDecimal

/** SDK 渠道事实；持久化只允许通过 MyBatis Repository 边界。 */
data class ChannelSdk(
    val sourceId: Long, val clientFeedbackId: String, val appId: String,
    val packageName: String? = null, val osVersion: String? = null, val networkType: String? = null,
    val deviceModel: String? = null, val screenOrientation: String? = null, val appSessionId: String? = null,
    val triggerType: String? = null, val inputMethods: String? = null, val batteryLevel: BigDecimal? = null,
    val storageAvailableMb: Int? = null, val integrationMode: String = "EMBEDDED_SDK",
    val contextSource: String = "RUNTIME", val appInternalLogs: String? = null, val crashStack: String? = null,
    val accountContext: String? = null, val courseId: String? = null, val questionId: String? = null,
    val orderId: String? = null, val pageRoute: String? = null, val businessId: String? = null,
    val apiRequestSummary: String? = null, val stateMachine: String? = null, val customEvents: String? = null,
    val privateFileManifest: String? = null, val diagnosticCiphertext: ByteArray? = null,
    val diagnosticNonce: ByteArray? = null, val diagnosticTag: ByteArray? = null,
    val diagnosticKeyId: String? = null, val diagnosticAlgorithm: String? = null,
    val diagnosticTypes: String? = null, val createdAt: LocalDateTime = LocalDateTime.now()
)
