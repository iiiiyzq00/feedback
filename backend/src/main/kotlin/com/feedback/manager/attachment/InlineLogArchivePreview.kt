package com.feedback.manager.attachment

import com.feedback.service.attachment.*

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** 从受支持归档中提取可读日志条目并生成有界 UTF-8 预览。 */
internal object InlineLogArchivePreview {
    private const val MAX_DEPTH = 4
    private const val MAX_ENTRIES = 2_000
    private val textExtensions = setOf("log", "txt", "json", "xml", "csv", "trace", "out", "err", "properties", "yaml", "yml")
    private val archiveExtensions = setOf("zip", "gz", "gzip", "tgz", "tar")

    /** 识别归档后执行有深度、条目和字节上限的解析；非归档返回 null。 */
    fun render(
        input: InputStream,
        filename: String,
        mimeType: String,
        limitBytes: Long,
        truncationNotice: ByteArray = inlineLogTruncationNotice(limitBytes)
    ): ByteArray? {
        if (!isArchive(filename, mimeType)) {
            return null
        }
        val sink = PreviewSink(limitBytes, truncationNotice)
        runCatching { readArchive(NonClosingInputStream(input), filename, mimeType, sink, 0) }
            .onFailure { sink.fail() }
        return sink.finish()
    }

    /** 按名称与 MIME 分派归档格式，并限制嵌套深度。 */
    private fun readArchive(input: InputStream, name: String, mimeType: String?, sink: PreviewSink, depth: Int) {
        if (depth > MAX_DEPTH || sink.done) {
            sink.truncate()
            return
        }
        when {
            isZip(name, mimeType) -> readZip(ZipInputStream(BufferedInputStream(input)), sink, depth)
            isGzip(name, mimeType) -> GZIPInputStream(BufferedInputStream(input)).use { gzip ->
                val unwrappedName = stripGzipSuffix(name)
                val buffered = BufferedInputStream(gzip)
                if (name.endsWith(".tgz", true) || unwrappedName.endsWith(".tar", true) || looksLikeTar(buffered)) {
                    readTar(TarArchiveInputStream(buffered), sink, depth)
                } else {
                    appendTextEntry(unwrappedName, buffered, sink, includeHeader = depth > 0)
                }
            }
            name.endsWith(".tar", true) || looksLikeTar(input) -> readTar(TarArchiveInputStream(input), sink, depth)
        }
    }

    /** 顺序读取 ZIP 条目，拒绝越界名称并限制总条目数。 */
    private fun readZip(zip: ZipInputStream, sink: PreviewSink, depth: Int) {
        zip.use {
            while (!sink.done) {
                val entry = zip.nextEntry ?: break
                if (!sink.visitEntry() || unsafeName(entry.name)) {
                    sink.truncate()
                    break
                }
                if (!entry.isDirectory) {
                    readEntry(NonClosingInputStream(zip), entry.name, sink, depth)
                }
                zip.closeEntry()
            }
        }
    }

    /** 顺序读取 TAR 普通文件条目，拒绝越界名称并限制总条目数。 */
    private fun readTar(tar: TarArchiveInputStream, sink: PreviewSink, depth: Int) {
        tar.use {
            while (!sink.done) {
                val entry = tar.nextEntry ?: break
                if (!sink.visitEntry() || unsafeName(entry.name)) {
                    sink.truncate()
                    break
                }
                if (!entry.isDirectory && entry.isFile) {
                    readEntry(NonClosingInputStream(tar), entry.name, sink, depth)
                }
            }
        }
    }

    /** 只处理已登记文本或归档扩展名，未知二进制条目直接忽略。 */
    private fun readEntry(input: InputStream, name: String, sink: PreviewSink, depth: Int) {
        val extension = name.substringAfterLast('.', "").lowercase()
        when {
            extension in archiveExtensions -> readArchive(input, name, null, sink, depth + 1)
            extension in textExtensions -> appendTextEntry(name, input, sink)
        }
    }

    /** 有界读取文本条目，二进制内容不进入预览。 */
    private fun appendTextEntry(name: String, input: InputStream, sink: PreviewSink, includeHeader: Boolean = true) {
        val bytes = readAtMost(input, sink.remaining + 1)
        if (looksBinary(bytes)) {
            return
        }
        val text = decodeText(bytes)
        if (includeHeader) {
            sink.append("\n===== $name =====\n")
        }
        sink.append(text)
        if (bytes.size.toLong() > sink.remainingBeforeLastAppend) {
            sink.truncate()
        }
    }

    /** 最多读取指定字节数，临时缓冲区使用后清零。 */
    private fun readAtMost(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024L).toInt())
        val buffer = ByteArray(8192)
        var remaining = maxBytes
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) {
                break
            }
            out.write(buffer, 0, count)
            remaining -= count
        }
        buffer.fill(0)
        return out.toByteArray()
    }

    /** 优先严格 UTF-8 解码，截断尾字符后仍失败才兼容 GB18030。 */
    private fun decodeText(bytes: ByteArray): String {
        // 有界读取可能停在最后一个 UTF-8 字符中间。
        // 先按最多去掉尾部 3 字节重试，再判定来源是否为 GB18030。
        for (trimmed in 0..minOf(3, bytes.size)) {
            val decoded = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.size - trimmed)).toString()
            }.getOrNull()
            if (decoded != null) {
                return decoded
            }
        }
        return String(bytes, charset("GB18030"))
    }

    /** 按 NUL 和控制字符比例识别二进制内容。 */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(4096)
        if (sample.any { it == 0.toByte() }) {
            return true
        }
        val controls = sample.count { value ->
            val unsigned = value.toInt() and 0xff
            unsigned < 0x20 && unsigned !in setOf(0x09, 0x0a, 0x0d)
        }
        return sample.isNotEmpty() && controls * 10 > sample.size
    }

    /** 在支持 mark 的流上探测 TAR 魔数且不消费输入。 */
    private fun looksLikeTar(input: InputStream): Boolean {
        if (!input.markSupported()) {
            return false
        }
        input.mark(512)
        val header = input.readNBytes(512)
        input.reset()
        return header.size == 512 && String(header, 257, 5, StandardCharsets.US_ASCII) == "ustar"
    }

    /** 去除 gzip 扩展名，并将 tgz 映射为 tar 名称。 */
    private fun stripGzipSuffix(name: String): String = when {
        name.endsWith(".tgz", true) -> name.dropLast(4) + ".tar"
        name.endsWith(".gzip", true) -> name.dropLast(5)
        name.endsWith(".gz", true) -> name.dropLast(3)
        else -> name
    }

    /** 根据受支持 MIME 或扩展名判断是否为归档。 */
    private fun isArchive(name: String, mimeType: String?) =
        isZip(name, mimeType) || isGzip(name, mimeType) || name.endsWith(".tar", true)

    /** 判断 ZIP MIME 或扩展名。 */
    private fun isZip(name: String, mimeType: String?) =
        mimeType.equals("application/zip", true) ||
            mimeType.equals("application/x-zip-compressed", true) ||
            name.endsWith(".zip", true)

    /** 判断 gzip/tgz MIME 或扩展名。 */
    private fun isGzip(name: String, mimeType: String?) =
        mimeType.equals("application/gzip", true) ||
            mimeType.equals("application/x-gzip", true) ||
            name.endsWith(".gz", true) ||
            name.endsWith(".gzip", true) ||
            name.endsWith(".tgz", true)

    /** 拒绝绝对路径、盘符、空段和父目录跳转。 */
    private fun unsafeName(name: String): Boolean {
        val normalized = name.replace('\\', '/').trimEnd('/')
        return normalized.isBlank() || normalized.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(normalized) || normalized.split('/').any { it == ".." || it.isBlank() }
    }

    /** 汇总归档条目预览并强制总条目数、总字节数与失败关闭语义。 */
    private class PreviewSink(private val limit: Long, private val truncationNotice: ByteArray) {
        private val out = ByteArrayOutputStream(minOf(limit, 64 * 1024L).toInt())
        private var entries = 0
        private var found = false
        private var failed = false
        var truncated = false
            private set
        var remainingBeforeLastAppend = limit
            private set
        val remaining get() = (limit - out.size()).coerceAtLeast(0)
        val done get() = remaining == 0L || failed

        /** 登记一个归档条目并检查总数上限。 */
        fun visitEntry() = ++entries <= MAX_ENTRIES

        /** 在剩余字节范围内追加文本并记录截断状态。 */
        fun append(value: String) {
            found = true
            remainingBeforeLastAppend = remaining
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val count = minOf(bytes.size.toLong(), remaining).toInt()
            out.write(bytes, 0, count)
            if (count < bytes.size) {
                truncated = true
            }
            bytes.fill(0)
        }
        /** 标记预览已达到任一安全上限。 */
        fun truncate() {
            truncated = true
        }

        /** 标记归档解析失败，最终返回稳定失败提示。 */
        fun fail() {
            failed = true
        }

        /** 根据解析状态生成最终有界字节，不返回部分失败内容。 */
        fun finish(): ByteArray {
            if (failed) {
                return "日志压缩包无法安全解析，请联系管理员检查归档格式。".toByteArray(StandardCharsets.UTF_8)
            }
            if (!found) {
                return "日志压缩包内未发现可预览的文本日志文件。".toByteArray(StandardCharsets.UTF_8)
            }
            if (truncated) {
                out.write(truncationNotice)
            }
            return out.toByteArray()
        }
    }

    /** 嵌套归档读取适配器；子解析器关闭时保留共享父流。 */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        /** 嵌套解析结束时不关闭共享归档流。 */
        override fun close() = Unit
    }
}
