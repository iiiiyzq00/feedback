package com.feedback.model.po.sdk

import java.math.BigDecimal

/** SdkChannelPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class SdkChannelPo(
    var sourceId: Long = 0,
    var clientFeedbackId: String = "",
    var appId: String = "",
    var packageName: String? = null,
    var osVersion: String? = null,
    var networkType: String? = null,
    var deviceModel: String? = null,
    var screenOrientation: String? = null,
    var appSessionId: String? = null,
    var triggerType: String? = null,
    var inputMethods: String? = null,
    var batteryLevel: BigDecimal? = null,
    var storageAvailableMb: Int? = null,
    var integrationMode: String = "EMBEDDED_SDK",
    var contextSource: String = "RUNTIME",
    var appInternalLogs: String? = null,
    var crashStack: String? = null,
    var accountContext: String? = null,
    var courseId: String? = null,
    var questionId: String? = null,
    var orderId: String? = null,
    var pageRoute: String? = null,
    var businessId: String? = null,
    var apiRequestSummary: String? = null,
    var stateMachine: String? = null,
    var customEvents: String? = null,
    var privateFileManifest: String? = null,
    var diagnosticCiphertext: ByteArray? = null,
    var diagnosticNonce: ByteArray? = null,
    var diagnosticTag: ByteArray? = null,
    var diagnosticKeyId: String? = null,
    var diagnosticAlgorithm: String? = null,
    var diagnosticTypes: String? = null
)
