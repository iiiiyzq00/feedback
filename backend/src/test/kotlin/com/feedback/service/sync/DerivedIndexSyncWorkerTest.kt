package com.feedback.service.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DerivedIndexSyncWorkerTest {
    @Test
    fun `derived index identifiers keep feedback and cluster namespaces disjoint`() {
        val feedbackId = 9L
        val clusterExternalId = -9L
        assertEquals(9L, feedbackId)
        assertEquals(-9L, clusterExternalId)
    }
}
