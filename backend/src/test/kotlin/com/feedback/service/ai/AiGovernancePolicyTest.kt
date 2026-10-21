package com.feedback.service.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiGovernancePolicyTest {
    @Test fun `rollout boundaries are deterministic`() {
        repeat(1000) { key ->
            assertEquals(AiGovernancePolicy.usesCandidate(key.toString(), 35), AiGovernancePolicy.usesCandidate(key.toString(), 35))
            assertFalse(AiGovernancePolicy.usesCandidate(key.toString(), 0))
            assertTrue(AiGovernancePolicy.usesCandidate(key.toString(), 100))
        }
    }

    @Test fun `shadow selection is independently salted`() {
        val keys = (1..1000).map(Int::toString)
        val rollout = keys.count { AiGovernancePolicy.usesCandidate(it, 10) }
        val shadow = keys.count { AiGovernancePolicy.usesShadow(it, 10) }
        assertTrue(rollout in 60..140)
        assertTrue(shadow in 60..140)
        assertNotEquals(keys.filter { AiGovernancePolicy.usesCandidate(it, 10) }, keys.filter { AiGovernancePolicy.usesShadow(it, 10) })
    }

    @Test fun `automatic rollback honors minimum sample and inclusive threshold`() {
        assertFalse(AiGovernancePolicy.shouldRollback(19, 19, 20, .10))
        assertTrue(AiGovernancePolicy.shouldRollback(20, 2, 20, .10))
        assertFalse(AiGovernancePolicy.shouldRollback(20, 1, 20, .10))
    }

    @Test fun `two person approval rejects creator and missing identity`() {
        assertFalse(AiGovernancePolicy.validSecondApprover(7, 7, true))
        assertFalse(AiGovernancePolicy.validSecondApprover(7, null, true))
        assertTrue(AiGovernancePolicy.validSecondApprover(7, 8, true))
        assertTrue(AiGovernancePolicy.validSecondApprover(7, 7, false))
    }
}
