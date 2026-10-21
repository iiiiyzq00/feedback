package com.feedback.strategy.sdk

import com.feedback.service.sdk.*

import com.feedback.config.RuntimeConfigService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** SDK 接入与附件处理共享的运行时限制。 */
interface SdkIngestPolicy {
    /** 返回 SDK 普通反馈保留天数，并把动态配置限制在允许范围内。 */
    fun normalRetentionDays(): Long
    /** 返回 SDK 附件最大字节数；配置缺失或越界时使用受控默认值。 */
    fun maxAttachmentBytes(): Long
}

/** 无动态配置时使用的安全默认值，也供纯单元测试复用。 */
object DefaultSdkIngestPolicy : SdkIngestPolicy {
    /** 返回 SDK 普通反馈保留天数，并把动态配置限制在允许范围内。 */
    override fun normalRetentionDays(): Long = 7
    /** 返回 SDK 附件最大字节数；配置缺失或越界时使用受控默认值。 */
    override fun maxAttachmentBytes(): Long = 50L * 1024 * 1024
}

/**
 * 从运行时配置读取限制；配置服务未启用时回退到 [DefaultSdkIngestPolicy]。
 * 使用 [ObjectProvider] 避免可空构造参数和隐式的可选注入行为。
 */
@Component
class RuntimeSdkIngestPolicy(
    private val runtimeConfigProvider: ObjectProvider<RuntimeConfigService>
) : SdkIngestPolicy {
    /** 返回 SDK 普通反馈保留天数，并把动态配置限制在允许范围内。 */
    override fun normalRetentionDays(): Long =
        runtimeConfigProvider.ifAvailable
            ?.int("cipher.retention-days", DefaultSdkIngestPolicy.normalRetentionDays().toInt())
            ?.toLong()
            ?: DefaultSdkIngestPolicy.normalRetentionDays()

    /** 返回 SDK 附件最大字节数；配置缺失或越界时使用受控默认值。 */
    override fun maxAttachmentBytes(): Long =
        (runtimeConfigProvider.ifAvailable?.int("attachment.max-mb", 50) ?: 50) * 1024L * 1024L
}
