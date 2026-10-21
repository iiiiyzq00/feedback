package com.feedback.model.po.feedback.diagnostics

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class FeedbackDiagnosticStationPo(
    var sourceId: Long = 0, var sourceUserId: String? = null, var authenticatedUserId: String? = null
)

/** SDK 加密诊断 JSON；解密后的根节点必须是 object，不接受数组或标量。 */

