package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.feedback.model.applicationidentity.ApplicationIdentitySafeText
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalApplicationTextMatcherTest {
    private val matcher = CanonicalApplicationTextMatcher()
    private val course = identity(1, "知识课程")
    private val tutor = identity(2, "AI学伴")

    @Test
    fun `one canonical name in safe description resolves identity`() {
        val result = matcher.match(
            ApplicationIdentitySafeText(null, "W0知识课程内容打不开，显示连接超时"),
            listOf(course, tutor)
        )

        assertTrue(result is CanonicalApplicationTextMatch.Resolved)
        assertEquals(course.publicId, (result as CanonicalApplicationTextMatch.Resolved).identity.publicId)
    }

    @Test
    fun `multiple canonical applications in one record fail closed as ambiguous`() {
        val result = matcher.match(
            ApplicationIdentitySafeText("知识课程跳转AI学伴失败", null),
            listOf(course, tutor)
        )

        assertTrue(result is CanonicalApplicationTextMatch.Ambiguous)
        assertEquals(2, (result as CanonicalApplicationTextMatch.Ambiguous).identityPublicIds.size)
    }

    @Test
    fun `unknown display text never creates a new application`() {
        val result = matcher.match(
            ApplicationIdentitySafeText("某新应用打不开", "连接超时"),
            listOf(course, tutor)
        )

        assertEquals(CanonicalApplicationTextMatch.Unresolved, result)
    }

    @Test
    fun `ascii canonical name does not match inside a larger ascii token`() {
        val result = matcher.match(
            ApplicationIdentitySafeText("myaiapp crashed", null),
            listOf(identity(3, "AI"))
        )

        assertEquals(CanonicalApplicationTextMatch.Unresolved, result)
    }

    private fun identity(id: Long, name: String) = ApplicationIdentitySnapshot(
        id,
        "00000000-0000-4000-8000-${id.toString().padStart(12, '0')}",
        name,
        1
    )
}
