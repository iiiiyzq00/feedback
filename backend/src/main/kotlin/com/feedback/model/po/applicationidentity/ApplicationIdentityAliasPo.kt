package com.feedback.model.po.applicationidentity



/** t_application_identity_alias 持久化行；字符串状态不得越过 RepositoryImpl。 */
data class ApplicationIdentityAliasPo(
    var id: Long? = null,
    var aliasPublicId: String = "",
    var sourceChannel: String = "",
    var aliasType: String = "",
    var normalizedAlias: String = "",
    var activeAliasKeyHex: String? = null,
    var applicationIdentityId: Long = 0,
    var aliasVersion: Long = 1,
    var bindingStatus: String = "ACTIVE",
    var evidenceType: String = "AUTO_SAME_SOURCE",
    var previousBindingId: Long? = null,
    var requestId: String? = null,
    var commandFingerprintHex: String? = null,
    var targetMappingVersionSnapshot: Long? = null,
    var changedBy: Long? = null,
    var changeReason: String? = null
)
