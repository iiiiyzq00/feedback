package com.feedback.service.impl.sdk

import com.feedback.security.sdk.SdkAttachmentDecryptor
import com.feedback.strategy.sdk.SdkIngestPolicy
import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.dto.sdk.SdkAttachmentAck
import com.feedback.dto.sdk.SdkAttachmentMetadata
import com.feedback.model.sdk.SdkIngest
import com.feedback.model.po.attachment.FeedbackAttachmentPo
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import com.feedback.repository.sdk.SdkIngestRepository
import com.feedback.service.sdk.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

/**
 * 处理 SDK 加密附件与分块上传。
 *
 * 文件读取、哈希和解密可能耗时，因此这里不建立数据库事务。附件先完整校验并原子移动，随后由
 * Repository 执行单次数据库写入；若写入失败，会删除刚生成的目标文件，避免留下无主文件。
 */
@Service
class SdkAttachmentServiceImpl(
    private val ingestRepository: SdkIngestRepository,
    private val attachmentRepository: FeedbackAttachmentMutationRepository,
    private val objectMapper: ObjectMapper,
    private val decryptor: SdkAttachmentDecryptor,
    private val policy: SdkIngestPolicy,
    @Value("\${app.attachment.dir:data/attachments}") attachmentDir: String
) : SdkAttachmentService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val attachmentRoot = Path.of(attachmentDir).toAbsolutePath().normalize()
    private val attachmentLocks = Array(ATTACHMENT_LOCK_STRIPES) { Any() }

    /** 接收完整 SDK 附件，在事务外校验大小、摘要并解密落盘，再以短事务登记附件元数据。 */
    override fun ingestAttachment(
        receiptId: String,
        appKey: String,
        metadataJson: String,
        encryptedFile: MultipartFile
    ): SdkAttachmentAck {
        val ingest = ownedIngest(receiptId, appKey)
        val metadata = parseMetadata(metadataJson)
        validateMetadata(metadata, encryptedFile)
        return synchronized(attachmentLock(receiptId, metadata.clientAttachmentId)) {
            ingestAttachmentLocked(receiptId, ingest, metadata, encryptedFile)
        }
    }

    /** 同一附件的幂等查询、文件替换和数据库写入必须位于同一临界区。 */
    private fun ingestAttachmentLocked(
        receiptId: String,
        ingest: SdkIngest,
        metadata: SdkAttachmentMetadata,
        encryptedFile: MultipartFile
    ): SdkAttachmentAck {
        attachmentRepository.findSourceIdentity("SDK", metadata.clientAttachmentId)
            ?.let {
                return SdkAttachmentAck(
                    it.clientAttachmentId ?: internalFailure("SDK_ATTACHMENT_CLIENT_ID_MISSING"),
                    it.uploadStatus
                )
            }

        Files.createDirectories(attachmentRoot)
        val encryptedTemp = Files.createTempFile(attachmentRoot, "sdk-upload-", ".part")
        var target: Path? = null
        try {
            val encryptedHash = copyAndHash(encryptedFile.inputStream, encryptedTemp)
            if (!MessageDigest.isEqual(encryptedHash, metadata.sha256.hexToBytes())) {
                reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_HASH_MISMATCH")
            }

            val plaintext = decryptFba2(encryptedTemp, metadata)
            try {
                val targetDir = attachmentRoot.resolve("sdk").resolve(ingest.id.toString()).normalize()
                requireInside(targetDir, attachmentRoot, "SDK_ATTACHMENT_TARGET_OUTSIDE_ROOT")
                Files.createDirectories(targetDir)
                // 唯一文件名避免多实例并发上传时，一个失败请求误删另一个请求已经登记的文件。
                target = targetDir.resolve("${metadata.clientAttachmentId}-${UUID.randomUUID()}.bin").normalize()
                val resolvedTarget = target ?: internalFailure("SDK_ATTACHMENT_TARGET_MISSING")
                requireInside(resolvedTarget, targetDir, "SDK_ATTACHMENT_TARGET_OUTSIDE_DIRECTORY")
                moveReplacing(plaintext, resolvedTarget)
            } finally {
                Files.deleteIfExists(plaintext)
            }

            val storedPath = target ?: internalFailure("SDK_ATTACHMENT_TARGET_MISSING")
            val entity = try {
                attachmentRepository.create(
                    FeedbackAttachmentPo(
                        publicId = uuidBytes(UUID.randomUUID()),
                        sdkIngestId = ingest.id,
                        attachmentType = metadata.type,
                        mimeType = metadata.mime ?: "application/octet-stream",
                        fileSizeBytes = Files.size(storedPath),
                        fileSha256 = sha256(Files.readAllBytes(storedPath)),
                        storagePath = storedPath.toString(),
                        storageKeyId = null,
                        clientAttachmentId = metadata.clientAttachmentId,
                        width = metadata.width,
                        height = metadata.height,
                        durationSeconds = metadata.durationSeconds,
                        annotationDataJson = metadata.annotationJson?.let(::parseAnnotation)?.toString(),
                        uploadStatus = "VERIFIED",
                        integrityStatus = "VERIFIED",
                        decryptionStatus = "DECRYPTED",
                        retentionUntil = LocalDateTime.now().plusDays(policy.normalRetentionDays())
                    )
                )
            } catch (ex: Exception) {
                Files.deleteIfExists(storedPath)
                throw ex
            }

            log.info("SDK attachment accepted: receipt={} attachment={}", receiptId, entity.clientAttachmentId)
            return SdkAttachmentAck(
                entity.clientAttachmentId ?: internalFailure("SDK_ATTACHMENT_CLIENT_ID_MISSING"),
                entity.uploadStatus
            )
        } finally {
            Files.deleteIfExists(encryptedTemp)
        }
    }

    /** 接收单个 SDK 附件分片；校验分片身份、序号、大小和摘要，最后一片才触发有界组装。 */
    override fun ingestAttachmentChunk(
        receiptId: String,
        appKey: String,
        metadataJson: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSha256: String,
        chunk: MultipartFile
    ): SdkAttachmentAck {
        validateChunk(chunkIndex, totalChunks, chunkSha256, chunk)
        val metadata = parseMetadata(metadataJson)
        validateChunkMetadata(metadata)
        return synchronized(attachmentLock(receiptId, metadata.clientAttachmentId)) {
            assembleChunk(receiptId, appKey, metadataJson, metadata, chunkIndex, totalChunks, chunkSha256, chunk)
        }
    }

    /** 按固定序号组装全部分片并复核总摘要；缺失、重复、越界或乱序分片均失败关闭。 */
    private fun assembleChunk(
        receiptId: String,
        appKey: String,
        metadataJson: String,
        metadata: SdkAttachmentMetadata,
        chunkIndex: Int,
        totalChunks: Int,
        chunkSha256: String,
        chunk: MultipartFile
    ): SdkAttachmentAck {
        val ingest = ownedIngest(receiptId, appKey)
        val expectedChunks = ((metadata.sizeBytes + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES)
            .toInt()
            .coerceAtLeast(1)
        if (totalChunks != expectedChunks) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_CHUNK_COUNT_INVALID")
        }
        attachmentRepository.findSourceIdentity("SDK", metadata.clientAttachmentId)
            ?.let {
                return SdkAttachmentAck(
                    it.clientAttachmentId ?: internalFailure("SDK_ATTACHMENT_CLIENT_ID_MISSING"),
                    it.uploadStatus
                )
            }

        val chunkDir = attachmentRoot.resolve(ingest.id.toString())
            .resolve(".chunks")
            .resolve(metadata.clientAttachmentId)
            .normalize()
        requireInside(chunkDir, attachmentRoot, "SDK_ATTACHMENT_CHUNK_DIRECTORY_OUTSIDE_ROOT")
        Files.createDirectories(chunkDir)

        val bytes = chunk.bytes
        if (sha256(bytes).toHex() != chunkSha256) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_CHUNK_HASH_MISMATCH")
        }
        val chunkPath = chunkDir.resolve("$chunkIndex.part")
        writeChunkOnce(chunkDir, chunkPath, bytes, chunkSha256)
        if ((0 until totalChunks).any { !Files.exists(chunkDir.resolve("$it.part")) }) {
            return SdkAttachmentAck(metadata.clientAttachmentId, "PARTIAL")
        }

        val assembled = Files.createTempFile(attachmentRoot, "sdk-assembled-", ".enc")
        try {
            Files.newOutputStream(assembled).use { output ->
                (0 until totalChunks).forEach { index ->
                    Files.newInputStream(chunkDir.resolve("$index.part")).use { it.copyTo(output) }
                }
            }
            return ingestAttachment(
                receiptId,
                appKey,
                metadataJson,
                PathMultipartFile(assembled, metadata.mime)
            ).also {
                (0 until totalChunks).forEach { Files.deleteIfExists(chunkDir.resolve("$it.part")) }
                Files.deleteIfExists(chunkDir)
            }
        } finally {
            Files.deleteIfExists(assembled)
        }
    }

    /** 校验输入或不变量：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun validateChunk(index: Int, total: Int, expectedHash: String, chunk: MultipartFile) {
        if (total !in 1..MAX_CHUNKS || index !in 0 until total) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_CHUNK_INVALID")
        }
        if (!expectedHash.matches(LOWERCASE_SHA256_PATTERN)) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_CHUNK_HASH_INVALID")
        }
        if (chunk.size !in 1..MAX_CHUNK_BYTES) {
            reject(ErrorCode.SDK_CIPHER_TOO_LARGE, "SDK_ATTACHMENT_CHUNK_SIZE_INVALID")
        }
    }

    /** 构造附件内部失败异常；仅保留稳定错误码和异常类型，禁止携带密文或文件内容。 */
    private fun internalFailure(reasonCode: String): Nothing =
        throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)

    /** 校验输入或不变量：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun validateMetadata(metadata: SdkAttachmentMetadata, file: MultipartFile) {
        validateChunkMetadata(metadata)
        if (metadata.sizeBytes != file.size) {
            reject(ErrorCode.SDK_CIPHER_TOO_LARGE, "SDK_ATTACHMENT_SIZE_INVALID")
        }
    }

    /** 校验完整附件与分块上传共用的元数据声明，不把单个分块大小误当成附件总大小。 */
    private fun validateChunkMetadata(metadata: SdkAttachmentMetadata) {
        if (!metadata.clientAttachmentId.matches(ATTACHMENT_ID_PATTERN)) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_ID_INVALID")
        }
        if (metadata.type !in ALLOWED_ATTACHMENT_TYPES) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_TYPE_INVALID")
        }
        if (metadata.sizeBytes !in 1..policy.maxAttachmentBytes()) {
            reject(ErrorCode.SDK_CIPHER_TOO_LARGE, "SDK_ATTACHMENT_SIZE_INVALID")
        }
        if (!metadata.sha256.matches(SHA256_PATTERN)) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_HASH_INVALID")
        }
        if (metadata.algorithmVersion != FBA2_ALGORITHM || metadata.encryptedDataKey.isNullOrBlank()) {
            reject(ErrorCode.SDK_ATTACHMENT_KEY_REQUIRED, "SDK_ATTACHMENT_KEY_REQUIRED")
        }
    }

    /** 解码或解密受控输入：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun decryptFba2(encrypted: Path, metadata: SdkAttachmentMetadata): Path {
        val envelope = Files.readAllBytes(encrypted)
        try {
            if (envelope.size <= FBA2_OVERHEAD || String(envelope, 0, FBA2_MAGIC.length, Charsets.US_ASCII) != FBA2_MAGIC) {
                reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_ENVELOPE_INVALID")
            }
            val wrappedKey = runCatching { Base64.getDecoder().decode(metadata.encryptedDataKey) }
                .getOrElse { reject(ErrorCode.SDK_ATTACHMENT_KEY_INVALID, "SDK_ATTACHMENT_KEY_INVALID") }
            val plaintext = decryptor.decrypt(
                ciphertext = envelope.copyOfRange(FBA2_HEADER_BYTES, envelope.size - FBA2_TAG_BYTES),
                tag = envelope.copyOfRange(envelope.size - FBA2_TAG_BYTES, envelope.size),
                nonce = envelope.copyOfRange(FBA2_MAGIC.length, FBA2_HEADER_BYTES),
                wrappedKey = wrappedKey
            )
            val output = Files.createTempFile(encrypted.parent, "sdk-plain-", ".part")
            try {
                Files.write(output, plaintext)
                return output
            } finally {
                plaintext.fill(0)
            }
        } finally {
            envelope.fill(0)
        }
    }

    /** 按回执读取 SDK 信封并核对 appKey 所有权；记录不存在或跨应用访问统一失败关闭。 */
    private fun ownedIngest(receiptId: String, appKey: String): SdkIngest {
        val ingest = ingestRepository.findByReceiptId(decodeReceipt(receiptId))
            ?: reject(ErrorCode.SDK_RECEIPT_NOT_FOUND, "SDK_RECEIPT_NOT_FOUND")
        if (ingest.appId != appKey) {
            reject(ErrorCode.SDK_RECEIPT_FORBIDDEN, "SDK_RECEIPT_FORBIDDEN")
        }
        return ingest
    }

    /** 严格解码固定长度的 URL-safe Station 回执；非法编码不得触发数据库查询。 */
    private fun decodeReceipt(value: String): ByteArray {
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .recoverCatching { Base64.getDecoder().decode(value) }
            .getOrElse { reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_RECEIPT_INVALID") }
        if (decoded.size != RECEIPT_BYTES) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_RECEIPT_LENGTH_INVALID")
        }
        return decoded
    }

    /** 解析并校验外部输入：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun parseMetadata(json: String): SdkAttachmentMetadata =
        runCatching { objectMapper.readValue(json, SdkAttachmentMetadata::class.java) }
            .getOrElse { reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_METADATA_INVALID") }

    /** 解析并校验外部输入：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun parseAnnotation(value: String) = runCatching { objectMapper.readTree(value) }
        .getOrElse { reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_ANNOTATION_INVALID") }

    /** 流式复制附件并同步计算 SHA-256；累计字节超过上限时立即中止并清理临时文件。 */
    private fun copyAndHash(input: InputStream, target: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) {
                        break
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return digest.digest()
    }

    /** 在受控目录内原子替换目标文件；不支持原子移动时使用同文件系统安全回退。 */
    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 通过同目录临时文件发布完整分块，避免共享卷上的另一个进程观察到半写入文件。
     * 已存在的分块必须与声明哈希一致；不同内容不会静默覆盖。
     */
    private fun writeChunkOnce(
        chunkDir: Path,
        target: Path,
        bytes: ByteArray,
        expectedHash: String
    ) {
        if (Files.exists(target)) {
            verifyStoredChunk(target, expectedHash)
            return
        }
        val pending = Files.createTempFile(chunkDir, "chunk-", ".pending")
        try {
            Files.write(pending, bytes)
            try {
                Files.move(pending, target)
            } catch (_: FileAlreadyExistsException) {
                // 另一个实例已经发布同一分块，下面校验其内容后复用。
            }
            verifyStoredChunk(target, expectedHash)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    /** 重新读取已落盘分片并复核大小与摘要，用于判断同分片重放是否为同一命令。 */
    private fun verifyStoredChunk(path: Path, expectedHash: String) {
        if (!Files.isRegularFile(path) || sha256(Files.readAllBytes(path)).toHex() != expectedHash) {
            reject(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_CHUNK_CONFLICT")
        }
    }

    /** 校验输入或不变量：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun requireInside(path: Path, parent: Path, reason: String) {
        if (!path.startsWith(parent)) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, reason)
        }
    }

    /** 按附件业务键选择条带锁，串行化同一附件的分片组装而不阻塞无关附件。 */
    private fun attachmentLock(receiptId: String, clientAttachmentId: String): Any {
        val lockKey = "$receiptId:$clientAttachmentId"
        return attachmentLocks[Math.floorMod(lockKey.hashCode(), attachmentLocks.size)]
    }

    /** 严格解码定长十六进制摘要；非法字符或长度错误在访问文件系统前拒绝。 */
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    /** 把摘要字节编码为小写十六进制文本；仅用于指纹和日志安全标识。 */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    /** 计算稳定 SHA-256 摘要：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    /** 把 UUID 转换为数据库兼容字节：附件记录；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(RECEIPT_BYTES)
        .putLong(uuid.mostSignificantBits)
        .putLong(uuid.leastSignificantBits)
        .array()

    /** 抛出带稳定错误码的客户端异常；错误详情不得包含签名、密文或原始正文。 */
    private fun reject(errorCode: ErrorCode, reasonCode: String): Nothing =
        throw ClientException(errorCode, reasonCode)

/** 把已落盘的受控分片文件适配为 MultipartFile；路径已校验且不得来自未可信文件名。 */
private class PathMultipartFile(
        private val path: Path,
        private val mimeType: String?
    ) : MultipartFile {
        /** 为内部 MultipartFile 适配器返回稳定字段名，不使用用户输入生成名称。 */
        override fun getName() = "encrypted_file"
        /** 返回已校验的客户端附件名；该值不得直接参与文件系统路径拼接。 */
        override fun getOriginalFilename() = path.fileName.toString()
        /** 返回已校验的附件 MIME 类型，最终文件类型仍以受控检测结果为准。 */
        override fun getContentType() = mimeType
        /** 依据受控字节数组判断内部附件是否为空，不触发额外文件或网络 I/O。 */
        override fun isEmpty() = Files.size(path) == 0L
        /** 返回内部附件字节数，用于执行显式上传上限校验。 */
        override fun getSize() = Files.size(path)
        /** 返回内部附件内容供既有接口适配；调用方必须限制生命周期并避免写入日志。 */
        override fun getBytes() = Files.readAllBytes(path)
        /** 为内部附件创建只读输入流；流的生命周期由调用方在当前处理阶段关闭。 */
        override fun getInputStream(): InputStream = Files.newInputStream(path)
        /** 把内部附件写入已验证目标路径；目标路径必须先通过根目录边界校验。 */
        override fun transferTo(dest: File) {
            Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val RECEIPT_BYTES = 16
        const val MAX_CHUNKS = 10_000
        const val MAX_CHUNK_BYTES = 1_048_576L
        const val ATTACHMENT_LOCK_STRIPES = 64
        const val FBA2_MAGIC = "FBA2"
        const val FBA2_HEADER_BYTES = 16
        const val FBA2_TAG_BYTES = 16
        const val FBA2_OVERHEAD = FBA2_HEADER_BYTES + FBA2_TAG_BYTES
        const val FBA2_ALGORITHM = "FBA2-AES-256-GCM+RSA-OAEP-256"
        val ATTACHMENT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,50}$")
        val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")
        val LOWERCASE_SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val ALLOWED_ATTACHMENT_TYPES = setOf("SCREENSHOT", "ANNOTATED_SCREENSHOT", "SCREEN_RECORDING", "LOG")
    }
}
