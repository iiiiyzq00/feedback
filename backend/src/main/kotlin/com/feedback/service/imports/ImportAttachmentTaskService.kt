package com.feedback.service.imports

import com.feedback.model.po.attachment.FeedbackAttachmentPo
import com.feedback.model.imports.ImportAttachmentTask
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import com.feedback.repository.ImportAttachmentTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.IDN
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

@Service
class ImportAttachmentTaskService(
    private val tasks: ImportAttachmentTaskRepository,
    private val attachments: FeedbackAttachmentMutationRepository,
    private val crypto: ImportRawCrypto
) {
    @Transactional
    fun create(rowId: Long, sourceId: Long, rawUrls: String, expectedHash: String?, expectedSize: String?): Int {
        val urls = rawUrls.split(';', '\n').map(String::trim).filter(String::isNotBlank).distinct()
        ensureImport(urls.size in 1..10) { "IMPORT_ATTACHMENT_URL_COUNT_INVALID" }
        ensureImport(urls.size == 1 || (expectedHash.isNullOrBlank() && expectedSize.isNullOrBlank())) {
            "IMPORT_ATTACHMENT_MANIFEST_AMBIGUOUS"
        }
        val parsedExpectedHash = expectedHash?.takeIf(String::isNotBlank)?.let(::decodeSha256)
        val parsedExpectedSize = expectedSize?.toLongOrNull()?.also { ensureImport(it in 1..MAX_ATTACHMENT_BYTES) }
        return urls.count { raw ->
            val safe = validateReference(raw)
            val hash = sha256(safe.canonical.toByteArray())
            if (tasks.existsByImportRowIdAndSourceUrlHash(rowId, hash)) return@count false
            val urlBytes = safe.canonical.toByteArray()
            val encrypted = try { crypto.encrypt(urlBytes) } finally { urlBytes.fill(0) }
            val attachment = attachments.create(FeedbackAttachmentPo(
                publicId = uuidBytes(UUID.randomUUID()), sourceId = sourceId, attachmentType = safe.type,
                mimeType = null, storageKeyId = null, uploadStatus = "PENDING", securityStatus = "QUARANTINED",
                sourceKind = "IMPORT", retentionUntil = LocalDateTime.now().plusDays(90)
            ))
            tasks.save(ImportAttachmentTask(
                importRowId = rowId, feedbackAttachmentId = attachment.id,
                sourceUrlCiphertext = encrypted.ciphertext, sourceUrlNonce = encrypted.nonce,
                sourceUrlTag = encrypted.tag, sourceUrlKeyId = crypto.keyId,
                urlExpiresAt = LocalDateTime.now().plusHours(24), sourceUrlHash = hash,
                urlDomain = safe.domain, attachmentType = safe.type,
                expectedSha256 = parsedExpectedHash, expectedSizeBytes = parsedExpectedSize
            ))
            true
        }
    }

    fun validateReference(raw: String): SafeAttachmentUrl {
        ensureImport(raw.length <= 2_000) { "ATTACHMENT_URL_TOO_LONG" }
        val uri = runCatching { URI(raw) }.getOrElse { importInvalid("ATTACHMENT_URL_INVALID") }
        ensureImport(uri.scheme.equals("https", true)) { "ATTACHMENT_URL_HTTPS_REQUIRED" }
        ensureImport(uri.userInfo == null && uri.fragment == null) { "ATTACHMENT_URL_CREDENTIAL_OR_FRAGMENT_FORBIDDEN" }
        val host = IDN.toASCII(importRequired(uri.host) { "ATTACHMENT_URL_HOST_REQUIRED" }).lowercase()
        ensureImport(host != "localhost" && !host.endsWith(".local") && !isIpLiteral(host)) { "ATTACHMENT_URL_HOST_FORBIDDEN" }
        val path = uri.rawPath.orEmpty()
        val type = when {
            IMAGE_EXTENSIONS.any { path.lowercase().endsWith(it) } -> "IMAGE"
            VIDEO_EXTENSIONS.any { path.lowercase().endsWith(it) } -> "VIDEO"
            else -> importInvalid("ATTACHMENT_URL_TYPE_UNSUPPORTED")
        }
        val canonical = URI("https", null, host, uri.port.takeIf { it != -1 } ?: -1, path, uri.rawQuery, null).toASCIIString()
        return SafeAttachmentUrl(canonical, host, type)
    }

    private fun isIpLiteral(host: String): Boolean = host.contains(':') || host.matches(Regex("^[0-9.]+$"))
    private fun decodeSha256(value: String): ByteArray {
        ensureImport(value.matches(Regex("^[0-9a-fA-F]{64}$"))) { "ATTACHMENT_SHA256_INVALID" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    private fun uuidBytes(uuid: UUID) = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    companion object {
        private const val MAX_ATTACHMENT_BYTES = 200L * 1024 * 1024
        private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".webp")
        private val VIDEO_EXTENSIONS = setOf(".mp4", ".webm")
    }
}

data class SafeAttachmentUrl(val canonical: String, val domain: String, val type: String)
