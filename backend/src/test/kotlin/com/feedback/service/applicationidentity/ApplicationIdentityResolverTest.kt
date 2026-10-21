package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.strategy.applicationidentity.*
import com.feedback.manager.applicationidentity.*
import com.feedback.job.applicationidentity.*
import com.feedback.manager.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationIdentityResolverTest {
    private val resolver = ApplicationIdentityResolver()

    @Test
    fun `same SDK record creates one conservative identity with both aliases`() {
        val result = resolver.resolve(
            sdkInput(appId = "Reader-App", packageName = " COM.EXAMPLE.READER "),
            emptyMap()
        ) as ApplicationResolution.NewIdentity

        assertEquals(2, result.keys.size)
        assertEquals("com.example.reader", result.keys.single { it.aliasType == ApplicationAliasType.SDK_PACKAGE_NAME }.normalizedAlias)
        assertEquals("Reader-App", result.keys.single { it.aliasType == ApplicationAliasType.SDK_APP_ID }.normalizedAlias)
    }

    @Test
    fun `known SDK alias binds its other alias to the same identity`() {
        val identity = identity(1, "reader")
        val appKey = ApplicationAliasInput(
            ApplicationSourceChannel.SDK,
            ApplicationAliasType.SDK_APP_ID,
            "Reader-App"
        ).normalized()!!
        val result = resolver.resolve(
            sdkInput(appId = "Reader-App", packageName = "com.example.reader"),
            mapOf(appKey to ActiveApplicationAlias("alias-1", appKey, 1, identity))
        ) as ApplicationResolution.Resolved

        assertEquals(identity, result.identity)
        assertEquals(listOf(ApplicationAliasType.SDK_PACKAGE_NAME), result.aliasesToBind.map { it.aliasType })
    }

    @Test
    fun `aliases already mapped to different identities fail closed`() {
        val input = sdkInput(appId = "Reader-App", packageName = "com.example.reader")
        val keys = input.aliases.mapNotNull(ApplicationAliasInput::normalized)
        val result = resolver.resolve(
            input,
            mapOf(
                keys[0] to ActiveApplicationAlias("alias-1", keys[0], 1, identity(1, "reader-a")),
                keys[1] to ActiveApplicationAlias("alias-2", keys[1], 1, identity(2, "reader-b"))
            )
        ) as ApplicationResolution.Ambiguous

        assertEquals(setOf("reader-a", "reader-b"), result.identityPublicIds)
    }

    @Test
    fun `station display name never merges with SDK identity`() {
        val result = resolver.resolve(
            ApplicationIdentitySourceInput(
                2,
                ApplicationSourceChannel.STATION_FEEDBACK,
                listOf(
                    ApplicationAliasInput(
                        ApplicationSourceChannel.STATION_FEEDBACK,
                        ApplicationAliasType.STATION_SOURCE_APP_ID,
                        "station-app-42"
                    )
                ),
                "same-visible-name"
            ),
            emptyMap()
        )

        assertTrue(result is ApplicationResolution.NewIdentity)
    }

    @Test
    fun `missing source identity alias remains unresolved`() {
        val result = resolver.resolve(
            ApplicationIdentitySourceInput(3, ApplicationSourceChannel.STATION_FEEDBACK, emptyList(), "Reader"),
            emptyMap()
        )

        assertEquals(ApplicationResolution.Unresolved, result)
    }

    @Test
    fun `invalid source id is a client error`() {
        val error = assertThrows(ClientException::class.java) {
            resolver.resolve(sdkInput("reader", null).copy(sourceId = 0), emptyMap())
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("APPLICATION_SOURCE_ID_INVALID", error.reasonCode)
    }

    @Test
    fun `lookup rows outside requested alias scope fail as source integrity`() {
        val input = sdkInput("reader", null)
        val unexpectedKey = ApplicationAliasKey(
            ApplicationSourceChannel.SDK,
            ApplicationAliasType.SDK_APP_ID,
            "another-reader"
        )
        val error = assertThrows(ServiceException::class.java) {
            resolver.resolve(
                input,
                mapOf(unexpectedKey to ActiveApplicationAlias("a", unexpectedKey, 1, identity(1, "reader")))
            )
        }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, error.errorCode)
        assertEquals("APPLICATION_ALIAS_LOOKUP_SCOPE_INVALID", error.reasonCode)
    }

    private fun sdkInput(appId: String?, packageName: String?) = ApplicationIdentitySourceInput(
        sourceId = 1,
        sourceChannel = ApplicationSourceChannel.SDK,
        aliases = listOf(
            ApplicationAliasInput(ApplicationSourceChannel.SDK, ApplicationAliasType.SDK_APP_ID, appId),
            ApplicationAliasInput(ApplicationSourceChannel.SDK, ApplicationAliasType.SDK_PACKAGE_NAME, packageName)
        ),
        displayNameEvidence = packageName ?: appId
    )

    private fun identity(id: Long, publicId: String) = ApplicationIdentitySnapshot(id, publicId, publicId, 1)
}
