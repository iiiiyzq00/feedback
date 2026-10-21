package com.feedback.service.query

import com.feedback.common.FeedbackChannel
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionPo
import com.feedback.security.FeedbackAccessGuard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 通过运行时类型结构保护列表追溯契约，不依赖源码排版或私有实现字符串。 */
class FeedbackQueryTraceabilityContractTest {
    @Test
    fun `list projection exposes governed source and business batch facts`() {
        val fields = FeedbackListProjectionPo::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertTrue(
            fields.containsAll(
                setOf("sourceRecordId", "ingestTime", "sourceUpdateTime", "platformUpdateTime", "importBatchCode")
            )
        )
    }

    @Test
    fun `phase one channel authority is closed and keeps existing wire values`() {
        assertEquals(
            setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"),
            FeedbackChannel.feedbackWireValues
        )
        assertEquals(FeedbackChannel.STATION_FEEDBACK, FeedbackChannel.fromWire("station_feedback"))
        assertEquals(null, FeedbackChannel.fromWire("UNKNOWN"))
    }

    @Test
    fun `direct access guard depends only on named repositories and security collaborators`() {
        val dependencyTypes = FeedbackAccessGuard::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asIterable() }
            .map { it.name }

        assertTrue(dependencyTypes.any { it.endsWith("FeedbackAccessRepository") })
        assertTrue(dependencyTypes.any { it.endsWith("DataPermissionService") })
        assertTrue(dependencyTypes.any { it.endsWith("CurrentWebPrincipalProvider") })
        assertFalse(dependencyTypes.any { it.startsWith("org.springframework.jdbc") })
    }
}
