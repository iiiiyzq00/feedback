package com.feedback.sdk.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内的诊断环形缓冲。它刻意不读 logcat：
 * Android 应用无法安全、可移植地读取其他应用的日志。
 */
object SdkLogBuffer {
    private const val MAX_ENTRIES = 1000
    private const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
    private var retentionMs = 5 * 60 * 1000L
    private var maxBytes = DEFAULT_MAX_BYTES
    private val entries = ArrayDeque<Entry>()
    private val keyedSensitive = Regex(
        "(?i)(authorization|token|password|secret|phone|email|cookie)(\\s*[:=]\\s*)([^\\s,;]+)"
    )
    private val valueSensitive = listOf(
        Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)") to "[PHONE]",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[EMAIL]",
        Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)") to "[ID_CARD]",
        Regex("(?<!\\d)\\d{16,19}(?!\\d)") to "[NUMBER_REDACTED]",
        Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])") to "[MAC]",
        Regex("(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)") to "[IP]"
    )

    @Synchronized
    fun configure(retentionMinutes: Int, maxSizeKb: Int) {
        // 保留足够的历史以满足产品契约：反馈要抓到
        // 弹窗打开之前的整整三分钟。
        retentionMs = retentionMinutes.coerceIn(3, 60) * 60_000L
        maxBytes = maxSizeKb.coerceIn(1, 10_240) * 1024
        purge(System.currentTimeMillis())
    }

    @Synchronized
    fun record(level: String, event: String, detail: String? = null) {
        val now = System.currentTimeMillis()
        purge(now)
        val safeEvent = sanitize(event).take(160)
        val safeDetail = detail?.let(::sanitize)?.take(1000)
        entries.addLast(Entry(now, level.take(8), safeEvent, safeDetail))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): ByteArray = snapshotWindow(3, System.currentTimeMillis())

    @Synchronized
    fun snapshotWindow(windowMinutes: Int, endTimestampMs: Long): ByteArray {
        purge(System.currentTimeMillis())
        val startTimestampMs = endTimestampMs - windowMinutes.coerceIn(1, 60) * 60_000L
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        val lines = entries.filter { it.timestamp in startTimestampMs..endTimestampMs }.map {
            "${formatter.format(Date(it.timestamp))} ${it.level} ${it.event}" +
                (it.detail?.let { detail -> " $detail" } ?: "")
        }
        val selected = ArrayDeque<String>()
        var byteCount = 0
        for (line in lines.asReversed()) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (byteCount + lineBytes > maxBytes) break
            selected.addFirst(line)
            byteCount += lineBytes
        }
        return selected.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun purge(now: Long) {
        while (entries.firstOrNull()?.timestamp?.let { now - it > retentionMs } == true) {
            entries.removeFirst()
        }
    }

    private fun sanitize(value: String): String =
        valueSensitive.fold(
            keyedSensitive.replace(value) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
        ) { safe, (pattern, replacement) -> pattern.replace(safe, replacement) }
            .replace(Regex("[\\r\\n]+"), " ")

    private data class Entry(
        val timestamp: Long,
        val level: String,
        val event: String,
        val detail: String?
    )
}
