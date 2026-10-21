package com.feedback.service.imports

import com.feedback.config.RuntimeConfigService
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import com.feedback.repository.ImportAttachmentTaskRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import com.feedback.security.sdk.TransportKeyService
import com.feedback.service.masking.EnvelopeCrypto
import java.util.Base64

data class ImportAttachmentUploadResult(val taskId: Long, val status: String, val securityStatus: String)

@Service
class ImportAttachmentUploadService(
    private val committer: ImportAttachmentCommitter,
    private val runtimeConfig: RuntimeConfigService? = null,
    private val transportKeys: TransportKeyService? = null,
    private val envelopeCrypto: EnvelopeCrypto? = null,
    @Value("\${app.attachment.dir:data/attachments}") attachmentDir: String
) {
    private val root = Path.of(attachmentDir).toAbsolutePath().normalize()

    fun upload(taskId: Long, declaredSha256: String, mimeType: String, encryptedDataKey: String,
               algorithmVersion: String, encryptedFile: MultipartFile): ImportAttachmentUploadResult {
        val task = committer.findTask(taskId)
        if (task.finalStatus == "AVAILABLE") return ImportAttachmentUploadResult(task.id, "AVAILABLE", "QUARANTINED")
        ensureImport(task.taskStatus == "PENDING" && task.urlExpiresAt.isAfter(LocalDateTime.now())) { "IMPORT_ATTACHMENT_TASK_CLOSED" }
        ensureImport(mimeType in MIME_TYPES.getValue(task.attachmentType)) { "IMPORT_ATTACHMENT_MIME_INVALID" }
        ensureImport(declaredSha256.matches(Regex("^[0-9a-fA-F]{64}$"))) { "ATTACHMENT_SHA256_INVALID" }
        val maxBytes = (runtimeConfig?.int("attachment.max-mb", 50) ?: 50) * 1024L * 1024L
        ensureImport(encryptedFile.size in 1..maxBytes) { "IMPORT_ATTACHMENT_SIZE_INVALID" }
        ensureImport(algorithmVersion == "FBI2-AES-256-GCM+RSA-OAEP-256") { "IMPORT_ATTACHMENT_ALGORITHM_UNSUPPORTED" }
        task.expectedSizeBytes?.let { ensureImport(it == encryptedFile.size) { "IMPORT_ATTACHMENT_EXPECTED_SIZE_MISMATCH" } }
        val expected = declaredSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        task.expectedSha256?.let { ensureImport(MessageDigest.isEqual(it, expected)) { "IMPORT_ATTACHMENT_EXPECTED_HASH_MISMATCH" } }
        val dir = root.resolve("import").resolve(task.id.toString()).normalize()
        ensureImport(dir.startsWith(root))
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "upload-", ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            encryptedFile.inputStream.use { input ->
                Files.newOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                    buffer.fill(0)
                }
            }
            val actual = digest.digest()
            ensureImport(MessageDigest.isEqual(actual, expected)) { "IMPORT_ATTACHMENT_HASH_MISMATCH" }
            val target = dir.resolve("payload.bin").normalize()
            ensureImport(target.startsWith(dir))
            val plain = decryptFbi2(temp, encryptedDataKey)
            Files.move(plain, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val plaintextHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target))
            return try {
                committer.commit(task.id, target, mimeType, plaintextHash)
            } catch (error: Throwable) {
                Files.deleteIfExists(target)
                throw error
            }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun decryptFbi2(path: Path, encodedKey: String): Path {
        val transport = transportKeys ?: importFailed("IMPORT_ATTACHMENT_DECRYPTION_UNAVAILABLE")
        val crypto = envelopeCrypto ?: importFailed("IMPORT_ATTACHMENT_DECRYPTION_UNAVAILABLE")
        val bytes = Files.readAllBytes(path)
        ensureImport(bytes.size > 32 && String(bytes, 0, 4, Charsets.US_ASCII) == "FBI2") { "IMPORT_ATTACHMENT_ENVELOPE_INVALID" }
        val wrapped = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrElse { importInvalid("IMPORT_ATTACHMENT_KEY_INVALID") }
        val key = transport.unwrapDataKey(wrapped, "sdk-transport-v2")
        val plain = try { crypto.decrypt(bytes.copyOfRange(16, bytes.size - 16), bytes.copyOfRange(bytes.size - 16, bytes.size), bytes.copyOfRange(4, 16), key) }
        finally { key.fill(0); wrapped.fill(0); bytes.fill(0) }
        val output = Files.createTempFile(path.parent, "plain-", ".part")
        try { Files.write(output, plain); return output } finally { plain.fill(0) }
    }

    companion object {
        private val MIME_TYPES = mapOf(
            "IMAGE" to setOf("image/jpeg", "image/png", "image/webp"),
            "VIDEO" to setOf("video/mp4", "video/webm")
        )
    }
}

/**
 * 把数据库事务限制在最后的比较并更新这一步。多部分拷贝、加解密与文件哈希
 * 都在本组件被调用之前完成，因此慢磁盘或慢客户端不会占住数据库连接。
 */
@Component
class ImportAttachmentCommitter(
    private val tasks: ImportAttachmentTaskRepository,
    private val attachments: FeedbackAttachmentMutationRepository,
    private val transactions: TransactionTemplate
) {
    fun findTask(taskId: Long) = tasks.findById(taskId)
        .orElseThrow { com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.NOT_FOUND,"IMPORT_ATTACHMENT_TASK_NOT_FOUND") }

    fun commit(taskId: Long, target: Path, mimeType: String, plaintextHash: ByteArray): ImportAttachmentUploadResult =
        importRequired(transactions.execute {
            val task = findTask(taskId)
            if (task.finalStatus == "AVAILABLE") {
                return@execute ImportAttachmentUploadResult(task.id, "AVAILABLE", "QUARANTINED")
            }
            ensureImport(task.taskStatus == "PENDING" && task.urlExpiresAt.isAfter(LocalDateTime.now())) {
                "IMPORT_ATTACHMENT_TASK_CLOSED"
            }
            val attachment = attachments.find(importRequired(task.feedbackAttachmentId))
                ?: importInvalid("ATTACHMENT_NOT_FOUND")
            attachments.updateStored(attachment.copy(
                mimeType = mimeType,
                fileSizeBytes = Files.size(target),
                fileSha256 = plaintextHash,
                storagePath = target.toString(),
                storageKeyId = null,
                uploadStatus = "VERIFIED",
                integrityStatus = "VERIFIED",
                decryptionStatus = "DECRYPTED",
                securityStatus = "QUARANTINED"
            ))
            tasks.save(task.copy(taskStatus = "UPLOADED", finalStatus = "AVAILABLE", updatedAt = LocalDateTime.now()))
            ImportAttachmentUploadResult(task.id, "AVAILABLE", "QUARANTINED")
        })
}
