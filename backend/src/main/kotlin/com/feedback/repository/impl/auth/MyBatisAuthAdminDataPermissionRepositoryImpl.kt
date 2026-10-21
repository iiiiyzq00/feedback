package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthAdminDataPermissionMapper
import com.feedback.model.po.auth.AuthAdminDataScopePo
import com.feedback.repository.auth.AuthAdminDataPermissionRepository
import com.feedback.repository.auth.AuthDataScopeCommand
import org.springframework.stereotype.Repository

/**
 * MyBatis 管理员数据范围实现。
 *
 * 删除后按去重范围重建；本实现不自行开事务，上层必须将 authVersion 提升和审计纳入同一事务。
 */
@Repository
class MyBatisAuthAdminDataPermissionRepositoryImpl(
    private val mapper: AuthAdminDataPermissionMapper,
) : AuthAdminDataPermissionRepository {
    /** 按一组用户 ID 批量读取管理数据范围；空集合直接返回，避免生成非法 SQL。 */
    override fun byUserIds(ids: Set<Long>): Map<String, List<AuthAdminDataScopePo>> = if (ids.isEmpty()) {
        emptyMap()
    } else {
        mapper.findBySubjectIds(ids.map(Long::toString).toSet()).groupBy { it.subjectId }
    }

    /**
     * 删除后按去重集合重建用户范围；必须加入上层授权写事务，使范围、authVersion 和审计共同提交。
     */
    override fun replace(userId: Long, scopes: List<AuthDataScopeCommand>) {
        val subjectId = userId.toString()
        mapper.deleteBySubjectId(subjectId)
        scopes.distinct().forEach { scope ->
            if (mapper.insert(subjectId, scope.type.code, scope.value, scope.accessLevel.code, scope.effect.code) != 1) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "AUTH_DATA_SCOPE_INSERT_FAILED")
            }
        }
    }
}
