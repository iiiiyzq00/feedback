package com.feedback.config

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** 媒体派生预览的资源预算；配置不合法时启动失败，避免无界转码进入生产。 */
@Component
@ConfigurationProperties(prefix = "app.attachment.media-preview", ignoreUnknownFields = false)
class AttachmentMediaPreviewProperties {
    var enabled: Boolean = true
    var batchSize: Int = 4
    var concurrency: Int = 2
    var delayMs: Long = 2_000
    var leaseSeconds: Int = 60
    var maxSourceBytes: Long = 512L * 1024 * 1024
    var maxSourcePixels: Long = 80_000_000
    var maxDimension: Int = 480
    var maxOutputBytes: Int = 512 * 1024
    var ffmpegEnabled: Boolean = false
    var ffmpegPath: String = "ffmpeg"
    var videoTimeoutMs: Long = 10_000

    /** 启动时校验全部资源预算；任一越界都阻止后台预览任务启动。 */
    @PostConstruct
    fun validate() {
        val valid = batchSize in 1..20 && concurrency in 1..4 && delayMs in 1_000..3_600_000 &&
            leaseSeconds in 10..300 && maxSourceBytes in 1..1024L * 1024 * 1024 &&
            maxSourcePixels in 1..200_000_000 && maxDimension in 96..1_024 &&
            maxOutputBytes in 16 * 1024..2 * 1024 * 1024 && videoTimeoutMs in 1_000..30_000 &&
            (!ffmpegEnabled || ffmpegPath.isNotBlank())
        if (!valid) {
            throw ServiceException(
                ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE,
                "ATTACHMENT_MEDIA_PREVIEW_CONFIGURATION_INVALID"
            )
        }
    }
}
