package com.feedback.repository.auth

import com.feedback.mapper.auth.AuthTenantMapper
import org.springframework.stereotype.Repository

/**
 * 租户注册表读取仓储。
 *
 * 与 `AuthUserRepository` 同层同形：Service 只依赖仓储，MyBatis Mapper 不外泄——
 * `ClassicLayeringGateTest` 的 `service-direct-mybatis-mapper` 会拦下越层引用。
 *
 * 返回 `Set` 而不是 `List`：调用方唯一用法是「这个键在不在里面」，
 * 而 `List.contains` 在租户数增长时是线性扫描；更重要的是集合语义消除了「同一个键出现两次」
 * 这种在列表上要额外判断、在集合上不存在的状态。
 */
@Repository
class TenantRegistryRepository(private val mapper: AuthTenantMapper) {

    /**
     * 全部可分配（ACTIVE）租户键。
     *
     * 每次调用都读库，**不做进程内缓存**：分配租户是低频治理动作，多一次查询的代价远小于
     * 「租户被停用后某个实例还在用旧快照放行」这类只在灰度期出现的错误。
     */
    fun activeKeys(): Set<String> = mapper.findActiveKeys().toSet()
}
