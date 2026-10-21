package com.feedback.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiClassificationTaxonomyTest {
    @Test fun `taxonomy has unique stable codes and complete presentation fields`() {
        val contract = AiClassificationTaxonomy.contract()
        assertEquals("ai-taxonomy-v1", contract.version)
        assertEquals(7, contract.categories.size)
        assertEquals(7, contract.categories.map { it.code }.distinct().size)
        assertTrue(contract.categories.all { it.label.isNotBlank() && it.definition.isNotBlank() && it.positiveExamples.isNotEmpty() && it.excludes.isNotBlank() })
        assertEquals(listOf("S0", "S1", "S2", "S3", "S4"), contract.severities.map { it.code })
    }

    @Test fun `legacy aliases normalize while unknown category fails closed`() {
        assertEquals("BUG", AiClassificationTaxonomy.normalizeCategory("software_bug"))
        assertEquals("CONTENT_ERROR", AiClassificationTaxonomy.normalizeCategory("CONTENT"))
        assertEquals("COMPLAINT", AiClassificationTaxonomy.normalizeCategory("claim"))
        assertNull(AiClassificationTaxonomy.normalizeCategory("UNKNOWN_NEW_VALUE"))
        assertTrue(AiClassificationTaxonomy.contract().unknownPolicy.contains("不得自动写入"))
    }
}
