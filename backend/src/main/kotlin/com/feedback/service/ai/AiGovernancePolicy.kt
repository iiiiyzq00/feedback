package com.feedback.service.ai

object AiGovernancePolicy {
    fun bucket(stableKey: String, salt: String = "") = Math.floorMod((stableKey + salt).hashCode(), 100)
    fun usesCandidate(stableKey: String, rolloutPercent: Int) = bucket(stableKey) < rolloutPercent
    fun usesShadow(stableKey: String, shadowPercent: Int) = bucket(stableKey, ":shadow") < shadowPercent
    fun shouldRollback(total: Int, failures: Int, minimum: Int, threshold: Double) =
        total >= minimum && total > 0 && failures.toDouble() / total >= threshold
    fun validSecondApprover(creator: Long?, approver: Long?, required: Boolean) =
        !required || approver != null && approver != creator
}
