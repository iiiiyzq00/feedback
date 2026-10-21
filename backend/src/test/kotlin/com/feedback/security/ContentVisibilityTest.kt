package com.feedback.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ContentVisibilityTest {
    private fun principal(vararg permissions:String)=WebPrincipal(1,"u","","U",setOf("PHASE_ONE_PRODUCT"),permissions.toSet(),authProvider="SSO")

    @Test fun `product sees partially hidden original values without masking placeholders`() {
        assertEquals("187****9695", ContentVisibility.present("PHONE", "18712349695", principal("access:phase_one_workspace")))
        assertEquals("y***@example.com", ContentVisibility.present("EMAIL", "yang@example.com", principal("access:phase_one_workspace")))
        assertEquals("SER****7890", ContentVisibility.present("DEVICE_SN", "SERIAL1234567890", principal("access:phase_one_workspace")))
    }

    @Test fun `technical permission sees the exact original value`() {
        val raw=principal("access:phase_one_workspace",ContentVisibility.RAW_PERMISSION.code)
        assertEquals("18712349695", ContentVisibility.present("PHONE", "18712349695", raw))
    }
}
