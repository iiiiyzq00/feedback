package com.feedback.sdk.identity

/**
 * 由宿主应用提供的进程内身份值。
 *
 * SDK 刻意不根据 Android ID、机型、IMEI 或其他不稳定/受限标识
 * 去推导设备序列号。可信宿主在有权读取时，必须提供
 * 真实的受管设备序列号。
 */
internal object SdkIdentityContext {
    @Volatile private var state = SdkIdentitySnapshot(null, null, null, null)

    @Synchronized fun setUser(value: String?) {
        state = state.copy(userId = identifier(value, "userId"))
    }

    @Synchronized fun setDeviceSerialNumber(value: String?) {
        state = state.copy(deviceSerialNumber = identifier(value, "deviceSerialNumber"))
    }

    @Synchronized fun setContact(phone: String?, email: String?) {
        val normalizedPhone = phone?.trim()?.takeIf(String::isNotEmpty)
        val normalizedEmail = email?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedPhone == null || normalizedPhone.matches(PHONE)) { "phone format is invalid" }
        require(normalizedEmail == null || normalizedEmail.matches(EMAIL)) { "email format is invalid" }
        state = state.copy(phone = normalizedPhone, email = normalizedEmail)
    }

    fun snapshot() = state

    @Synchronized fun clear() {
        state = EMPTY
    }

    private fun identifier(value: String?, field: String): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(normalized.length <= 200) { "$field must contain at most 200 characters" }
        require(normalized.none(Char::isISOControl)) { "$field must not contain control characters" }
        return normalized
    }

    private val PHONE = Regex("^\\+?[0-9][0-9 -]{5,24}$")
    private val EMAIL = Regex("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$")
    private val EMPTY = SdkIdentitySnapshot(null, null, null, null)
}

internal data class SdkIdentitySnapshot(
    val userId: String?,
    val deviceSerialNumber: String?,
    val phone: String?,
    val email: String?
)
