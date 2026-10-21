package com.feedback.model.auth

/**
 * 租户稳定键的共享常量，对应 `t_auth_tenant.tenant_key`。
 *
 * 为什么要有这个文件：`"DEFAULT"` 这个字面量此前散落在生产接入（RcaIntakeSignalAssembler）、
 * 离线回放（EpisodeSessionizationMain / ReplayScenario）与测试三处。定义只留一份之后，
 * 「默认租户」的语义变化只需要改一个地方。
 *
 * 这里**不**定义「未知租户」的哨兵值。缺租户的语义是「未知」，由
 * [DataAccessScope.permitsTenant] 按拒绝处理；给未知一个字符串常量只会让人把它当成真值用。
 */
object TenantKeys {
    /** 迁移期兼容租户；与 V245 里各列的 DEFAULT 一致。存量数据与尚未接入租户的写路径都落在这里。 */
    const val DEFAULT: String = "DEFAULT"

    /**
     * 规范化租户键：去空白 + 转大写。
     *
     * 数据库列用的是 `utf8mb4_0900_as_cs`（大小写敏感），而授权行的 `scope_value` 是人工录入的，
     * 因此判定两侧都必须先过这里，否则 `Acme` 与 `ACME` 会被判成两个租户。
     */
    fun canonical(value: String?): String = value?.trim()?.uppercase().orEmpty()
}
