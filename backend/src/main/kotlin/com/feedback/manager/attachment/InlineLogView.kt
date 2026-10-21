package com.feedback.manager.attachment

import com.feedback.model.attachment.*

import com.feedback.service.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.ContentVisibility
import com.feedback.security.WebPrincipal
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader

/** 页面日志预览最大字节数，防止大日志拖垮请求线程。 */
const val INLINE_LOG_PREVIEW_LIMIT_BYTES = 5L * 1024L * 1024L

/** 生成稳定的中文截断提示，明确实际展示上限。 */
internal fun inlineLogTruncationNotice(limitBytes: Long): ByteArray {
    val limitLabel = if (limitBytes % (1024L * 1024L) == 0L) {
        "${limitBytes / (1024L * 1024L)} MB"
    } else if (limitBytes % 1024L == 0L) {
        "${limitBytes / 1024L} KB"
    } else "$limitBytes 字节"
    return "\n\n===== 日志预览已截断 =====\n日志内容较大，当前仅展示前 $limitLabel，以避免页面长时间无响应。\n"
        .toByteArray(Charsets.UTF_8)
}

/** 将已授权日志转换为按角色脱敏、按字节有界的页面文本流。 */
fun SecureAttachmentContent.asInlineView(
    principal: WebPrincipal,
    previewLimitBytes: Long = INLINE_LOG_PREVIEW_LIMIT_BYTES
): SecureAttachmentContent {
    if (previewLimitBytes <= 0) {
        throw ClientException(ErrorCode.BAD_REQUEST, "INLINE_LOG_PREVIEW_LIMIT_INVALID")
    }
    val truncationNotice = inlineLogTruncationNotice(previewLimitBytes)
    val source = openStream()
    val archivePreview = InlineLogArchivePreview.render(source, filename, mimeType, previewLimitBytes, truncationNotice)
    val text = archivePreview?.let(::ByteArrayInputStream) ?: source
    val roleView = if (ContentVisibility.canViewRaw(principal)) text else PartiallyHiddenLogInputStream(text, principal)
    val preview = if (archivePreview == null) {
        BoundedLogPreviewInputStream(roleView, previewLimitBytes, truncationNotice)
    } else roleView
    return SecureAttachmentContent(AttachmentContentSource { preview }, "text/plain;charset=UTF-8", "$filename.txt", null)
}

/** 生成角色无关的有界日志明文，供后台加密缓存；调用方随后必须清零结果。 */
fun SecureAttachmentContent.renderRawLogPreview(previewLimitBytes: Long): ByteArray {
    if (previewLimitBytes <= 0) {
        throw ClientException(ErrorCode.BAD_REQUEST, "INLINE_LOG_PREVIEW_LIMIT_INVALID")
    }
    val truncationNotice = inlineLogTruncationNotice(previewLimitBytes)
    return openStream().use { source ->
        InlineLogArchivePreview.render(source, filename, mimeType, previewLimitBytes, truncationNotice)
            ?: BoundedLogPreviewInputStream(source, previewLimitBytes, truncationNotice).use { it.readAllBytes() }
    }
}

/** 有界日志预览流；达到上限后最多附加一次固定截断提示。 */
private class BoundedLogPreviewInputStream(
    private val input: InputStream,
    private val limitBytes: Long,
    private val truncationNotice: ByteArray
) : InputStream() {
    private var delivered = 0L
    private var markerIndex = 0
    private var checkedForTruncation = false
    private var truncated = false

    /** 读取单字节并复用有界批量读取逻辑。 */
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    /** 在上限内读取原内容，确认被截断后追加一次固定提示。 */
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (delivered < limitBytes) {
            val count = input.read(target, offset, minOf(length.toLong(), limitBytes - delivered).toInt())
            if (count < 0) {
                return -1
            }
            delivered += count
            return count
        }
        if (!checkedForTruncation) {
            truncated = input.read() >= 0
            checkedForTruncation = true
        }
        if (!truncated || markerIndex >= truncationNotice.size) {
            return -1
        }
        val count = minOf(length, truncationNotice.size - markerIndex)
        truncationNotice.copyInto(target, offset, markerIndex, markerIndex + count)
        markerIndex += count
        return count
    }

    /** 关闭底层日志流。 */
    override fun close() = input.close()
}

/** 按当前授权主体逐行隐藏日志中的受限字段，不缓存完整日志。 */
private class PartiallyHiddenLogInputStream(
    input: InputStream,
    private val principal: WebPrincipal
) : InputStream() {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private var pending = ByteArray(0)
    private var index = 0
    private var finished = false

    /** 读取单字节并复用按行脱敏的批量读取。 */
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) {
            -1
        } else {
            one[0].toInt() and 0xff
        }
    }

    /** 逐行应用内容可见性策略，不把未脱敏行越过输出边界。 */
    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        while (index >= pending.size && !finished) {
            val line = reader.readLine()
            if (line == null) {
                finished = true
                break
            }
            pending = (ContentVisibility.present("APPLICATION_LOG", line, principal) + "\n")
                .toByteArray(Charsets.UTF_8)
            index = 0
        }
        if (finished && index >= pending.size) {
            return -1
        }
        val count = minOf(length, pending.size - index)
        pending.copyInto(target, offset, index, index + count)
        index += count
        return count
    }

    /** 关闭字符读取器及其底层日志流。 */
    override fun close() = reader.close()
}
