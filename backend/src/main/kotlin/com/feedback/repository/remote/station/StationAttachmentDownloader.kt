package com.feedback.repository.remote.station

import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.feedback.config.RuntimeConfigService
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime

/** Station Online 附件下载器；只访问校验后的公网 HTTPS 地址，禁止重定向并将文件写入隔离目录。 */
@Service
class StationAttachmentDownloader(
    private val runtimeConfig: RuntimeConfigService? = null,
    @Value("\${app.attachment.dir:data/attachments}") attachmentDir: String,
    @Value("\${app.station-feedback.attachment-timeout-seconds:10}") timeoutSeconds: Long
) {
    private val root = Path.of(attachmentDir).toAbsolutePath().normalize()
    private val client = OkHttpClient.Builder()
        .dns(PublicAddressDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(Duration.ofSeconds(timeoutSeconds.coerceIn(1, 30)))
        .readTimeout(Duration.ofSeconds(timeoutSeconds.coerceIn(1, 30)))
        .callTimeout(Duration.ofSeconds(timeoutSeconds.coerceIn(1, 30)))
        .build()

    /** 在事务外完成输入规范化、加密或文件准备，返回可由短事务安全提交的不可变结果。 */
    fun prepare(sourceId: Long, url: String, ordinal: Int): PreparedStationAttachment {
        if (sourceId <= 0 || ordinal !in 0..8) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_IDENTITY_INVALID")
        }
        val uri = validateUrl(url)
        val sourceAttachmentId = sourceIdentity(sourceId, ordinal, uri)
        val maxBytes = (runtimeConfig?.int("attachment.max-mb", 50) ?: 50) * 1024L * 1024L
        val dir = root.resolve("station").resolve(sourceId.toString()).normalize()
        if (!dir.startsWith(root)) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_PATH_INVALID")
        }
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "download-", ".part")
        try {
            val request = Request.Builder().url(uri.toASCIIString()).get().build()
            try {
                client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RemoteException(
                        ErrorCode.STATION_ATTACHMENT_UNAVAILABLE,
                        "STATION_ATTACHMENT_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                }
                val body = response.body
                    ?: throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_EMPTY")
                val declaredLength = body.contentLength()
                if (declaredLength != -1L && declaredLength !in 1..maxBytes) {
                    throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_SIZE_INVALID")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                body.byteStream().use { input ->
                    Files.newOutputStream(temp).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            total += read
                            if (total > maxBytes) {
                                throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_TOO_LARGE")
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        buffer.fill(0)
                    }
                }
                if (total <= 0) {
                    throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_EMPTY")
                }
                val detected = detectMime(temp)
                val declared = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (declared != null && declared != "application/octet-stream" && declared != detected) {
                    throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_MIME_MISMATCH")
                }
                val hash = digest.digest()
                val target = dir.resolve("${hash.toHex()}.$ordinal${extension(detected)}").normalize()
                if (!target.startsWith(dir)) {
                    throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_PATH_INVALID")
                }
                val ownsFile = !Files.exists(target)
                if (ownsFile) {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
                } else {
                    Files.delete(temp)
                }
                return PreparedStationAttachment(
                    sourceId = sourceId,
                    sourceAttachmentId = sourceAttachmentId,
                    mimeType = detected,
                    fileSizeBytes = total,
                    fileSha256 = hash,
                    storagePath = target,
                    retentionUntil = LocalDateTime.now().plusDays(30),
                    ownsFile = ownsFile
                )
            }
            } catch (error: RemoteException) {
                throw error
            } catch (error: SocketTimeoutException) {
                throw RemoteException(ErrorCode.REMOTE_TIMEOUT, "STATION_ATTACHMENT_REMOTE_TIMEOUT", cause = error)
            } catch (error: IOException) {
                throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_IO_FAILED", cause = error)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** 由 sourceId、序号和规范化 URL 摘要生成稳定附件身份，避免保存敏感原始 URL。 */
    internal fun sourceIdentity(sourceId: Long, ordinal: Int, uri: URI): String {
        val urlHash = MessageDigest.getInstance("SHA-256")
            .digest(uri.toASCIIString().toByteArray(Charsets.UTF_8)).toHex()
        return "station:$sourceId:$ordinal:$urlHash".also {
            if (it.length > 100) {
                throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_IDENTITY_INVALID")
            }
        }
    }

    /** 校验附件 URL 只使用 HTTPS、无用户信息且端口受限；拒绝可能绕过 SSRF 边界的地址。 */
    internal fun validateUrl(value: String): URI {
        val uri = runCatching { URI(value) }
            .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_URL_INVALID", cause = it) }
        if (!uri.scheme.equals("https", true) || uri.host == null || uri.userInfo != null || uri.fragment != null) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_URL_INVALID")
        }
        if (uri.port != -1 && uri.port != 443) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_PORT_INVALID")
        }
        return uri
    }

    /** 读取最小文件头识别 JPEG、PNG 或 WEBP；未知类型失败关闭，不信任响应头。 */
    private fun detectMime(path: Path): String {
        val bytes = Files.newInputStream(path).use { it.readNBytes(12) }
        return when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)) -> "image/png"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> throw RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, "STATION_ATTACHMENT_FILE_TYPE_INVALID")
        }
    }

    /** 把受控 MIME 类型映射为固定扩展名；未知类型失败关闭。 */
    private fun extension(mime: String) = when (mime) { "image/jpeg" -> ".jpg"; "image/png" -> ".png"; else -> ".webp" }
    /** 把摘要字节编码为小写十六进制文本；仅用于指纹和日志安全标识。 */
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
/** Station 附件下载专用 DNS；拒绝回环、内网、链路本地和保留地址。 */
    internal object PublicAddressDns : Dns {
        /** 解析附件域名并要求全部地址为公网地址；解析失败或混入内网地址时失败关闭。 */
        override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || !addresses.all(::isPublic)) {
                throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_PRIVATE_ADDRESS")
            }
        }

        /** 判断解析地址是否为可访问公网地址，拒绝回环、链路本地、站点本地和保留地址。 */
        internal fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val raw = address.address
            if (address is Inet4Address) {
                val a = raw[0].toInt() and 255; val b = raw[1].toInt() and 255
                return !(a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224)
            }
            if (address is Inet6Address) {
                val first = raw[0].toInt() and 255; val second = raw[1].toInt() and 255
                return !(first == 0xfc || first == 0xfd || (first == 0xfe && second and 0xc0 == 0x80))
            }
            return false
        }
    }
}
