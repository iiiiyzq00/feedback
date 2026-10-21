package com.feedback.demo

import android.util.Base64

object TransportPublicKey {
    fun pemOrNull(): String? = BuildConfig.SDK_SERVER_PUBLIC_KEY_BASE64
        .takeIf { it.isNotBlank() }
        ?.let { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }
        ?.takeIf { it.contains("BEGIN PUBLIC KEY") && it.contains("END PUBLIC KEY") }
}
