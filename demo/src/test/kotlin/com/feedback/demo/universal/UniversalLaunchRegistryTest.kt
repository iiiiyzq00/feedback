package com.feedback.demo.universal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniversalLaunchRegistryTest {
    @Test
    fun tokenCanOnlyBeConsumedOnce() {
        val context = UniversalFeedbackContext(
            sourcePackage = "com.seewo.example",
            sourceAppName = "Example",
            sourceVersionName = "1.0",
            sourceVersionCode = 1,
            sourceActivity = "ExampleActivity",
            triggerType = "FLOAT_BUTTON",
            detectedAt = System.currentTimeMillis(),
            detectionMethod = "USAGE_EVENTS",
            trustedSource = true
        )

        val token = UniversalLaunchRegistry.issue(context)

        assertEquals(context, UniversalLaunchRegistry.consume(token))
        assertNull(UniversalLaunchRegistry.consume(token))
    }
}
