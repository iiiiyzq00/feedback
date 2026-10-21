package com.feedback.service.imports

import com.feedback.repository.imports.ImportChunkRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

data class ImportChunkStatus(
    val fileId: String,
    val receivedChunks: List<Int>,
    val missingChunks: List<Int>,
    val isComplete: Boolean,
    val expiresAt: LocalDateTime,
    val uploadStatus:String = "UPLOADING",
    val batchId:String? = null
)

@Service
class ImportChunkUploadService(
    private val repository: ImportChunkRepository,
    private val crypto: ImportRawCrypto,
    private val imports: ImportBatchService
) {
    @Transactional
    fun put(fileIdInput: String, channelInput: String, chunkIndex: Int, totalChunks: Int,
            chunkSha256: String, data: MultipartFile): ImportChunkStatus {
        val fileId = UUID.fromString(fileIdInput).toString()
        val channel = channelInput.trim().uppercase().also { ensureImport(it in CHANNELS) { "IMPORT_CHANNEL_INVALID" } }
        ensureImport(totalChunks in 1..MAX_CHUNKS && chunkIndex in 0 until totalChunks) { "IMPORT_CHUNK_INDEX_INVALID" }
        ensureImport(data.size in 1..MAX_CHUNK_BYTES) { "IMPORT_CHUNK_SIZE_INVALID" }
        val expected = decodeSha256(chunkSha256)
        val bytes = data.bytes
        try {
        ensureImport(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(expected)) { "IMPORT_CHUNK_HASH_MISMATCH" }
        repository.ensureSession(fileId, channel, totalChunks, LocalDateTime.now())
        val session = importRequired(repository.session(fileId)) { "IMPORT_CHUNK_SESSION_NOT_FOUND" }
        ensureImport(session.sourceChannel == channel && session.totalChunks == totalChunks) {
            "IMPORT_CHUNK_SESSION_CONFLICT"
        }
        ensureImport(session.uploadStatus == "UPLOADING" && session.expiresAt.isAfter(LocalDateTime.now())) {
            "IMPORT_CHUNK_SESSION_CLOSED"
        }
        val sessionId = session.id
        val existing = repository.chunk(sessionId, chunkIndex)
        if (existing != null) {
            ensureImport(existing.chunkSha256.contentEquals(expected)) { "IMPORT_CHUNK_INDEX_CONFLICT" }
        } else {
            val encrypted = crypto.encrypt(bytes)
            repository.addChunk(sessionId, chunkIndex, expected, bytes.size, encrypted.ciphertext,
                encrypted.nonce, encrypted.tag, crypto.keyId, LocalDateTime.now())
        }
        return status(fileId)
        } finally {
            bytes.fill(0)
        }
    }

    fun channelOf(fileIdInput:String):String {
        val fileId=UUID.fromString(fileIdInput).toString()
        return repository.session(fileId)?.sourceChannel ?: importAbort("IMPORT_CHUNK_SESSION_NOT_FOUND")
    }

    fun status(fileIdInput: String): ImportChunkStatus {
        val fileId = UUID.fromString(fileIdInput).toString()
        val session = importRequired(repository.session(fileId)) { "IMPORT_CHUNK_SESSION_NOT_FOUND" }
        val total = session.totalChunks
        val received = repository.chunks(session.id).map { it.chunkIndex }
        return ImportChunkStatus(fileId, received, (0 until total).filterNot(received::contains), received.size == total,
            session.expiresAt, session.uploadStatus,
            session.qualityErrorSummary?.takeIf { session.uploadStatus == "COMPLETED" })
    }

    @Transactional
    fun complete(fileIdInput:String,filename:String,fullSha256:String,sheetName:String?,dataDateFrom:java.time.LocalDate?=null,dataDateTo:java.time.LocalDate?=null):ImportChunkStatus {
        val fileId = UUID.fromString(fileIdInput).toString()
        ensureImport(filename.matches(Regex("^[^/\\\\]{1,200}\\.(csv|xlsx|xls)$", RegexOption.IGNORE_CASE))) { "IMPORT_FILENAME_INVALID" }
        val expected = decodeSha256(fullSha256)
        val session = importRequired(repository.lockSession(fileId)) { "IMPORT_CHUNK_SESSION_NOT_FOUND" }
        ensureImport(session.uploadStatus == "UPLOADING") { "IMPORT_CHUNK_SESSION_CLOSED" }
        ensureImport(session.totalSizeBytes in 1..MAX_FILE_BYTES) { "IMPORT_ASSEMBLED_FILE_SIZE_INVALID" }
        val chunks = repository.chunks(session.id)
        ensureImport(chunks.size == session.totalChunks && chunks.indices.all { chunks[it].chunkIndex == it }) {
            "IMPORT_CHUNKS_INCOMPLETE"
        }
        repository.markAssembling(session.id, filename, expected, sheetName,
            listOf(dataDateFrom?.toString().orEmpty(),dataDateTo?.toString().orEmpty()).joinToString("|"), LocalDateTime.now())
        return status(fileId)
    }

    @Transactional
    fun processOneReady():Boolean {
        val session=repository.readySession()?:return false
        val chunks=repository.chunks(session.id)
        ensureImport(chunks.size==session.totalChunks){"IMPORT_CHUNKS_INCOMPLETE"}
        val assembled = ByteArrayOutputStream(session.totalSizeBytes.toInt())
        chunks.forEach { chunk ->
            val plain = crypto.decrypt(chunk.chunkCiphertext, chunk.chunkTag, chunk.chunkNonce)
            assembled.write(plain); plain.fill(0)
        }
        val bytes=assembled.toByteArray()
        try {
            ensureImport(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(importRequired(session.headerHash))) { "IMPORT_FILE_HASH_MISMATCH" }
            val dates=session.qualitySuggestion?.split('|').orEmpty()
            val result=imports.upload(InMemoryMultipartFile(importRequired(session.sheetName),bytes),session.sourceChannel,
                session.qualityErrorSummary,dates.getOrNull(0)?.takeIf(String::isNotBlank)?.let(java.time.LocalDate::parse),
                dates.getOrNull(1)?.takeIf(String::isNotBlank)?.let(java.time.LocalDate::parse))
            repository.complete(session.id, result.batchId, LocalDateTime.now())
            return true
        } catch(error:Exception) {
            repository.fail(session.id, error.javaClass.simpleName, LocalDateTime.now())
            return true
        } finally {
            bytes.fill(0)
        }
    }

    private fun decodeSha256(value: String): ByteArray {
        ensureImport(value.matches(Regex("^[0-9a-fA-F]{64}$"))) { "SHA256_INVALID" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        private val CHANNELS = setOf("SDK", "JIRA", "QUALITY", "REDBLACK")
        private const val MAX_CHUNKS = 1_000
        private const val MAX_CHUNK_BYTES = 5L * 1024 * 1024
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    }
}

private class InMemoryMultipartFile(private val filename: String, private val value: ByteArray) : MultipartFile {
    override fun getName() = "file"
    override fun getOriginalFilename() = filename
    override fun getContentType() = "application/octet-stream"
    override fun isEmpty() = value.isEmpty()
    override fun getSize() = value.size.toLong()
    override fun getBytes() = value
    override fun getInputStream(): InputStream = ByteArrayInputStream(value)
    override fun transferTo(dest: java.io.File) = dest.writeBytes(value)
}
