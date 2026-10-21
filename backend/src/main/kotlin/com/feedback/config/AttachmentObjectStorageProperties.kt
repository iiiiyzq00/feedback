package com.feedback.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI

/**
 * 附件对象存储部署配置。
 *
 * 默认关闭。开启后任何缺失或不安全配置都会阻止应用启动；访问密钥只能通过运行环境或密钥管理注入，
 * 仓库配置不得提供真实默认值。当前 MinIO 部署要求显式注入访问密钥，避免意外使用宿主机默认凭据链。
 */
@Component
@ConfigurationProperties(prefix = "app.attachment.object-storage", ignoreUnknownFields = false)
class AttachmentObjectStorageProperties {
    var enabled: Boolean = false
    var endpoint: String = ""
    var region: String = ""
    var bucket: String = ""
    var pathStyleAccess: Boolean = false
    var allowInsecureEndpoint: Boolean = false
    var accessKey: String = ""
    var secretKey: String = ""
    var publishNew: Boolean = true
    var migrationEnabled: Boolean = false
    var migrationDryRun: Boolean = true
    var migrationBatchSize: Int = 25
    var migrationDelayMs: Long = 30_000
    var migrationRetryAttempts: Int = 3
    var cleanupLocalAfterPublish: Boolean = false
    var cleanupAuditPath: String = "logs/attachment-object-migration-audit.jsonl"
    var minioDataDir: String = ""
    var minimumFreeDiskBytes: Long = 20L * 1024 * 1024 * 1024
    var migrationMaxInMemoryBytes: Long = 128L * 1024 * 1024

    /** 返回全部启动就绪错误；关闭对象存储时不要求填写远端配置。 */
    fun readinessErrors(): List<String> = buildList {
        if (!enabled) {
            return@buildList
        }
        if (!isValidBucket(bucket)) add("ATTACHMENT_OBJECT_BUCKET_INVALID")
        if (migrationBatchSize !in 1..500) add("ATTACHMENT_OBJECT_MIGRATION_BATCH_SIZE_INVALID")
        if (migrationDelayMs !in 1_000..3_600_000) add("ATTACHMENT_OBJECT_MIGRATION_DELAY_INVALID")
        if (migrationRetryAttempts !in 1..10) add("ATTACHMENT_OBJECT_MIGRATION_RETRY_ATTEMPTS_INVALID")
        if (minimumFreeDiskBytes < 1024L * 1024 * 1024) add("ATTACHMENT_OBJECT_MINIMUM_FREE_DISK_BYTES_INVALID")
        if (migrationMaxInMemoryBytes !in 1024L * 1024..512L * 1024 * 1024) {
            add("ATTACHMENT_OBJECT_MIGRATION_MAX_IN_MEMORY_BYTES_INVALID")
        }
        if (cleanupLocalAfterPublish && cleanupAuditPath.isBlank()) add("ATTACHMENT_OBJECT_CLEANUP_AUDIT_PATH_MISSING")
        if (cleanupLocalAfterPublish && minioDataDir.isBlank()) add("ATTACHMENT_OBJECT_MINIO_DATA_DIR_MISSING")
        validateS3()
    }

    /** 对象存储启用且配置不完整时启动失败，禁止运行期静默降级。 */
    @PostConstruct
    fun failClosedWhenEnabledAndIncomplete() {
        val errors = readinessErrors()
        if(errors.isNotEmpty()) configFailed("ATTACHMENT_OBJECT_STORAGE_CONFIGURATION_INVALID")
    }

    /** 校验 S3 端点、区域与成对静态凭据，不读取宿主机默认凭据链。 */
    private fun MutableList<String>.validateS3() {
        if (endpoint.isBlank()) add("ATTACHMENT_OBJECT_ENDPOINT_MISSING")
        if (region.isBlank()) add("ATTACHMENT_OBJECT_REGION_MISSING")
        if ((accessKey.isBlank()) != (secretKey.isBlank())) add("ATTACHMENT_OBJECT_STATIC_CREDENTIALS_INCOMPLETE")
        if (accessKey.isBlank() && secretKey.isBlank()) add("ATTACHMENT_OBJECT_STATIC_CREDENTIALS_MISSING")
        if (endpoint.isNotBlank()) {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            if (uri == null || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
                (uri.path.isNotBlank() && uri.path != "/") ||
                uri.scheme !in setOf("http", "https")) {
                add("ATTACHMENT_OBJECT_ENDPOINT_INVALID")
            } else if (uri.scheme == "http" && !allowInsecureEndpoint) {
                add("ATTACHMENT_OBJECT_ENDPOINT_INSECURE")
            }
        }
    }

    /** 按 S3/MinIO 通用规则校验桶名并拒绝 IP 形式。 */
    private fun isValidBucket(value: String): Boolean =
        BUCKET_PATTERN.matches(value) && ".." !in value && !IP_ADDRESS_PATTERN.matches(value)

    companion object {
        private val BUCKET_PATTERN = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        private val IP_ADDRESS_PATTERN = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")
    }
}
