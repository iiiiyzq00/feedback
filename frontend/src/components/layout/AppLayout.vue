<template>
  <div class="app-layout">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <Sidebar />
    <div class="main-area" :class="{ collapsed: app.sidebarCollapsed }">
      <Navbar />
      <div class="route-progress" :class="{ visible: app.pageLoading }"><span /></div>
      <main id="main-content" class="main-content" tabindex="-1">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
        <PageSkeleton v-if="app.pageLoading" class="route-skeleton" />
      </main>
    </div>
    <!-- 全局搜索弹窗 -->
    <el-dialog
      v-if="!feedbackOnly"
      v-model="app.globalSearchVisible"
      title="全局搜索"
      width="600px"
      :before-close="app.closeGlobalSearch"
    >
      <GlobalSearch />
    </el-dialog>
    <el-dialog v-model="profileVisible" title="个人信息" width="460px">
      <div class="profile-head">
        <el-avatar :size="56" :icon="UserFilled" />
        <div><b>{{ auth.user?.realName || auth.user?.username }}</b><p>{{ auth.user?.username }}</p></div>
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户 ID">{{ auth.user?.id ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ auth.user?.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ auth.user?.realName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="当前角色"><el-tag>{{ auth.user?.role || '-' }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="权限数量">{{ auth.user?.permissions?.length || 0 }}</el-descriptions-item>
      </el-descriptions>
      <el-alert class="profile-note" title="账号密码与角色由管理员统一维护" type="info" :closable="false" />
      <template #footer><el-button type="primary" @click="profileVisible=false">完成</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import Sidebar from './Sidebar.vue'
import Navbar from './Navbar.vue'
import GlobalSearch from './GlobalSearch.vue'
import PageSkeleton from '@/components/common/PageSkeleton.vue'
import { UserFilled } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { isFeedbackWorkspaceOnlyUser } from '@/utils/feedbackWorkspaceAccess'

const app = useAppStore()
const auth = useAuthStore()
const feedbackOnly = computed(() => isFeedbackWorkspaceOnlyUser(auth.user, auth.hasPermission))
const profileVisible = ref(false)
function openProfile() { profileVisible.value = true }
onMounted(() => window.addEventListener('open-user-profile', openProfile))
onUnmounted(() => window.removeEventListener('open-user-profile', openProfile))
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.skip-link{position:fixed;left:16px;top:-48px;z-index:9999;padding:10px 14px;background:#fff;color:var(--color-primary);border:2px solid var(--color-primary);border-radius:8px;transition:top .15s}.skip-link:focus{top:8px}

.main-area {
  flex: none;
  width: calc(100% - var(--sidebar-width));
  min-width: 0;
  display: flex;
  flex-direction: column;
  margin-left: var(--sidebar-width);
  transition: margin-left 0.3s ease;
  position: relative;
}

.main-area.collapsed {
  width: calc(100% - var(--sidebar-collapsed-width));
  margin-left: var(--sidebar-collapsed-width);
}

.main-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  background: var(--bg-body);
}
.route-skeleton{position:absolute;inset:var(--navbar-height) 0 0;background:var(--bg-body);z-index:10;overflow:hidden}

.route-progress{position:absolute;top:var(--navbar-height);left:0;right:0;height:3px;overflow:hidden;opacity:0;z-index:1001;pointer-events:none;transition:opacity .15s}
.route-progress.visible{opacity:1}.route-progress span{display:block;width:35%;height:100%;background:linear-gradient(90deg,#3578e5,#67a7ff);border-radius:999px;animation:route-loading 1s ease-in-out infinite}
.profile-head{display:flex;align-items:center;gap:14px;margin-bottom:18px}.profile-head b{font-size:17px}.profile-head p{margin-top:4px;color:var(--text-secondary)}.profile-note{margin-top:16px}
@keyframes route-loading{from{transform:translateX(-110%)}to{transform:translateX(320%)}}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
