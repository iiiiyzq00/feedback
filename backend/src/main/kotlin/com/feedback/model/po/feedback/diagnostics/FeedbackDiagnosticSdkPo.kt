package com.feedback.model.po.feedback.diagnostics

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class FeedbackDiagnosticSdkPo(
    var sourceId: Long = 0, var diagnosticCiphertext: ByteArray? = null,
    var diagnosticNonce: ByteArray? = null, var diagnosticTag: ByteArray? = null,
    var diagnosticKeyId: String? = null, var diagnosticAlgorithm: String? = null
)

/** 日志附件的受治理安全元数据；不含存储路径、正文或访问令牌。 */

