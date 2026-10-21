package com.feedback.demo.universal

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 生命周期极短的内存中继。明文截图绝不落盘。 */
object CapturedScreenshotRegistry {
    private data class Entry(val bytes: ByteArray, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun issue(bytes: ByteArray): String {
        purge()
        return UUID.randomUUID().toString().also {
            entries[it] = Entry(bytes, System.currentTimeMillis() + TTL_MS)
        }
    }

    fun consume(token: String?): ByteArray? {
        val entry = token?.let(entries::remove) ?: return null
        return if (entry.expiresAt >= System.currentTimeMillis()) entry.bytes
        else null.also { entry.bytes.fill(0) }
    }

    fun discard(token: String?) {
        token?.let(entries::remove)?.bytes?.fill(0)
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf {
            val expired = it.value.expiresAt < now
            if (expired) it.value.bytes.fill(0)
            expired
        }
    }

    private const val TTL_MS = 60_000L
}
