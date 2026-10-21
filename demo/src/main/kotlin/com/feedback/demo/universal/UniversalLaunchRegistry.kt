package com.feedback.demo.universal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 进程内、一次性的中继，使对外暴露的入口无法伪造可信来源数据。 */
object UniversalLaunchRegistry {
    private data class Entry(val context: UniversalFeedbackContext, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun issue(context: UniversalFeedbackContext): String {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAt < now }
        return UUID.randomUUID().toString().also {
            entries[it] = Entry(context, now + TOKEN_TTL_MS)
        }
    }

    fun consume(token: String?): UniversalFeedbackContext? {
        val entry = token?.let(entries::remove) ?: return null
        return entry.context.takeIf { entry.expiresAt >= System.currentTimeMillis() }
    }

    private const val TOKEN_TTL_MS = 30_000L
}
