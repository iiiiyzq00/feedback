package com.feedback.service.cluster

import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
import com.feedback.manager.applicationidentity.ApplicationIdentityResolver
import com.feedback.model.applicationidentity.ApplicationResolution
import com.feedback.strategy.applicationidentity.CanonicalApplicationTextMatcher
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoricalApplicationTextResolutionTest {
    @Test
    fun `three external channels use only unique canonical mentions`() {
        val sources = mockk<ApplicationIdentitySourceRepository>()
        val identities = mockk<ApplicationIdentityRepository>()
        val resolver = mockk<ApplicationIdentityResolver>()
        val primaryFacts = mockk<PrimaryProblemFactRepository>()
        val safeTexts = mockk<ApplicationIdentitySafeTextRepository>()
        val inputs = listOf(
            input(1, ApplicationSourceChannel.JIRA),
            input(2, ApplicationSourceChannel.QUALITY),
            input(3, ApplicationSourceChannel.REDBLACK)
        )
        val course = identity(11, "知识课程")
        val tutor = identity(12, "AI学伴")
        every { sources.nextFourChannelInputs(0, 3) } returns inputs
        every { identities.activeAliases(emptyList()) } returns emptyMap()
        every { identities.listAfter(null, 100) } returns listOf(course, tutor)
        every { primaryFacts.findBySourceIds(listOf(1L, 2L, 3L)) } returns emptyMap()
        every { safeTexts.findPassingBySourceIds(listOf(1L, 2L, 3L)) } returns mapOf(
            1L to ApplicationIdentitySafeText(null, "知识课程内容打不开，连接超时"),
            2L to ApplicationIdentitySafeText("硬件无法开机", null),
            3L to ApplicationIdentitySafeText("知识课程跳转AI学伴失败", null)
        )
        inputs.forEach { every { resolver.resolve(it, emptyMap()) } returns ApplicationResolution.Unresolved }

        val facts = HistoricalApplicationFactReader(
            sources, identities, resolver, primaryFacts, safeTexts, CanonicalApplicationTextMatcher()
        ).read(0, 3)

        assertEquals(course.publicId, facts[0].applicationKey)
        assertEquals(null, facts[1].applicationKey)
        assertFalse(facts[1].ambiguous)
        assertTrue(facts[2].ambiguous)
    }

    @Test
    fun `station unresolved fact still does not use display text`() {
        val sources = mockk<ApplicationIdentitySourceRepository>()
        val identities = mockk<ApplicationIdentityRepository>()
        val resolver = mockk<ApplicationIdentityResolver>()
        val primaryFacts = mockk<PrimaryProblemFactRepository>()
        val safeTexts = mockk<ApplicationIdentitySafeTextRepository>()
        val input = input(1, ApplicationSourceChannel.STATION_FEEDBACK)
        every { sources.nextFourChannelInputs(0, 1) } returns listOf(input)
        every { identities.activeAliases(emptyList()) } returns emptyMap()
        every { resolver.resolve(input, emptyMap()) } returns ApplicationResolution.Unresolved
        every { primaryFacts.findBySourceIds(listOf(1L)) } returns mapOf(
            1L to PrimaryProblemFact(
                feedbackSourceId = 1,
                applicationIdentityId = null,
                applicationPublicIdSnapshot = null,
                applicationDisplayNameSnapshot = null,
                applicationMappingVersion = null,
                applicationResolutionStatus = ApplicationResolutionStatus.UNRESOLVED,
                classificationStatus = PrimaryProblemClassificationStatus.PENDING,
                resolutionRuleVersion = "application-identity-v1",
                resolutionReasonCode = "APPLICATION_ALIAS_MISSING",
                resolvedAliasCount = 0
            )
        )
        every { safeTexts.findPassingBySourceIds(emptyList()) } returns emptyMap()

        val fact = HistoricalApplicationFactReader(
            sources, identities, resolver, primaryFacts, safeTexts, CanonicalApplicationTextMatcher()
        ).read(0, 1).single()

        assertEquals(null, fact.applicationKey)
        assertFalse(fact.ambiguous)
    }

    @Test
    fun `latest governed station alias overrides stale unresolved shadow fact`() {
        val sources = mockk<ApplicationIdentitySourceRepository>()
        val identities = mockk<ApplicationIdentityRepository>()
        val resolver = ApplicationIdentityResolver()
        val primaryFacts = mockk<PrimaryProblemFactRepository>()
        val safeTexts = mockk<ApplicationIdentitySafeTextRepository>()
        val key = ApplicationAliasKey(
            ApplicationSourceChannel.STATION_FEEDBACK,
            ApplicationAliasType.STATION_SOURCE_APP_ID,
            "reviewed-station-app"
        )
        val source = ApplicationIdentitySourceInput(
            1,
            ApplicationSourceChannel.STATION_FEEDBACK,
            listOf(ApplicationAliasInput(key.sourceChannel, key.aliasType, key.normalizedAlias)),
            "优学练习"
        )
        val governed = identity(21, "优学练习")
        every { sources.nextFourChannelInputs(0, 1) } returns listOf(source)
        every { identities.activeAliases(listOf(key)) } returns mapOf(
            key to ActiveApplicationAlias("alias-public-id", key, 1, governed)
        )
        every { primaryFacts.findBySourceIds(listOf(1L)) } returns mapOf(
            1L to PrimaryProblemFact(
                feedbackSourceId = 1,
                applicationIdentityId = null,
                applicationPublicIdSnapshot = null,
                applicationDisplayNameSnapshot = null,
                applicationMappingVersion = null,
                applicationResolutionStatus = ApplicationResolutionStatus.UNRESOLVED,
                classificationStatus = PrimaryProblemClassificationStatus.PENDING,
                resolutionRuleVersion = "application-identity-v1",
                resolutionReasonCode = "APPLICATION_ALIAS_MISSING",
                resolvedAliasCount = 0
            )
        )
        every { safeTexts.findPassingBySourceIds(emptyList()) } returns emptyMap()

        val fact = HistoricalApplicationFactReader(
            sources, identities, resolver, primaryFacts, safeTexts, CanonicalApplicationTextMatcher()
        ).read(0, 1).single()

        assertEquals(governed.publicId, fact.applicationKey)
        assertEquals("优学练习", fact.applicationName)
        assertFalse(fact.ambiguous)
    }

    private fun input(id: Long, channel: ApplicationSourceChannel) =
        ApplicationIdentitySourceInput(id, channel, emptyList(), null)

    private fun identity(id: Long, name: String) = ApplicationIdentitySnapshot(
        id,
        "00000000-0000-4000-8000-${id.toString().padStart(12, '0')}",
        name,
        1
    )
}
