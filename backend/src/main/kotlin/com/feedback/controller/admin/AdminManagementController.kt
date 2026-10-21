package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.admin.*
import com.feedback.audit.BusinessAuditService
import com.feedback.model.auth.FeishuRoleMappingDecisionInput
import com.feedback.model.auth.FeishuRoleMappingInput
import com.feedback.service.auth.FeishuRoleMappingService
import com.feedback.service.auth.FeishuAuthorizationPolicyService
import com.feedback.model.auth.FeishuPolicyPreviewInput
import org.springframework.web.bind.annotation.*

/**
 * 用户、角色权限、数据范围和飞书授权策略的 HTTP 入口。
 *
 * 仅依赖 Service 接口；URL 资格由安全过滤链先行判断，业务不变量与审计原子性由 ServiceImpl 复核。
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminManagementController(
    private val service: AdminManagementService,
    private val businessAudits: BusinessAuditService,
    private val feishuRoleMappings: FeishuRoleMappingService,
    private val feishuPolicies: FeishuAuthorizationPolicyService,
) {
    /** 返回有界用户管理分页；输入边界和批量装配由服务契约统一处理。 */
    @GetMapping("/users")
    fun users(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) keyword: String?,
    ) =
        ApiResponse.success(service.users(page, size, keyword))

    /** 返回角色、权限目录及其当前关联矩阵。 */
    @GetMapping("/roles/permissions")
    fun rolePermissions() = ApiResponse.success(service.rolePermissionMatrix())

    /** 替换指定角色权限包；会话失效与审计由服务事务保证。 */
    @PutMapping("/roles/{roleCode}/permissions")
    fun rolePermissions(@PathVariable roleCode: String, @RequestBody input: RolePermissionInput) =
        ApiResponse.success(service.replaceRolePermissions(roleCode, input.permissions))

    /** 创建本地用户，密码哈希和最小授权由服务实现处理。 */
    @PostMapping("/users")
    fun createUser(@RequestBody input: UserCreateInput) = ApiResponse.success(service.createUser(input))

    /** 更新用户展示资料或启用状态。 */
    @PutMapping("/users/{id}")
    fun updateUser(@PathVariable id: Long, @RequestBody input: UserUpdateInput) =
        ApiResponse.success(service.updateUser(id, input))

    /** 通过显式 PUT 语义幂等停用用户。 */
    @PutMapping("/users/{id}/disable")
    fun disableUserExplicit(@PathVariable id: Long) = ApiResponse.success(service.disableUser(id))

    /** 保留旧 DELETE 路由兼容；新调用方应迁移到显式 disable 路由。 */
    @Deprecated("Use PUT /users/{id}/disable")
    @DeleteMapping("/users/{id}")
    fun disableUser(@PathVariable id: Long) = ApiResponse.success(service.disableUser(id))

    /** 替换用户角色；最后管理员、飞书绑定和会话失效由服务复核。 */
    @PutMapping("/users/{id}/roles")
    fun roles(@PathVariable id: Long, @RequestBody input: RoleAssignmentInput) =
        ApiResponse.success(service.assignRoles(id, input.roles))

    /** 替换用户数据权限范围并使旧会话失效。 */
    @PutMapping("/users/{id}/data-permissions")
    fun permissions(@PathVariable id: Long, @RequestBody input: DataPermissionInput) =
        ApiResponse.success(service.replaceDataPermissions(id, input.scopes))

    /** 返回飞书授权映射及审批概况。 */
    @GetMapping("/feishu-role-mappings")
    fun feishuRoleMappings() = ApiResponse.success(feishuRoleMappings.list())

    /** 创建飞书授权映射草稿。 */
    @PostMapping("/feishu-role-mappings")
    fun createFeishuRoleMapping(@RequestBody input: FeishuRoleMappingInput) =
        ApiResponse.success(feishuRoleMappings.create(input))

    /** 按版本 CAS 更新飞书授权映射草稿。 */
    @PutMapping("/feishu-role-mappings/{id}")
    fun updateFeishuRoleMapping(@PathVariable id: Long, @RequestBody input: FeishuRoleMappingInput) =
        ApiResponse.success(feishuRoleMappings.update(id, input))

    /** 审批、拒绝或退役飞书授权映射。 */
    @PostMapping("/feishu-role-mappings/{id}/decision")
    fun decideFeishuRoleMapping(@PathVariable id: Long, @RequestBody input: FeishuRoleMappingDecisionInput) =
        ApiResponse.success(feishuRoleMappings.decide(id, input))

    /** 返回最近飞书授权同步状态，不触发远程调用。 */
    @GetMapping("/feishu-role-mappings/sync-status")
    fun feishuSyncStatus() = ApiResponse.success(feishuPolicies.status())

    /** 使用输入组织事实预演授权策略，不落库。 */
    @PostMapping("/feishu-role-mappings/preview")
    fun previewFeishuPolicy(@RequestBody input: FeishuPolicyPreviewInput) =
        ApiResponse.success(feishuPolicies.preview(input))

    /** 显式触发飞书授权全量同步。 */
    @PostMapping("/feishu-role-mappings/sync")
    fun syncFeishuPolicies() = ApiResponse.success(feishuPolicies.syncAll())

    /** 验证指定上限内的业务审计完整性。 */
    @GetMapping("/audit-integrity")
    fun auditIntegrity(@RequestParam(defaultValue = "1000") limit: Int) =
        ApiResponse.success(businessAudits.verify(limit))
}
