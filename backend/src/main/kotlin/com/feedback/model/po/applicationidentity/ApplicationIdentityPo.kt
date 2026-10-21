package com.feedback.model.po.applicationidentity



/** t_application_identity 持久化行；publicId 对应 BINARY(16) UUID。 */
data class ApplicationIdentityPo(
    var id: Long? = null,
    var publicId: String = "",
    var displayName: String = "",
    var lifecycleStatus: String = "ACTIVE",
    var mappingVersion: Long = 1,
    var createdBy: Long? = null
)
