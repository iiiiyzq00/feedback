package com.feedback.service.attachment.storage

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.Properties
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * 仅供纯单元测试直接实例化的临时适配器。
 *
 * 它不受 Spring 管理，也不接受稳定本地目录。每个实例创建独立临时根目录，并在 `close` 时递归销毁。
 */
class LocalObjectStorageTestAdapter(
    private val bucket: String
) : AttachmentObjectStorage, AutoCloseable {
    val temporaryRoot: Path = Files.createTempDirectory("attachment-object-storage-test-")
    private val bucketRoot = temporaryRoot.resolve(bucket).normalize()

    init {
        require(bucket.isNotBlank()) { "ATTACHMENT_OBJECT_BUCKET_BLANK" }
        Files.createDirectories(bucketRoot)
    }

    override fun put(command: PutAttachmentObjectCommand): StoredAttachmentObject {
        val target = resolve(command.key)
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".object-", ".part")
        try {
            temporary.outputStream().use { output -> command.content.copyTo(output) }
            check(Files.size(temporary) == command.contentLength) { "ATTACHMENT_OBJECT_CONTENT_LENGTH_MISMATCH" }
            runCatching {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            writeMetadata(target, command.contentType)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return StoredAttachmentObject(AttachmentStorageBackend.S3_COMPATIBLE, bucket, command.key, null, null)
    }

    override fun head(key: AttachmentObjectKey): AttachmentObjectMetadata? {
        val target = resolve(key)
        if (!Files.isRegularFile(target)) return null
        return metadata(key, target)
    }

    override fun read(key: AttachmentObjectKey): AttachmentObjectRead {
        val target = resolve(key)
        check(Files.isRegularFile(target)) { "ATTACHMENT_OBJECT_NOT_FOUND" }
        return AttachmentObjectRead(metadata(key, target), target.inputStream())
    }

    override fun delete(command: DeleteAttachmentObjectCommand) {
        val target = resolve(command.key)
        Files.deleteIfExists(target)
        Files.deleteIfExists(metadataPath(target))
    }

    override fun close() {
        if (!Files.exists(temporaryRoot)) return
        Files.walk(temporaryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun resolve(key: AttachmentObjectKey): Path {
        val resolved = bucketRoot.resolve(key.value).normalize()
        check(resolved.startsWith(bucketRoot)) { "ATTACHMENT_OBJECT_KEY_ESCAPES_ROOT" }
        return resolved
    }

    private fun metadata(key: AttachmentObjectKey, target: Path) = AttachmentObjectMetadata(
        backend = AttachmentStorageBackend.S3_COMPATIBLE,
        bucket = bucket,
        key = key,
        version = null,
        etag = null,
        contentLength = Files.size(target),
        contentType = readContentType(target),
        lastModified = Instant.ofEpochMilli(Files.getLastModifiedTime(target).toMillis())
    )

    private fun writeMetadata(target: Path, contentType: String) {
        Properties().apply { setProperty("contentType", contentType) }
            .store(metadataPath(target).outputStream(), "local object metadata")
    }

    private fun readContentType(target: Path): String? {
        val path = metadataPath(target)
        if (!Files.isRegularFile(path)) return Files.probeContentType(target)
        return Properties().apply { path.inputStream().use(::load) }.getProperty("contentType")
    }

    private fun metadataPath(target: Path): Path = target.resolveSibling("${target.fileName}.metadata")
}
