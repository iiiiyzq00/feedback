<template>
  <header class="navbar">
    <div class="navbar-left">
      <el-button
        :icon="app.sidebarCollapsed ? Expand : Fold"
        text
        @click="app.toggleSidebar()"
      />
      <el-breadcrumb separator="/">
        <el-breadcrumb-item
          v-for="item in breadcrumbs"
          :key="item.path"
          :to="item.path"
        >
          {{ item.title }}
        </el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="navbar-right">
      <!-- 全局搜索 -->
      <el-input
        v-if="!feedbackOnly"
        v-model="searchQuery"
        placeholder="搜索反馈、问题、告警..."
        :prefix-icon="Search"
        class="global-search"
        size="small"
        clearable
        readonly
        @click="openSearch"
        @keyup.enter="app.openGlobalSearch()"
      />

      <!-- 通知 -->
      <el-badge
        v-if="!feedbackOnly"
        :value="unreadCount"
        :hidden="unreadCount === 0"
        class="navbar-icon"
      >
        <el-button
          :icon="Bell"
          text
          aria-label="打开站内通知"
          @click="$router.push('/notifications')"
        />
      </el-badge>

      <!-- 用户 -->
      <el-dropdown trigger="click" @command="handleCommand">
        <span class="user-info">
          <el-avatar :size="32" :icon="UserFilled" />
          <span class="user-identity"
            ><span class="user-name">{{
              auth.user?.realName || auth.user?.username || '用户'
            }}</span
            ><small v-if="auth.user?.realName">{{
              auth.user?.username
            }}</small></span
          >
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">
              <el-icon><User /></el-icon>个人信息
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>
              <el-icon><SwitchButton /></el-icon>退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import {
  ArrowDown,
  Bell,
  Expand,
  Fold,
  Search,
  SwitchButton,
  User,
  UserFilled,
} from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus';
import { useAppStore } from '@/stores/app';
import { useAuthStore } from '@/stores/auth';
import { getInAppUnreadCount } from '@/api/notification';
import { isFeedbackWorkspaceOnlyUser } from '@/utils/feedbackWorkspaceAccess';

const route = useRoute();
const app = useAppStore();
const auth = useAuthStore();
const feedbackOnly = computed(() =>
  isFeedbackWorkspaceOnlyUser(auth.user, auth.hasPermission),
);

const searchQuery = ref('');
const unreadCount = ref(0);
const logoutInProgress = ref(false);
let unreadTimer: number | undefined;
async function refreshUnread() {
  try {
    const data = await getInAppUnreadCount();
    unreadCount.value = Number(data?.count) || 0;
  } catch {
    unreadCount.value = 0;
  }
}
function notificationUpdated(event: Event) {
  const count = (event as CustomEvent).detail?.count;
  typeof count === 'number'
    ? (unreadCount.value = count)
    : void refreshUnread();
}
onMounted(() => {
  if (!feedbackOnly.value) {
    void refreshUnread();
    unreadTimer = window.setInterval(refreshUnread, 30000);
    window.addEventListener(
      'in-app-notifications-updated',
      notificationUpdated,
    );
  }
});
onUnmounted(() => {
  if (unreadTimer) window.clearInterval(unreadTimer);
  window.removeEventListener(
    'in-app-notifications-updated',
    notificationUpdated,
  );
});

function openSearch(event: MouseEvent) {
  const target = event.currentTarget as HTMLElement;
  target?.blur?.();
  app.openGlobalSearch();
}

// 面包屑
const breadcrumbs = computed(() => {
  const matched = route.matched.filter((r) => r.meta?.title);
  return matched.map((r) => ({
    title: r.meta?.title as string,
    path: r.path,
  }));
});

/** 处理用户菜单命令；注销流程优先清理前端敏感状态，再短暂等待服务端撤销会话。 */
async function handleCommand(cmd: string) {
  if (cmd === 'logout') {
    if (logoutInProgress.value) return;
    logoutInProgress.value = true;
    try {
      await ElMessageBox.confirm(
        '退出后需要重新登录才能访问平台，是否确认退出？',
        '确认退出登录',
        {
          confirmButtonText: '退出登录',
          cancelButtonText: '取消',
          type: 'warning',
          autofocus: false,
        },
      );
    } catch {
      logoutInProgress.value = false;
      return;
    }

    const serverLogout = auth.logout();
    // 给服务端会话撤销一个很短的完成窗口，但不能让卡住的请求把受保护内容继续留在屏幕上。
    await Promise.race([
      serverLogout,
      new Promise<void>((resolve) => window.setTimeout(resolve, 300)),
    ]);
    // 始终从可部署站点根路径重载；部分静态宿主不会把 /login 重写到 index.html，可能直接白屏。
    // 根路径加载后由鉴权守卫送回登录页，logged_out 参数负责显示过渡遮罩。
    window.location.replace('/?logged_out=1');
  } else if (cmd === 'profile') {
    window.dispatchEvent(new CustomEvent('open-user-profile'));
  }
}
</script>

<style scoped>
.navbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--navbar-height);
  flex: 0 0 var(--navbar-height);
  min-width: 0;
  padding: 0 clamp(12px, 2vw, 28px);
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid rgba(214, 223, 235, 0.8);
  box-shadow: 0 4px 18px rgba(27, 54, 93, 0.04);
  z-index: 999;
}

.navbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.navbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.global-search {
  width: 280px;
}

.navbar-icon {
  cursor: pointer;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: background 0.2s;
}

.user-info:hover {
  background: #f5f7fa;
}

.user-name {
  font-size: 14px;
  color: var(--text-primary);
}
.user-identity {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.user-identity small {
  font-size: 11px;
  color: var(--text-secondary);
}

@media (max-width: 900px) {
  .global-search {
    width: 200px;
  }
  .user-identity {
    display: none;
  }
}

@media (max-width: 680px) {
  .global-search {
    display: none;
  }
}
</style>
