package com.feedback.agent.rca.episode

import com.feedback.agent.rca.domain.TrustedIdentityLevel

/**
 * 身份指纹计算端口。
 *
 * 口径必须与 `t_feedback_identity` **完全一致**（HMAC-SHA256，输入是 `类型:规范化值`），
 * 否则同一个账号在会话化里和在别处会得到两个指纹，问题对象就再也对不上账号维度了。
 * 因此这里不自己实现 HMAC：实现方直接委托既有的身份指纹服务，
 * 让「同口径」由同一个实现保证，而不是靠两处代码看起来一样。
 */
fun interface ContactFingerprintPort {

    /**
     * 计算指纹。`value` 可以是原始形式，由实现按 [type] 规范化
     * （手机号去分隔符、邮箱转小写、设备序列号统一大写），调用方不需要预先规范化。
     */
    fun fingerprint(value: String, type: String): ByteArray
}

/** 渠道登记的联系方式类型。取值与 `t_feedback_identity` 的 type 列一致。 */
enum class ChannelContactType(val storageType: String) {
    PHONE("PHONE"),
    EMAIL("EMAIL")
}

/** 渠道登记的联系方式：工单手机号、邮件来信地址等。 */
data class ChannelContact(val value: String, val type: ChannelContactType)

/**
 * 一条原始信号里可用的身份线索。
 *
 * 字段全部可空，因为「缺哪个」本身就是信息：[EpisodeIdentityResolver] 按**有什么**定等级，
 * 而不是按「想按什么算」——调用方传进来的只是线索，等级由本域决定。
 *
 * [deviceSerial] 与 [deviceAuthorizationProof] 必须成对出现才算授权设备：
 * 只有序列号时它就是一个客户端可以随便填的字符串，把它当可信设备等于让上报方自选身份。
 */
data class ContactHints(
    /** 已登录校验通过的账号 ID。**来自会话令牌的校验结果**，不是客户端自报字段。 */
    val verifiedAccountId: String? = null,
    /** 设备序列号（原始形式，未规范化）。 */
    val deviceSerial: String? = null,
    /** 设备授权凭证。与服务端登记的授权记录核对通过后才由调用方传入。 */
    val deviceAuthorizationProof: String? = null,
    /** 渠道登记的联系方式。 */
    val channelContact: ChannelContact? = null,
    /** 客户端安装标识，用于匿名弱会话化。用户可以清空它，所以它只能支撑最弱的一档。 */
    val anonymousId: String? = null
)

/**
 * 身份解析结果。
 *
 * [fingerprintType] 一并返回而不是只返回指纹：指纹是 `类型:值` 的 HMAC，
 * 同一条原始值在不同类型下得到完全不同的指纹。事后复核「这条信号为什么落在这个分区」时，
 * 没有类型就无法复算，也就无法证明分区键没被写错。
 */
data class ResolvedEpisodeIdentity(
    val level: TrustedIdentityLevel,
    val fingerprint: ByteArray,
    val fingerprintType: String,
    /**
     * 设备指纹（十六进制）。它与 [level] 是**两个独立维度**，不是等级的附属物：
     * 等级回答「这条信号代表谁」，它回答「从哪台已授权设备来」，
     * 所以 [TrustedIdentityLevel.ACCOUNT] 与 [TrustedIdentityLevel.AUTHORIZED_DEVICE]
     * 两档都可能带它。
     *
     * 为空时**宁可不计**设备数，而不是把账号指纹当成设备指纹凑一个数出来：
     * 设备影响面必须按授权设备去重，混入账号指纹会让「4 台设备」变成「3 个账号 4 台设备」里
     * 一个说不清来源的数字。
     */
    val deviceFingerprint: String? = null,
    /** 定级依据。落库与排障都要能看到「为什么算成这个等级」。 */
    val explanation: String
) {

    /** 指纹是二进制，日志、分区标签与测试断言里统一按十六进制呈现，避免不可见字符。 */
    val fingerprintHex: String get() = fingerprint.toHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedEpisodeIdentity) return false
        return level == other.level &&
            fingerprintType == other.fingerprintType &&
            deviceFingerprint == other.deviceFingerprint &&
            fingerprintHex == other.fingerprintHex
    }

    override fun hashCode(): Int =
        listOf(level, fingerprintType, deviceFingerprint, fingerprintHex).hashCode()

    override fun toString(): String =
        "ResolvedEpisodeIdentity(${level.storageCode}/$fingerprintType/$fingerprintHex" +
            (deviceFingerprint?.let { ", device=$it" } ?: "") + ")"
}

/**
 * 身份解析：把一条原始信号里的线索定成一个可信等级 + 一个身份指纹。
 *
 * 这一步是影响面统计的地基，所以它只做一件事：**决定这条信号代表谁**。
 * 归一化与指纹计算交给 [ContactFingerprintPort]，等级判定留在本类。
 *
 * ## 为什么等级要在这里定，而不是由上报方给
 *
 * 等级决定了这条信号能「代表多少人」。如果由客户端上报的身份类型决定，
 * 那么任何人只要在报文里写 `identityLevel=ACCOUNT` 就能把自己的匿名反馈塞进
 * 某个账号的问题对象里，把一个真实账号的影响面改成任意值。
 * 所以等级只能由服务端根据**校验过的线索**推导（见 [ContactHints.verifiedAccountId]）。
 *
 * ## 等级与设备指纹是两个维度
 *
 * 本类产出两样东西：一个等级（代表谁）与一个设备指纹（从哪台授权设备来）。
 * 两者的取值互不决定——账号级信号照样可以带设备指纹，见 [authorizedDeviceFingerprint]。
 * 把它们绑成一档的后果是「4 条反馈来自 3 个账号、4 台设备」这种影响面永远算不出来：
 * 账号级信号会全部不带设备指纹，设备数恒为 0，而 0 看起来像「没有设备维度数据」，
 * 不像「口径写错了」。
 *
 * ## 三条降级规则
 *
 * 1. **账号线索优先，且只认校验过的。** 有 `verifiedAccountId` 就是 [TrustedIdentityLevel.ACCOUNT]，
 *    其余线索即使也在，也不改变等级——等级取最高可用档，而设备与联系方式不能提升也不能冒充账号。
 * 2. **设备必须有授权凭证。** 序列号与凭证缺一即按「没有设备线索」处理，继续往下找联系方式；
 *    否则一条伪造的序列号就能把两条无关反馈并成一个「同一设备」的会话。
 * 3. **什么都没有时按来源自成一会话。** 见 [resolve] 的说明。
 */
class EpisodeIdentityResolver(
    private val fingerprints: ContactFingerprintPort
) {

    /**
     * 解析一条信号的身份。
     *
     * @param sourceId 原始信号的来源 ID。**只在一个地方用得上**：所有身份线索都缺失时，
     *        它被用来给这条信号一个独立的指纹（见下）。
     */
    fun resolve(sourceId: Long, hints: ContactHints): ResolvedEpisodeIdentity {
        // 设备指纹是**独立维度**，不是等级的一部分：等级回答「这条信号代表谁」，
        // 设备指纹回答「它从哪台授权设备来」。一个已登录账号从一台已授权设备上反馈时，
        // 两者同时成立——若只在设备级那一档才记设备指纹，「3 个账号、4 台设备」
        // 这种影响面就永远算不出来：四个账号级信号会被算成「影响 3 人、0 台设备」。
        val device = authorizedDeviceFingerprint(hints)

        hints.verifiedAccountId?.takeIf(String::isNotBlank)?.let { accountId ->
            return ResolvedEpisodeIdentity(
                level = TrustedIdentityLevel.ACCOUNT,
                fingerprint = fingerprints.fingerprint(accountId, ACCOUNT_TYPE),
                fingerprintType = ACCOUNT_TYPE,
                deviceFingerprint = device?.toHex(),
                explanation = "会话令牌校验通过，按账号级身份聚合" + deviceNote(device)
            )
        }

        if (device != null) {
            return ResolvedEpisodeIdentity(
                level = TrustedIdentityLevel.AUTHORIZED_DEVICE,
                fingerprint = device,
                fingerprintType = DEVICE_TYPE,
                deviceFingerprint = device.toHex(),
                explanation = "设备序列号带授权凭证，按授权设备级身份聚合"
            )
        }

        hints.channelContact?.let { contact ->
            return ResolvedEpisodeIdentity(
                level = TrustedIdentityLevel.CHANNEL_CONTACT,
                fingerprint = fingerprints.fingerprint(contact.value, contact.type.storageType),
                fingerprintType = contact.type.storageType,
                explanation = "按渠道登记的联系方式聚合；" +
                    "联系方式是登记事实，不是上报方自报字段"
            )
        }

        hints.anonymousId?.takeIf(String::isNotBlank)?.let { anonymousId ->
            return ResolvedEpisodeIdentity(
                level = TrustedIdentityLevel.ANONYMOUS,
                fingerprint = fingerprints.fingerprint(anonymousId, ANONYMOUS_TYPE),
                fingerprintType = ANONYMOUS_TYPE,
                explanation = "仅有客户端安装标识，按匿名级弱会话化"
            )
        }

        // 一条线索都没有：**不与其他匿名信号合并**，用来源 ID 自成一会话。
        // 合并它们会把互不相关的反馈聚成一个「影响 N 人」的假问题对象，
        // 而 N 越大越像真问题——这种错误会主动伪装成有价值的信号。
        // 拆散只是少了聚合，代价远小于制造一个假影响面。
        return ResolvedEpisodeIdentity(
            level = TrustedIdentityLevel.ANONYMOUS,
            fingerprint = fingerprints.fingerprint("source:$sourceId", ANONYMOUS_TYPE),
            fingerprintType = ANONYMOUS_TYPE,
            explanation = "无任何身份线索，按来源 ID 自成一会话，不与其他匿名信号合并"
        )
    }

    /**
     * 授权设备指纹：序列号与授权凭证**必须成对**出现，缺一即返回 null。
     *
     * 只有序列号时，它就是一个客户端可以随便填的字符串——把它当可信设备，
     * 等于让上报方自选身份，一条伪造的序列号就能把两条无关反馈并成「同一设备」的会话。
     * 凭证的核对在调用方（它知道哪些序列号被授权过），本类只负责「没有凭证就不认」。
     *
     * 返回原始字节而不是十六进制字符串：会话分区键要的是字节（与 `t_feedback_identity` 同格式），
     * 影响面去重集合要的是十六进制呈现。算一次字节、两处各自呈现，
     * 比算两次 HMAC 再期望它们相等要可靠。
     */
    private fun authorizedDeviceFingerprint(hints: ContactHints): ByteArray? {
        val serial = hints.deviceSerial?.takeIf(String::isNotBlank) ?: return null
        val proof = hints.deviceAuthorizationProof?.takeIf(String::isNotBlank) ?: return null
        return fingerprints.fingerprint(serial, DEVICE_TYPE)
    }

    /** 定级依据里补一句设备维度：事后复核「影响面为什么有设备数」时要能读出来。 */
    private fun deviceNote(device: ByteArray?): String =
        if (device == null) "" else "，同时来自一台已授权设备"

    private companion object {
        /** 类型字符串与 `t_feedback_identity` 及既有指纹调用方保持一致，不要另起一套命名。 */
        const val ACCOUNT_TYPE = "ACCOUNT"
        const val DEVICE_TYPE = "DEVICE_SN"
        const val ANONYMOUS_TYPE = "ANONYMOUS"
    }
}

/** 二进制指纹的十六进制呈现。与工具层的摘要输出同一格式（小写、无分隔）。 */
internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
