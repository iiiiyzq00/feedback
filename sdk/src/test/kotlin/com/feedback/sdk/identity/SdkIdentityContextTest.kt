package com.feedback.sdk.identity

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkIdentityContextTest {
    @After fun clear() = SdkIdentityContext.clear()

    @Test
    fun `stores host governed identity and contact values`() {
        SdkIdentityContext.setUser(" user-001 ")
        SdkIdentityContext.setDeviceSerialNumber(" SN-DT15PA-001 ")
        SdkIdentityContext.setContact("138 0013 8000", "student@example.com")

        assertEquals(
            SdkIdentitySnapshot("user-001", "SN-DT15PA-001", "138 0013 8000", "student@example.com"),
            SdkIdentityContext.snapshot()
        )
    }

    @Test
    fun `null values clear identities without inventing device identifiers`() {
        SdkIdentityContext.setUser("user-001")
        SdkIdentityContext.setDeviceSerialNumber("SN-001")
        SdkIdentityContext.setContact("13800138000", "student@example.com")
        SdkIdentityContext.setUser(null)
        SdkIdentityContext.setDeviceSerialNumber(null)
        SdkIdentityContext.setContact(null, null)

        val snapshot = SdkIdentityContext.snapshot()
        assertNull(snapshot.userId)
        assertNull(snapshot.deviceSerialNumber)
        assertNull(snapshot.phone)
        assertNull(snapshot.email)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects malformed email`() {
        SdkIdentityContext.setContact(phone = null, email = "not-an-email")
    }
}
