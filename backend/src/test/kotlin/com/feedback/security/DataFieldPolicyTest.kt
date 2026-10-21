package com.feedback.security

import com.feedback.model.auth.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DataFieldPolicyTest {
    @Test fun `deny overrides wildcard while structural fields remain available`() {
        val policy=DataFieldPolicy(true,setOf("*"),setOf("DESCRIPTION","SOURCE_RECORD_ID"))
        assertFalse(policy.permits("description"))
        assertFalse(policy.permits("sourceRecordId"))
        assertTrue(policy.permits("title"))
        assertTrue(policy.permits("id"))
        assertTrue(policy.permits("sourceChannel"))
    }

    @Test fun `explicit allow list is fail closed for unspecified business fields`() {
        val policy=DataFieldPolicy(false,setOf("TITLE"),emptySet())
        assertTrue(policy.permits("title"))
        assertFalse(policy.permits("description"))
    }
}
