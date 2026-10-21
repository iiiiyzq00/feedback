package com.feedback.model.po.cluster

import java.math.BigDecimal

data class ClusterShadowCandidateMemberPo(
    val generationId: Long,
    val feedbackSourceId: Long,
    val problemStatus: String,
    val clusterKeyHash: String?,
    val decisionCode: String,
    val score: BigDecimal?,
    val manualLocked: Boolean,
    val applicationHash: String?,
    val failureModeHash: String?,
    val targetHash: String?,
    val actionHash: String?,
    val errorSignatureHash: String?
)
