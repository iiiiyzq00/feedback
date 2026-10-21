package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthDataPermissionMapper
import com.feedback.model.auth.DataAccessLevel
import com.feedback.model.auth.DataPermissionEffect
import com.feedback.model.auth.DataPermissionSubjectType
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.RoleCode
import com.feedback.model.po.auth.AuthDataPermissionPo
import com.feedback.repository.auth.AuthDataPermission
import com.feedback.repository.auth.AuthDataPermissionRepository
import org.springframework.stereotype.Repository

/**
 * MyBatis 数据权限仓储实现。
 *
 * 一条 SQL 批量读取用户和最多 32 个角色，使用第 1001 行作完整性哨兵；不产生 N+1 或无界查询。
 */
@Repository
class MyBatisAuthDataPermissionRepositoryImpl(
    private val mapper: AuthDataPermissionMapper,
) : AuthDataPermissionRepository {
    /**
     * 用单条有界 SQL 读取用户与角色的 READ 授权；角色数或结果数越界即失败关闭，避免截断后误放行。
     */
    override fun findReadPermissions(userId: Long, roles: Set<RoleCode>): List<AuthDataPermission> {
        if (userId <= 0) {
            throw ClientException(ErrorCode.BAD_REQUEST, "AUTH_DATA_PERMISSION_USER_ID_INVALID")
        }
        val effectiveRoles = roles.ifEmpty { setOf(RoleCode.USER) }
        if (effectiveRoles.size > AuthDataPermissionRepository.MAX_ROLE_COUNT) {
            failed("AUTH_DATA_PERMISSION_ROLE_SET_TOO_LARGE")
        }
        val rows = mapper.findReadPermissions(
            userId.toString(),
            effectiveRoles.mapTo(linkedSetOf(), RoleCode::code),
            AuthDataPermissionRepository.MAX_PERMISSION_ROWS + 1,
        )
        if (rows.size > AuthDataPermissionRepository.MAX_PERMISSION_ROWS) {
            failed("AUTH_DATA_PERMISSION_SET_INCOMPLETE")
        }
        return rows.map(::toDomain)
    }

    /** MyBatis 字符串必须全部解析成功；未知存量编码失败关闭。 */
    private fun toDomain(row: AuthDataPermissionPo) = AuthDataPermission(
        id = row.id,
        subjectType = enumValueOf<DataPermissionSubjectType>(row.subjectType),
        subjectId = row.subjectId,
        scopeType = DataScopeType.requireStorage(row.scopeType),
        scopeValue = row.scopeValue,
        accessLevel = DataAccessLevel.requireStorage(row.accessLevel),
        effect = DataPermissionEffect.requireStorage(row.effect),
    )

    /** 将存储编码或查询边界异常统一映射为内部错误，禁止权限计算降级放行。 */
    private fun failed(reasonCode: String): Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
}
