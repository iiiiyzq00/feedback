package com.feedback.security

import com.feedback.security.station.StationGatewayAuthenticationFilter

import com.feedback.common.exception.ServiceException
import com.feedback.security.station.StationUcAuthenticator
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate

class StationGatewayAuthenticationFilterConfigurationTest {

    @Test
    fun `adapter mode refuses to start without an authorized client id`() {
        val error = assertThrows(ServiceException::class.java) {
            filter(adapterEnabled = true, clientIds = " , ")
        }
        assertEquals("STATION_ADAPTER_CLIENT_IDS_REQUIRED", error.reasonCode)
    }

    @Test
    fun `UC mode permits an empty adapter client list`() {
        assertDoesNotThrow { filter(adapterEnabled = false, clientIds = "") }
    }

    private fun filter(adapterEnabled: Boolean, clientIds: String) = StationGatewayAuthenticationFilter(
        redis = Mockito.mock(StringRedisTemplate::class.java),
        errors = Mockito.mock(SecurityErrorWriter::class.java),
        ucAuthenticator = Mockito.mock(StationUcAuthenticator::class.java),
        enabled = true,
        secret = "station-test-secret-at-least-32-characters",
        maxBodyBytes = 65_536,
        windowSeconds = 300,
        adapterEnabled = adapterEnabled,
        adapterClientIdsRaw = clientIds,
        adapterResolvedUserIdHeader = "X-Station-Resolved-User-Id"
    )
}
