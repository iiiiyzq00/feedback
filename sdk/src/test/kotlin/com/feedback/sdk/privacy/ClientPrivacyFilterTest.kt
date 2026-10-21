package com.feedback.sdk.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClientPrivacyFilterTest {
    @Test fun `filters description and business context pii`() {
        val value=ClientPrivacyFilter.filter("电话13812345678 邮箱 a@b.com token=secret-value IP 10.1.2.3")
        assertFalse(value.contains("13812345678"))
        assertFalse(value.contains("a@b.com"))
        assertFalse(value.contains("secret-value"))
        assertFalse(value.contains("10.1.2.3"))
    }

    @Test fun `filters map values without changing keys`() {
        assertEquals("[PHONE]",ClientPrivacyFilter.filter(mapOf("contact" to "13812345678"))["contact"])
    }
}
