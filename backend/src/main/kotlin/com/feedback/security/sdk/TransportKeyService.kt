package com.feedback.security.sdk

import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * SDK 传输加密密钥服务
 *
 * - 加载服务端 RSA 私钥（解封装客户端发来的 dataKey）
 * - 服务端解密 dataKey 后立即用 KMS 密钥重封装（生产环境）
 * - 开发环境直接用私钥解密
 */
@Service
class TransportKeyService(
    @Value("\${app.sdk.transport-private-key-path:}") private val privateKeyPath: String,
    @Value("\${app.sdk.transport-private-key-paths:}") private val configuredPaths: String,
    @Value("\${app.sdk.transport-active-key-id:sdk-transport-v2}") val activeKeyId: String,
    @Value("\${app.sdk.transport-public-key-path:}") val activePublicKeyPath: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var privateKeys: Map<String, java.security.PrivateKey>? = null

    @Synchronized
    /** 从受控配置加载传输私钥目录；校验 active key 存在且不接受重复 keyId。 */
    fun loadKeys(): Map<String, java.security.PrivateKey> {
        privateKeys?.let { return it }
        val paths = configuredPaths.split(',').map(String::trim).filter(String::isNotBlank).associate { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size != 2 || parts.any(String::isBlank)) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "SDK_TRANSPORT_PRIVATE_KEY_PATHS_INVALID")
            }
            parts[0] to parts[1]
        }.toMutableMap()
        if (paths.isEmpty() && privateKeyPath.isNotBlank()) {
            paths[activeKeyId] = privateKeyPath
        }
        if (paths.isEmpty()) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "SDK_TRANSPORT_PRIVATE_KEY_NOT_CONFIGURED")
        }
        val loaded = paths.mapValues { (_, path) -> loadPrivateKey(Files.readString(Path.of(path))) }
        if (activeKeyId !in loaded) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "SDK_TRANSPORT_ACTIVE_KEY_NOT_CONFIGURED")
        }
        this.privateKeys = loaded
        log.info("Transport private keys loaded: keyIds={} active={}", loaded.keys.sorted(), activeKeyId)
        return loaded
    }

    /**
     * RSA-OAEP-256 解封装 dataKey
     * 客户端用公钥封装 → 服务端用私钥解开
     */
    fun unwrapDataKey(encryptedDataKey: ByteArray, keyId: String = activeKeyId): ByteArray {
        val key = loadKeys()[keyId]
            ?: throw ClientException(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_TRANSPORT_KEY_ID_UNKNOWN")
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        return cipher.doFinal(encryptedDataKey)
    }

    /** 读取并解析 PKCS#8 私钥；不记录路径内容、密钥文本或解析后的密钥材料。 */
    private fun loadPrivateKey(pem: String): java.security.PrivateKey {
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = java.util.Base64.getDecoder().decode(base64)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }
}
