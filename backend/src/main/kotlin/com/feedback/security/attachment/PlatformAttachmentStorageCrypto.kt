package com.feedback.security.attachment

import com.feedback.service.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 平台附件静态加密组件；密钥仅由配置派生，明文和密钥不得写入日志。 */
@Component
class PlatformAttachmentStorageCrypto(
    @Value("\${app.attachment.storage-encryption-secret:}") secret: String,
    @Value("\${app.attachment.storage-encryption-key-id:attachment-storage-v1}") val keyId: String
) {
    private val key = secret.takeIf { it.length >= 32 }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
    }
    /** 返回当前是否配置了可用密钥，不触发任何 I/O。 */
    fun available() = key != null

    /** 在事务外将明文文件流式加密到目标路径，调用方负责后续原子状态更新。 */
    fun encrypt(source: Path, target: Path) {
        val storageKey = key ?: unavailable()
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, nonce))
        }
        Files.newOutputStream(target).use { raw ->
            raw.write("FBS1".toByteArray(Charsets.US_ASCII)); raw.write(nonce)
            CipherOutputStream(raw, cipher).use { encrypted -> Files.newInputStream(source).use { it.copyTo(encrypted) } }
        }
    }

    /** 打开并校验平台密文封装，返回调用方负责关闭的解密流。 */
    fun open(path: Path): InputStream {
        val storageKey = key ?: unavailable()
        val raw = Files.newInputStream(path)
        try {
            val header = raw.readNBytes(16)
            ensure(header.size == 16 && String(header, 0, 4, Charsets.US_ASCII) == "FBS1", "ATTACHMENT_STORAGE_ENVELOPE_INVALID")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, header.copyOfRange(4, 16)))
            }
            return CipherInputStream(raw, cipher)
        } catch (error: Exception) { raw.close(); throw error }
    }

    /** 用与附件文件相同的带认证封装来加密一份小的有界预览。 */
    /** 加密有界内存明文并返回自描述封装；调用方应及时清零原数组。 */
    fun encryptBytes(plaintext: ByteArray): ByteArray {
        ensure(plaintext.size <= 1024 * 1024, "ATTACHMENT_PREVIEW_TOO_LARGE")
        val storageKey = key ?: unavailable()
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, nonce))
        }
        return ByteArrayOutputStream(plaintext.size + 32).use { out ->
            out.write("FBS1".toByteArray(Charsets.US_ASCII))
            out.write(nonce)
            out.write(cipher.doFinal(plaintext))
            out.toByteArray()
        }
    }

    /** 在认证完整封装之后，解密一份已做尺寸约束的数据库预览。 */
    /** 解密有界预览封装并校验魔数和尺寸。 */
    fun decryptBytes(envelope: ByteArray): ByteArray {
        ensure(envelope.size in 32..(1024 * 1024 + 32), "ATTACHMENT_PREVIEW_ENVELOPE_INVALID")
        ensure(String(envelope, 0, 4, Charsets.US_ASCII) == "FBS1", "ATTACHMENT_PREVIEW_ENVELOPE_INVALID")
        val storageKey = key ?: unavailable()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(storageKey, "AES"), GCMParameterSpec(128, envelope.copyOfRange(4, 16)))
            doFinal(envelope, 16, envelope.size - 16)
        }
    }

    /**
     * 为对象迁移解密一个受大小上限约束的附件。JDK 的 GCM 流会在认证标签校验前缓存明文，大文件可能
     * 长时间不产出首字节并触发 S3 请求超时；迁移器因此按单文件在内存中完成认证，调用方必须及时覆零。
     */
    fun decryptForMigration(path: Path, maximumPlaintextBytes: Long): ByteArray {
        ensure(maximumPlaintextBytes > 0, "ATTACHMENT_MIGRATION_MEMORY_LIMIT_INVALID")
        val encryptedSize = Files.size(path)
        ensure(encryptedSize >= 32 && encryptedSize - 32 <= maximumPlaintextBytes, "ATTACHMENT_MIGRATION_FILE_EXCEEDS_MEMORY_LIMIT")
        val envelope = Files.readAllBytes(path)
        try {
            ensure(envelope.size >= 32 && String(envelope, 0, 4, Charsets.US_ASCII) == "FBS1", "ATTACHMENT_STORAGE_ENVELOPE_INVALID")
            val storageKey = key ?: unavailable()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(storageKey, "AES"),
                    GCMParameterSpec(128, envelope.copyOfRange(4, 16))
                )
            }
            return cipher.doFinal(envelope, 16, envelope.size - 16)
        } finally {
            envelope.fill(0)
        }
    }

    /** 构造稳定密钥不可用错误并失败关闭。 */
    private fun unavailable(): Nothing =
        throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, "ATTACHMENT_STORAGE_KEY_UNAVAILABLE")

    /** 统一验证密文和大小不变量，失败时不返回部分明文。 */
    private fun ensure(valid: Boolean, reasonCode: String) {
        if (!valid) {
            throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)
        }
    }
}
