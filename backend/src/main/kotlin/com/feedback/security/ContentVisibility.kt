package com.feedback.security

import com.feedback.model.auth.PlatformPermissions

/**
 * 反馈诊断原始值的统一展示策略。具备 `access:phase_one_raw_content` 才返回 RAW；其余主体对电话、邮箱、
 * 设备号和用户号做局部隐藏，并继续扫描自由文本中的嵌入式敏感值。日志附件不经过此对象，其正文只能走安全流。
 */
object ContentVisibility {
    val RAW_PERMISSION = PlatformPermissions.RAW_CONTENT

    /** 只有主体显式拥有原文权限时才允许跳过脱敏。 */
    fun canViewRaw(principal: WebPrincipal): Boolean = RAW_PERMISSION in principal.permissions

    /** 纯内存展示转换，不查询权限库，也不改变审计中记录的诊断类型集合。 */
    fun present(type: String, value: String, principal: WebPrincipal): String =
        if (canViewRaw(principal)) value else maskOriginal(type, value)

    /** 按诊断类型选择局部隐藏算法；未知类型仍扫描文本内嵌敏感值。 */
    internal fun maskOriginal(type: String, value: String): String = when (type.uppercase()) {
        "PHONE" -> maskPhone(value)
        "EMAIL" -> maskEmail(value)
        "DEVICE_SN", "USER_ID", "AUTHENTICATED_USER_ID" -> maskIdentifier(value)
        else -> maskEmbeddedOriginals(value)
    }

    /** 保留合法手机号前三后四位；非标准号码降级为通用标识隐藏。 */
    private fun maskPhone(value: String): String {
        val compact = value.replace(Regex("[\\s-]"), "")
        val prefix = if (compact.startsWith("+86")) "+86" else ""
        val digits = compact.removePrefix("+86")
        return if (digits.length == 11 && digits.all(Char::isDigit)) {
            "$prefix${digits.take(3)}****${digits.takeLast(4)}"
        } else {
            maskIdentifier(value)
        }
    }

    /** 仅保留邮箱本地部分首字符和完整域名；非法邮箱使用通用隐藏。 */
    private fun maskEmail(value: String): String {
        val at = value.indexOf('@')
        if (at <= 0 || at == value.lastIndex) {
            return maskIdentifier(value)
        }
        val local = value.substring(0, at)
        return local.take(1) + "***@" + value.substring(at + 1)
    }

    /** 根据长度保留最小首尾上下文，避免短标识被完整暴露。 */
    private fun maskIdentifier(value: String): String {
        if (value.length <= 2) {
            return "*".repeat(value.length.coerceAtLeast(1))
        }
        if (value.length <= 7) {
            return value.take(1) + "*".repeat(value.length - 2) + value.takeLast(1)
        }
        return value.take(3) + "****" + value.takeLast(4)
    }

    /** 扫描自由文本中的电话、邮箱和带标签标识，避免未知诊断类型绕过脱敏。 */
    private fun maskEmbeddedOriginals(value: String): String {
        var result = PHONE.replace(value) { maskPhone(it.value) }
        result = EMAIL.replace(result) { maskEmail(it.value) }
        return LABELED_IDENTIFIER.replace(result) { match ->
            match.groups[1]!!.value + maskIdentifier(match.groups[2]!!.value)
        }
    }

    private val PHONE = Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9](?:[- ]?[0-9]){8}(?![0-9])")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val LABELED_IDENTIFIER = Regex("(?i)((?:设备(?:序列号|SN)|序列号|SN|user(?:_?id)?|账号)\\s*[:=：]\\s*)([A-Za-z0-9_-]{6,64})")
}
