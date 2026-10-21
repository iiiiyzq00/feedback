import type { UserInfo } from '@/stores/auth';

export const FEEDBACK_WORKSPACE_PERMISSION = 'access:phase_one_workspace';

type User = Pick<UserInfo, 'role' | 'authProvider' | 'workspaceAccessRelaxed'> | null | undefined;
type PermissionCheck = (permission: string) => boolean;

export function isFeedbackWorkspaceRole(
  role: string | null | undefined,
): boolean {
  return role === 'PHASE_ONE_TECHNICAL' || role === 'PHASE_ONE_PRODUCT';
}

/**
 * 判断会话来源是否满足工作区的来源要求：默认必须是 SSO；
 * 服务端下发本地放宽标记（app.security.local-workspace.enabled）时，LOCAL 主体同样满足。
 *
 * 这是 `requiresFeishu` 路由元数据与工作区权限判定共用的唯一来源口径，
 * 与后端 PrincipalAccess.hasWorkspace 对应，避免两处形状漂移。
 */
export function isWorkspaceOriginAllowed(user: User): boolean {
  return user?.authProvider === 'SSO' || user?.workspaceAccessRelaxed === true;
}

/**
 * 判断当前会话是否具备反馈工作区入口资格。
 *
 * 页面可见性只负责减少误触，后端鉴权和路由守卫仍是访问控制的权威边界。
 */
export function hasFeedbackWorkspaceAccess(
  user: User,
  hasPermission: PermissionCheck,
): boolean {
  return (
    isWorkspaceOriginAllowed(user) && hasPermission(FEEDBACK_WORKSPACE_PERMISSION)
  );
}

/**
 * 判断当前会话是否只能使用反馈工作区。
 *
 * 管理员即使拥有反馈权限也保留管理入口；反馈专属角色或其他获准的飞书账号
 * 只能看到渠道数据、问题深度分析及其必要详情页面。
 */
export function isFeedbackWorkspaceOnlyUser(
  user: User,
  hasPermission: PermissionCheck,
): boolean {
  if (isFeedbackWorkspaceRole(user?.role)) return true;
  return (
    user?.role !== 'ADMIN' && hasFeedbackWorkspaceAccess(user, hasPermission)
  );
}
