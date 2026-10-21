package com.feedback.demo.universal

import com.feedback.sdk.FeedbackDraft
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 宿主 Activity 被回收后，用于重建反馈表单的一次性中继。 */
object UniversalFeedbackRecoveryRegistry {
    data class Payload(
        val context: UniversalFeedbackContext,
        val draft: FeedbackDraft,
        val errorMessage: String?
    )

    private data class Entry(val payload: Payload, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun issue(context: UniversalFeedbackContext, draft: FeedbackDraft, errorMessage: String?): String {
        purge()
        return UUID.randomUUID().toString().also {
            entries[it] = Entry(Payload(context, draft, errorMessage), System.currentTimeMillis() + TTL_MS)
        }
    }

    fun consume(token: String?): Payload? {
        val entry = token?.let(entries::remove) ?: return null
        return entry.payload.takeIf { entry.expiresAt >= System.currentTimeMillis() }
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAt < now }
    }

    private const val TTL_MS = 5 * 60_000L
}
