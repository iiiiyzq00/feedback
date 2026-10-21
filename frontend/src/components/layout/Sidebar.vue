<template>
  <aside class="sidebar" :class="{ collapsed: app.sidebarCollapsed }">
    <div class="sidebar-logo">
      <img src="/logo.svg" alt="Logo" class="logo-img" />
      <span v-show="!app.sidebarCollapsed" class="logo-text">学习机反馈与运营平台</span>
    </div>

    <el-menu
      :default-active="activeMenu"
      :collapse="app.sidebarCollapsed"
      :collapse-transition="false"
      background-color="var(--bg-sidebar)"
      text-color="#bfcbd9"
      active-text-color="#409eff"
      router
    >
      <el-menu-item v-if="!feedbackUser" index="/notifications"><el-icon><BellFilled /></el-icon><span>站内通知</span></el-menu-item>
      <el-menu-item v-if="!feedbackUser" index="/tasks/product-feedback"><el-icon><ChatLineSquare /></el-icon><span>需求反馈</span></el-menu-item>
      <el-menu-item v-if="!feedbackUser && hasRole('ADMIN', 'OPERATOR')" index="/tasks/channel-acceptance"><el-icon><Checked /></el-icon><span>渠道验收</span></el-menu-item>
      <!-- 模块一：数据接入监控 -->
      <el-sub-menu index="monitor" v-if="!feedbackUser && (auth.hasPermission('access:ingestion_monitor') || auth.hasPermission('access:import_management'))">
        <template #title>
          <el-icon><Monitor /></el-icon>
          <span>数据接入监控</span>
        </template>
        <el-menu-item v-if="auth.hasPermission('access:import_management')" index="/monitor/import">数据接入管理</el-menu-item>
        <el-menu-item v-if="hasRole('ADMIN', 'OPERATOR')" index="/monitor/cluster-audit">问题归簇审核</el-menu-item>
        <el-menu-item v-if="hasRole('ADMIN', 'OPERATOR')" index="/monitor/ai-review">AI 分类复核</el-menu-item>
      </el-sub-menu>

      <!-- 模块二：渠道数据查看 -->
      <el-sub-menu index="channel" v-if="feedbackChannelAccess">
        <template #title>
          <el-icon><Collection /></el-icon>
          <span>渠道数据查看</span>
        </template>
        <el-menu-item index="/channel/all"><el-icon><Collection /></el-icon><span>全部渠道</span></el-menu-item>
        <el-menu-item index="/channel/station-feedback" :route="{ name: 'StationFeedback' }">
          <el-icon><LearningMachineIcon /></el-icon>
          <span>学习机反馈</span>
        </el-menu-item>
        <el-menu-item index="/channel/jira" v-if="auth.user?.role !== 'USER'">
          <el-icon><Tickets /></el-icon>
          <span>Jira 反馈</span>
        </el-menu-item>
        <el-menu-item index="/channel/quality" v-if="auth.user?.role !== 'USER'">
          <el-icon><SetUp /></el-icon>
          <span>质量平台</span>
        </el-menu-item>
        <el-menu-item index="/channel/redblack" v-if="auth.user?.role !== 'USER'">
          <el-icon><TrendCharts /></el-icon>
          <span>红黑榜</span>
        </el-menu-item>
      </el-sub-menu>

      <!-- 模块三：公共看板 -->
      <el-sub-menu index="dashboard" v-if="feedbackAnalysisAccess">
        <template #title>
          <el-icon><DataBoard /></el-icon>
          <span>公共看板</span>
        </template>
        <el-menu-item v-if="feedbackAnalysisAccess" index="/dashboard/analysis">问题深度分析</el-menu-item>
      </el-sub-menu>

      <!-- 模块四：AI报告与告警中心 -->
      <el-sub-menu index="alert-report" v-if="!feedbackUser && (auth.hasPermission('access:alert_center') || auth.hasPermission('access:ai_reports'))">
        <template #title>
          <el-icon><BellFilled /></el-icon>
          <span>告警与报告</span>
        </template>
        <el-menu-item v-if="auth.hasPermission('access:alert_center')" index="/alert/center">告警中心</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('access:ai_reports')" index="/report/list">AI报告</el-menu-item>
      </el-sub-menu>

      <!-- 模块五：系统管理（Admin/Operator 可见） -->
      <el-sub-menu index="admin" v-if="!feedbackUser && (auth.isAdmin || auth.isOperator || auth.user?.role === 'AUDITOR')">
        <template #title>
          <el-icon><Setting /></el-icon>
          <span>系统管理</span>
        </template>
        <el-menu-item index="/admin/users" v-if="auth.hasPermission('access:user_management')">用户与权限</el-menu-item>
        <el-menu-item index="/admin/alerts" v-if="auth.hasPermission('access:routing_config')">告警规则配置</el-menu-item>
        <el-menu-item index="/admin/masking" v-if="auth.hasPermission('access:masking_view')">脱敏策略管理</el-menu-item>
        <el-menu-item index="/admin/system" v-if="auth.hasPermission('access:system_config')">动态配置</el-menu-item>
        <el-menu-item index="/admin/cluster-evaluation" v-if="auth.isAdmin">归簇质量验收</el-menu-item>
        <el-menu-item index="/admin/cluster-shadow-preview" v-if="auth.isAdmin">Shadow 归簇预览</el-menu-item>
      </el-sub-menu>
    </el-menu>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { BellFilled, ChatLineSquare, Checked, Collection, DataBoard, Monitor, Setting, SetUp, Tickets, TrendCharts } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import LearningMachineIcon from '@/components/common/LearningMachineIcon.vue'
import { hasFeedbackWorkspaceAccess, isFeedbackWorkspaceOnlyUser } from '@/utils/feedbackWorkspaceAccess'

const route = useRoute()
const app = useAppStore()
const auth = useAuthStore()
const feedbackChannelAccess=computed(()=>hasFeedbackWorkspaceAccess(auth.user,auth.hasPermission))
const feedbackAnalysisAccess=feedbackChannelAccess
const feedbackUser=computed(()=>isFeedbackWorkspaceOnlyUser(auth.user,auth.hasPermission))
const hasRole = (...roles: string[]) => !!auth.user?.role && roles.includes(auth.user.role)

const activeMenu = computed(() => {
  const path = route.path
  // 匹配到二级菜单
  const parts = path.split('/')
  if (parts.length >= 3) {
    return `/${parts[1]}/${parts[2]}`
  }
  return path
})
</script>

<style scoped>
.sidebar {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: var(--sidebar-width);
  background: var(--bg-sidebar);
  transition: width 0.3s ease;
  z-index: 1000;
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar.collapsed {
  width: var(--sidebar-collapsed-width);
}

.sidebar-logo {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  height: var(--navbar-height);
  box-sizing: border-box;
  padding: 0 14px;
  gap: 9px;
}

.sidebar.collapsed .sidebar-logo {
  justify-content: center;
  padding-inline: 0;
}

.logo-img {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
}

.logo-text {
  min-width: 0;
  color: #f5f7fb;
  font-size: 13.5px;
  font-weight: 650;
  line-height: 32px;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.sidebar :deep(.el-menu) {
  border-right: none;
}

.sidebar :deep(.el-sub-menu .el-sub-menu__title) {
  height: 48px;
  line-height: 48px;
}

.sidebar :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
}
</style>
