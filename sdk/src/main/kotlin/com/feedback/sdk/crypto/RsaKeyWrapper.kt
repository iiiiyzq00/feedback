package com.feedback.sdk.crypto

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * RSA-OAEP-256 密钥封装
 *
 * SDK 端持有服务端公钥 → 封装 AES dataKey
 * 服务端持有私钥 → 解封装
 *
 * 安全保证：即使请求被截获，没有服务端私钥也无法解开 dataKey
 */
object RsaKeyWrapper {

    private const val RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    /** 内嵌的服务端公钥（生产环境从动态配置拉取，支持轮换） */
    val DEFAULT_PUBLIC_KEY_PEM = """
-----BEGIN PUBLIC KEY-----
MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAwmx5Y13dSsvcNO/ap2tZmoy8rs9FcOny6EpjQe7TjXpR/0E4BFIPtFJXA/cq5pThheew6bmMTYX943+NZd4+1YkKlEDgLEKRLd6G85bzCxpdaNyXAUXR+xYahb9XghGPk+grwgZf95KYAET7fBJ5lr27M508N3MsDvizdh0lJJU1iG9a7Yk5pGzqtkcXq+vuwwbjrg0oKt//ThK4rzpoia4JSwkJWeK42L0Q4XAb2xaoIfvs4juVWWseAF6LZAarKhHtuE/X+yw7ld7KU/Slfy73U7cTQRiGSc6BdKSBz8vSmlxbeOA7zmmaCnB9fMejkJMU2uVbwvZf92/9XgqWoVQxtrAtSD0wWbE8Gms24Q5tXrIMizLWuwMtpoS4KxF+ab3Cu2K3xpCKau9ew57Wrlx6brs0+PqW63QhSSpNymMyZC213EugipxLnRTpFcHW9KqIoUPCj3DPuPxYO5T2yOwS4XrKuMnnSGpBDhLJGWXQy0hqLxRzqm/GBnm2+0xSJ3f3y+GRdyedsnTXciFjMBdA6WcrQJGj5/hY2znKiH0qIRx3fog0pdu3iyX9x+MQRa3bG/eoGoD753I+zAvVTFneInQkur79HC0fPUdEJuHkFP2fkcBwpND5J1ebnoJyx/8p/Wukd3klcJnPYgUkYb881FwupX6liZLBTT9Y2xECAwEAAQ==
-----END PUBLIC KEY-----
    """.trimIndent()

    data class WrappedDataKey(val keyId: String, val bytes: ByteArray)

    private data class KeyMaterial(val keyId: String, val publicKey: java.security.PublicKey)

    @Volatile private var keyMaterial: KeyMaterial? = null

    /** 加载公钥（SDK 初始化时调用，也可后续从服务端配置拉取） */
    fun loadPublicKey(pem: String = DEFAULT_PUBLIC_KEY_PEM, keyId: String = "sdk-transport-v2") {
        require(keyId.isNotBlank()) { "transport key id must not be blank" }
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.decode(base64, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(keyBytes)
        keyMaterial = KeyMaterial(keyId, KeyFactory.getInstance("RSA").generatePublic(keySpec))
    }

    /**
     * RSA-OAEP-256 封装 dataKey
     *
     * @param dataKey 随机 AES-256 密钥（32 bytes）
     * @return RSA 加密后的密文（512 bytes for RSA-4096）
     */
    fun wrapDataKey(dataKey: ByteArray): ByteArray {
        return wrapDataKeyWithId(dataKey).bytes
    }

    /** 封装密钥，并从同一次原子密钥快照里返回它的标识符。 */
    fun wrapDataKeyWithId(dataKey: ByteArray): WrappedDataKey {
        val material = keyMaterial ?: throw IllegalStateException(
            "RSA public key not loaded. Call RsaKeyWrapper.loadPublicKey() or FeedbackSDK.init() first."
        )
        val cipher = Cipher.getInstance(RSA_ALGORITHM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            material.publicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        return WrappedDataKey(material.keyId, cipher.doFinal(dataKey))
    }
}
