import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
} from "vue-router";
import { authGuard } from "./guards";
import { useAppStore } from "@/stores/app";
import { recordPerformance } from "@/utils/performance";
import { FEEDBACK_WORKSPACE_PERMISSION } from "@/utils/feedbackWorkspaceAccess";

// 懒加载页面
const LoginPage = () => import("@/views/LoginPage.vue");
const ImportManagePage = () => import("@/views/monitor/ImportManagePage.vue");
const ClusterAuditPage = () => import("@/views/monitor/ClusterAuditPage.vue");
const StationFeedbackPage = () => import("@/views/channel/StationFeedbackPage.vue");
const AllFeedbackPage = () => import("@/views/channel/AllFeedbackPage.vue");
const AiReviewPage = () => import("@/views/monitor/AiReviewPage.vue");
const JiraFeedbackPage = () => import("@/views/channel/JiraFeedbackPage.vue");
const QualityFeedbackPage = () =>
  import("@/views/channel/QualityFeedbackPage.vue");
const RedblackFeedbackPage = () =>
  import("@/views/channel/RedblackFeedbackPage.vue");
const FeedbackDetailPage = () =>
  import("@/views/channel/FeedbackDetailPage.vue");
const ProblemAnalysis = () => import("@/views/dashboard/ProblemAnalysis.vue");
const AlertCenter = () => import("@/views/alert/AlertCenter.vue");
const NotificationCenter = () => import("@/views/notification/NotificationCenter.vue");
const ProductFeedbackPage = () => import("@/views/tasks/ProductFeedbackPage.vue");
const ChannelAcceptancePage = () => import("@/views/tasks/ChannelAcceptancePage.vue");
const ReportList = () => import("@/views/report/ReportList.vue");
const ClusterDetail = () => import("@/views/report/ClusterDetail.vue");
const UserPermission = () => import("@/views/admin/UserPermissionPage.vue");
const FeishuRoleMapping = () => import("@/views/admin/FeishuRoleMappingPage.vue");
const AlertConfig = () => import("@/views/admin/AlertConfigPage.vue");
const MaskingRule = () => import("@/views/admin/MaskingRulePage.vue");
const SystemConfig = () => import("@/views/admin/SystemConfigPage.vue");
const ClusterEvaluation = () =>
  import("@/views/admin/ClusterEvaluationPage.vue");
const ClusterShadowPreview = () =>
  import("@/views/admin/ClusterShadowPreviewPage.vue");

const routes: RouteRecordRaw[] = [
  // 唯一公开的页面入口；redirect 与飞书回调参数均由 LoginPage 处理。
  {
    path: "/login",
    name: "Login",
    component: LoginPage,
    meta: { title: "登录", noAuth: true },
  },
  {
    path: "/",
    component: () => import("@/components/layout/AppLayout.vue"),
    redirect: "/channel/all",
    children: [
      // 模块一：数据接入监控
      {
        path: "monitor/import",
        name: "ImportManage",
        component: ImportManagePage,
        meta: {
          title: "数据接入管理",
          roles: ["ADMIN", "OPERATOR"],
          permission: "access:import_management",
        },
      },
      {
        path: "monitor/cluster-audit",
        name: "ClusterAudit",
        component: ClusterAuditPage,
        meta: { title: "问题归簇审核", roles: ["ADMIN", "OPERATOR"] },
      },
      {
        path: "monitor/ai-review",
        name: "AiReview",
        component: AiReviewPage,
        meta: { title: "AI 分类复核", roles: ["ADMIN", "OPERATOR"] },
      },
      // 模块二：渠道数据查看
      { path: "tasks/product-feedback", name: "ProductFeedback", component: ProductFeedbackPage, meta: { title: "需求反馈" } },
      { path: "tasks/channel-acceptance", name: "ChannelAcceptance", component: ChannelAcceptancePage, meta: { title: "渠道验收", roles: ["ADMIN", "OPERATOR"] } },
      {
        path: "channel/all",
        name: "AllFeedback",
        component: AllFeedbackPage,
        meta: { title: "全部渠道", requiresFeishu: true, permission: FEEDBACK_WORKSPACE_PERMISSION },
      },
      {
        path: "channel/station-feedback",
        name: "StationFeedback",
        component: StationFeedbackPage,
        meta: { title: "学习机反馈", requiresFeishu: true, permission: FEEDBACK_WORKSPACE_PERMISSION },
      },
      {
        path: "channel/jira",
        name: "JiraFeedback",
        component: JiraFeedbackPage,
        meta: { title: "Jira 反馈", requiresFeishu: true, permission: FEEDBACK_WORKSPACE_PERMISSION },
      },
      {
        path: "channel/quality",
        name: "QualityFeedback",
        component: QualityFeedbackPage,
        meta: {
          title: "质量平台反馈",
          requiresFeishu: true,
          permission: FEEDBACK_WORKSPACE_PERMISSION,
        },
      },
      {
        path: "channel/redblack",
        name: "RedblackFeedback",
        component: RedblackFeedbackPage,
        meta: { title: "红黑榜反馈", requiresFeishu: true, permission: FEEDBACK_WORKSPACE_PERMISSION },
      },
      {
        path: "channel/:id",
        name: "FeedbackDetail",
        component: FeedbackDetailPage,
        meta: { title: "反馈详情", requiresFeishu: true, permission: FEEDBACK_WORKSPACE_PERMISSION },
      },
      // 模块三：公共看板
      {
        path: "dashboard/analysis",
        name: "ProblemAnalysis",
        component: ProblemAnalysis,
        meta: {
          title: "问题深度分析",
          permission: FEEDBACK_WORKSPACE_PERMISSION,
          requiresFeishu: true,
        },
      },
      // 模块四：告警与报告
      {
        path: "alert/center",
        name: "AlertCenter",
        component: AlertCenter,
        meta: {
          title: "告警中心",
          roles: ["ADMIN", "OPERATOR"],
          permission: "access:alert_center",
        },
      },
      {
        path: "notifications",
        name: "NotificationCenter",
        component: NotificationCenter,
        meta: { title: "站内通知" },
      },
      {
        path: "report/list",
        name: "ReportList",
        component: ReportList,
        meta: {
          title: "AI报告",
          roles: ["ADMIN", "OPERATOR", "AUDITOR"],
          permission: "access:ai_reports",
        },
      },
      {
        path: "report/cluster/:id",
        name: "ClusterDetail",
        component: ClusterDetail,
        meta: { title: "问题簇详情", workspaceOrPermission: "access:ai_reports" },
      },
      // 模块五：系统管理
      {
        path: "admin/users",
        name: "UserPermission",
        component: UserPermission,
        meta: {
          title: "用户与权限",
          roles: ["ADMIN"],
          permission: "access:user_management",
        },
      },
      {
        path: "admin/feishu-role-mappings",
        name: "FeishuRoleMapping",
        component: FeishuRoleMapping,
        meta: {
          title: "飞书权限映射",
          roles: ["ADMIN"],
          permission: "access:user_management",
        },
      },
      {
        path: "admin/alerts",
        name: "AlertConfig",
        component: AlertConfig,
        meta: {
          title: "告警规则配置",
          roles: ["ADMIN", "OPERATOR"],
          permission: "access:routing_config",
        },
      },
      {
        path: "admin/masking",
        name: "MaskingRule",
        component: MaskingRule,
        meta: {
          title: "脱敏策略管理",
          roles: ["ADMIN", "AUDITOR"],
          permission: "access:masking_view",
        },
      },
      {
        path: "admin/system",
        name: "SystemConfig",
        component: SystemConfig,
        meta: {
          title: "动态配置",
          roles: ["ADMIN"],
          permission: "access:system_config",
        },
      },
      {
        path: "admin/cluster-evaluation",
        name: "ClusterEvaluation",
        component: ClusterEvaluation,
        meta: { title: "归簇质量验收", roles: ["ADMIN"] },
      },
      {
        path: "admin/cluster-shadow-preview",
        name: "ClusterShadowPreview",
        component: ClusterShadowPreview,
        meta: { title: "Shadow 归簇预览", roles: ["ADMIN"] },
      },
    ],
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

// 所有导航先经过鉴权守卫，再进入页面加载状态和性能统计钩子。
router.beforeEach(authGuard);
let routeStartedAt = performance.now();
router.beforeEach(() => {
  routeStartedAt = performance.now();
  useAppStore().pageLoading = true;
});
router.afterEach((to, from) => {
  useAppStore().pageLoading = false;
  recordPerformance({
    type: "route",
    name: String(to.name ?? to.path),
    duration: performance.now() - routeStartedAt,
    status: "success",
    detail: { from: from.fullPath, to: to.fullPath },
  });
});
router.onError(() => {
  useAppStore().pageLoading = false;
});

let commonRoutesPrefetched = false;
router.afterEach((to) => {
  if (commonRoutesPrefetched || to.path === "/login") return;
  commonRoutesPrefetched = true;
  const preload = () =>
    Promise.allSettled([
      import("@/views/monitor/ClusterAuditPage.vue"),
      import("@/views/alert/AlertCenter.vue"),
      import("@/views/admin/UserPermissionPage.vue"),
      import("@/views/channel/AllFeedbackPage.vue"),
    ]);
  if ("requestIdleCallback" in window)
    window.requestIdleCallback(() => preload(), { timeout: 2500 });
  else globalThis.setTimeout(preload, 800);
});

export default router;
