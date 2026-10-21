package com.feedback.security.attachment

import com.feedback.service.attachment.*

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** 流式验证日志归档，不把归档条目写入磁盘。 */
object SafeLogArchiveInspector {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_ENTRY_BYTES = 1024L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    private const val MAX_EXPANSION_RATIO = 100L
    private val allowedExtensions = setOf("","log","txt","json","xml","csv","trace","dmp","bin","out","err","curf","properties","yaml","yml")

    /** 按受支持 MIME 流式检查归档；任何解析或文件异常都失败关闭为 false。 */
    fun inspect(path: Path, mimeType: String): Boolean = runCatching {
        val compressedSize = Files.size(path).coerceAtLeast(1)
        when (mimeType) {
            "application/zip" -> inspectZip(path, compressedSize)
            "application/gzip" -> inspectGzip(path, compressedSize)
            else -> false
        }
    }.getOrDefault(false)

    /** 校验 ZIP 条目名称、扩展名、条目数、展开总量和膨胀比例。 */
    private fun inspectZip(path: Path, compressedSize: Long): Boolean {
        var entries = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(Files.newInputStream(path))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES || unsafeName(entry.name)) {
                    return false
                }
                if (!entry.isDirectory) {
                    val extension = entry.name.substringAfterLast('.', "").lowercase()
                    if (extension !in allowedExtensions) {
                        return false
                    }
                    val result = inspectStream(zip, total, compressedSize)
                    if (!result.safe) {
                        return false
                    }
                    total = result.total
                }
                zip.closeEntry()
            }
        }
        return entries > 0 && total > 0
    }

    /** 流式校验 gzip 展开内容及膨胀比例。 */
    private fun inspectGzip(path: Path, compressedSize: Long): Boolean =
        GZIPInputStream(BufferedInputStream(Files.newInputStream(path))).use { inspectStream(it, 0, compressedSize).safe }

    /** 单条归档流的安全结论与累计展开字节数。 */
    private data class StreamResult(val safe: Boolean, val total: Long)
    /** 有界读取单条目并识别常见可执行文件魔数。 */
    private fun inspectStream(input: InputStream, previousTotal: Long, compressedSize: Long): StreamResult {
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        var total = previousTotal
        var prefix = ByteArray(0)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (prefix.size < 8) {
                prefix += buffer.copyOfRange(0, minOf(read, 8 - prefix.size))
            }
            entryBytes += read
            total += read
            if (entryBytes > MAX_ENTRY_BYTES || total > MAX_EXPANDED_BYTES || total > compressedSize * MAX_EXPANSION_RATIO) {
                return StreamResult(false, total)
            }
        }
        buffer.fill(0)
        return StreamResult(entryBytes > 0 && !executableMagic(prefix), total)
    }

    /** 拒绝绝对路径、盘符、空段和父目录跳转。 */
    private fun unsafeName(name: String): Boolean {
        val normalized = name.replace('\\', '/').trimEnd('/')
        return normalized.isBlank() || normalized.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." || it.isBlank() }
    }
    /** 识别 PE、ELF 和 Java class 魔数，防止可执行内容伪装成日志。 */
    private fun executableMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte() ||
        bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() ||
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(
            byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())
        )
}
