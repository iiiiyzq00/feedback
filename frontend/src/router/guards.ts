import { useAuthStore } from '@/stores/auth';
import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router';
import {
  hasFeedbackWorkspaceAccess,
  isFeedbackWorkspaceRole,
  isFeedbackWorkspaceOnlyUser,
  isWorkspaceOriginAllowed,
} from '@/utils/feedbackWorkspaceAccess';

/** 将登录用户送到其角色有权访问的默认首页，亦作为越权路由的安全回退地址。 */
export function homeRouteForRole(role?: string) {
  if (isFeedbackWorkspaceRole(role)) return { name: 'AllFeedback' };
  if (role === 'ADMIN') return { name: 'UserPermission' };
  if (role === 'OPERATOR') return { name: 'ClusterAudit' };
  if (role === 'AUDITOR') return { name: 'ReportList' };
  return { name: 'NotificationCenter' };
}

/**
 * 全局路由鉴权入口。
 * 依次恢复会话、限制专属工作区、校验角色/权限/SSO 来源，任何失败都跳转到安全页面。
 */
export async function authGuard(
  to: RouteLocationNormalized,
  _from: RouteLocationNormalized,
  next: NavigationGuardNext,
) {
  // 页面标题不参与鉴权，但在每次导航开始时统一更新。
  document.title = to.meta?.title
    ? `${to.meta.title} - 学习机反馈与运营平台`
    : '学习机反馈与运营平台';

  // 登录页等公开路由显式标记 noAuth，不触发会话恢复。
  if (to.meta?.noAuth) {
    next();
    return;
  }

  // 旧版浏览器 E2E 的开发态旁路；双重条件保证生产构建无法意外开启。
  if (import.meta.env.DEV && import.meta.env.VITE_E2E_BYPASS_AUTH === 'true') {
    next();
    return;
  }

  const auth = useAuthStore();
  if (!auth.user) {
    // Pinia 状态在刷新后会丢失，首次进入受保护页面时用 HttpOnly 会话恢复用户。
    try {
      await auth.loadCurrentUser();
    } catch {
      await auth.logout();
      next({ name: 'Login', query: { redirect: to.fullPath } });
      return;
    }
  }

  const feedbackOnly = isFeedbackWorkspaceOnlyUser(
    auth.user,
    auth.hasPermission,
  );
  // 专属工作区账号只能在反馈相关页面之间导航，不能借地址栏进入其他业务模块。
  const feedbackRoutes = new Set([
    'AllFeedback',
    'StationFeedback',
    'JiraFeedback',
    'QualityFeedback',
    'RedblackFeedback',
    'FeedbackDetail',
    'ProblemAnalysis',
    'ClusterDetail',
  ]);
  if (feedbackOnly && !feedbackRoutes.has(String(to.name || ''))) {
    next({ name: 'AllFeedback' });
    return;
  }

  // 路由可分别声明角色、单权限、全量权限、工作区或权限，以及必须由飞书登录等约束。
  if (to.meta?.roles && Array.isArray(to.meta.roles)) {
    const roles = to.meta.roles.filter(
      (role): role is string => typeof role === 'string',
    );
    if (!roles.includes(String(auth.user?.role ?? ''))) {
      next(homeRouteForRole(auth.user?.role));
      return;
    }
  }
  const permission =
    typeof to.meta?.permission === 'string' ? to.meta.permission : undefined;
  if (permission && !auth.hasPermission(permission)) {
    next(homeRouteForRole(auth.user?.role));
    return;
  }
  if (
    Array.isArray(to.meta?.permissions) &&
    !to.meta.permissions.every((permission: string) =>
      auth.hasPermission(permission),
    )
  ) {
    next(homeRouteForRole(auth.user?.role));
    return;
  }
  if (to.meta?.workspaceOrPermission) {
    const workspaceOrPermission =
      typeof to.meta.workspaceOrPermission === 'string'
        ? to.meta.workspaceOrPermission
        : undefined;
    const feedbackWorkspace = hasFeedbackWorkspaceAccess(
      auth.user,
      auth.hasPermission,
    );
    if (
      !feedbackWorkspace &&
      (!workspaceOrPermission || !auth.hasPermission(workspaceOrPermission))
    ) {
      next(homeRouteForRole(auth.user?.role));
      return;
    }
  }
  // 工作区路由要求飞书来源；本地放宽开关打开时（服务端下发 workspaceAccessRelaxed）LOCAL 主体同样放行。
  if (to.meta?.requiresFeishu && !isWorkspaceOriginAllowed(auth.user)) {
    next(homeRouteForRole(auth.user?.role));
    return;
  }

  next();
}
