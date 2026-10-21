package com.feedback.service.auth

import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.model.auth.DataPermissionSnapshot
import com.feedback.model.auth.RoleCode

/**
 * 反馈数据范围和字段范围服务契约。实现必须从一次有界 Repository 读取中计算稳定快照，
 * 对匿名主体、非法授权事实和越界结果失败关闭。
 */
interface DataPermissionService {
    /** 返回当前主体的渠道范围；匿名或非法授权事实一律返回空集合。 */
    fun allowedChannels(): Set<String>

    /** 在一次调用内计算渠道、模块、地域与租户的同源快照。 */
    fun scope(): DataAccessScope

    /**
     * 供无需 SecurityContext 的内部编排按强类型角色计算范围。
     *
     * [tenantKey] 没有默认值，是刻意的：给定默认值会让调用方在编译期就绕开租户维度，
     * 而绕开的表现是「不受限」——正是最难在评审里发现的那种失败方向。
     */
    fun scopeFor(userId: Long, roles: Set<RoleCode>, tenantKey: String): DataAccessScope

    /** 计算字段级 ALLOW/DENY 策略，DENY 始终优先。 */
    fun fieldPolicy(): DataFieldPolicy

    /** 需要同时消费数据范围和字段策略的查询必须使用本方法。 */
    fun snapshot(): DataPermissionSnapshot
}
