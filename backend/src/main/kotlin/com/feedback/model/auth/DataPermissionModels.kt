package com.feedback.model.auth

/** 行级数据范围；空集合表示明确拒绝，null 表示该维度不受限。 */
data class DataAccessScope(
    val channels: Set<String>,
    val modules: Set<String>?,
    val regions: Set<String>?,
    /**
     * 租户范围。语义与其余维度一致：空集合明确拒绝，null 不受限。
     *
     * 默认 null 只服务于「构造点不掌握租户」的历史形状。服务实现**必须**显式给出，
     * 特别是匿名主体那条分支——那里若沿用默认值，就等于把「未认证」判成「跨租户全通」。
     */
    val tenants: Set<String>? = null
) {
    /** 按渠道、模块和省市逐层判定；任何受限维度不匹配即失败关闭。 */
    fun permits(channel: String, module: String?, province: String?, city: String?): Boolean {
        if (channel.uppercase() !in channels) {
            return false
        }
        if (modules != null && (module ?: "UNKNOWN").uppercase() !in modules) {
            return false
        }
        if (regions != null) {
            val values = setOfNotNull(province, city).map(String::uppercase).toSet()
            if (values.intersect(regions).isEmpty()) {
                return false
            }
        }
        return true
    }

    /**
     * 租户维度单独判定，**故意不并进 [permits]**。
     *
     * 并进去就要给 permits 加一个带默认值的参数，于是全部现存调用点会在没人察觉的情况下变成
     * 「不检查租户」——那恰好是这次改造想避免的静默失败。拆成独立方法后，每个调用点要么显式调用它，
     * 要么在评审里看得见它没被调用。
     *
     * 缺租户按拒绝处理：`null` 与空白不是「无租户」，而是「租户未知」，未知不能放行。
     */
    fun permitsTenant(tenant: String?): Boolean {
        if (tenants == null) {
            return true
        }
        return TenantKeys.canonical(tenant) in tenants
    }
}

/** 字段授权快照；DENY 优先于 ALLOW，系统结构字段始终保留。 */
data class DataFieldPolicy(
    val unrestricted: Boolean,
    val allowed: Set<String>,
    val denied: Set<String>
) {
    /** 按规范字段名判定是否可见；显式拒绝始终优先。 */
    fun permits(field: String): Boolean {
        val key = canonicalField(field)
        if (key in SYSTEM_FIELDS) {
            return true
        }
        if ("*" in denied || key in denied) {
            return false
        }
        return unrestricted || "*" in allowed || key in allowed
    }

    companion object {
        private val SYSTEM_FIELDS = setOf("ID", "SOURCE_CHANNEL", "STATUS", "REPORTED_AT")

        /** 将 camelCase 与其他字段表示统一为数据库授权使用的大写下划线形式。 */
        fun canonicalField(value: String): String =
            value.replace(Regex("([a-z0-9])([A-Z])"), "\$1_\$2").uppercase()
    }
}

/** 同一次有界授权读取计算出的行级范围和字段策略，避免重复查询形成不一致快照。 */
data class DataPermissionSnapshot(val scope: DataAccessScope, val fieldPolicy: DataFieldPolicy)
