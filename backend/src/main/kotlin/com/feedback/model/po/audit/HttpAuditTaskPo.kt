package com.feedback.model.po.audit

data class HttpAuditTaskPo(
    var id: Long = 0,
    var actorId: Long? = null,
    var operation: String = "",
    var auditClass: String = "",
    var outcome: String = "",
    var targetType: String = "",
    var targetId: String? = null,
    var requestId: String = "",
    var clientIp: String? = null,
    var eventDetail: String = "{}",
    var retryCount: Int = 0,
)
