<template>
  <div class="login-page">
    <div
      v-if="isFeishuCallback"
      class="login-card feishu-completing-card"
      role="status"
      aria-live="polite"
    >
      <div class="feishu-completing-icon" aria-hidden="true">
        <span class="feishu-completing-spinner"></span>
      </div>
      <h1>正在登录</h1>
      <p>正在安全建立飞书会话，请稍候</p>
      <span>完成后将自动进入平台</span>
    </div>

    <div v-else class="login-card">
      <div class="login-header">
        <h1>学习机反馈与运营平台</h1>
        <p>{{ registerMode ? '注册本地账号' : '汇聚用户声音，推动产品持续改进' }}</p>
      </div>

      <el-alert
        v-if="feishuLoginError && !registerMode"
        class="feishu-login-status"
        title="本次飞书登录未完成"
        :description="feishuLoginErrorDescription"
        type="error"
        :closable="false"
        show-icon
      />

      <el-form
        v-if="!registerMode"
        ref="formRef"
        :model="loginForm"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="loginForm.username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            size="large"
            :disabled="feishuLoading"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            size="large"
            show-password
            :disabled="feishuLoading"
          />
        </el-form-item>

        <el-form-item class="login-submit-row">
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            :disabled="feishuLoading"
            block
            @click="handleLogin"
          >
            登 录
          </el-button>
        </el-form-item>
        <el-divider>或</el-divider>
        <el-button
          class="feishu-login"
          size="large"
          :loading="feishuLoading"
          :disabled="loading"
          block
          @click="handleFeishuLogin"
        >
          使用飞书登录
        </el-button>
      </el-form>

      <!-- 注册入口：只在没有真实飞书租户的本地环境有意义，服务端默认关闭（关闭时按 404 处理）。 -->
      <el-form
        v-else
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-position="top"
        @keyup.enter="handleRegister"
      >
        <el-alert
          class="register-hint"
          title="本地注册"
          description="注册出来的账号只有基础角色（USER）；角色与可见范围由管理员在后台调整。"
          type="info"
          :closable="false"
          show-icon
        />

        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="registerForm.username"
            placeholder="3~50 位字母、数字、下划线、点或连字符"
            :prefix-icon="User"
            size="large"
            :disabled="registerLoading"
          />
        </el-form-item>

        <el-form-item label="显示名（可选）" prop="displayName">
          <el-input
            v-model="registerForm.displayName"
            placeholder="留空则显示用户名"
            size="large"
            :disabled="registerLoading"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="registerForm.password"
            type="password"
            :placeholder="`至少 ${MIN_PASSWORD_LENGTH} 位`"
            :prefix-icon="Lock"
            size="large"
            show-password
            :disabled="registerLoading"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="registerForm.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            :prefix-icon="Lock"
            size="large"
            show-password
            :disabled="registerLoading"
          />
        </el-form-item>

        <el-form-item class="login-submit-row">
          <el-button
            type="primary"
            size="large"
            :loading="registerLoading"
            block
            @click="handleRegister"
          >
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login-mode-switch">
        <el-button
          v-if="!registerMode"
          link
          type="primary"
          :disabled="loading || feishuLoading"
          @click="enterRegisterMode"
        >
          还没有账号？注册
        </el-button>
        <el-button
          v-else
          link
          type="primary"
          :disabled="registerLoading"
          @click="backToLogin"
        >
          已有账号？返回登录
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { homeRouteForRole } from '@/router/guards'
import { useAuthStore } from '@/stores/auth'
import { beginFeishuLogin, register as registerApi } from '@/api/auth'
import { dismissAuthBootAfterPaint, hideAuthBootImmediately } from '@/utils/authTransition'

// 登录页承载三条入口：用户名密码登录、飞书 OAuth 授权回调，以及本地自助注册（默认关闭）。
// 最终认证结果统一写入 auth store，再由角色首页规则决定登录后的落点。
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

// 两种登录方式分别维护提交状态，避免用户在一个流程未结束时重复触发另一个流程。
const formRef = ref()
const loading = ref(false)
const feishuLoading = ref(false)
const feishuLoginError = ref(typeof route.query.feishu_error === 'string')
// 后端会把可公开的飞书失败原因放在查询参数中；这里只转换成面向用户的提示语。
const feishuLoginErrorDescription = computed(() =>
  route.query.feishu_error === 'provider_timeout'
    ? '连接飞书服务超时，请点击下方“使用飞书登录”重新尝试；无需刷新页面。'
    : '请点击下方“使用飞书登录”重新尝试；无需刷新页面。',
)

// 直接根据回调 URL 决定首屏形态，避免 onMounted 开始兑换前短暂闪现密码表单。
const isFeishuCallback = computed(() => typeof route.query.feishu_code === 'string')

const loginForm = reactive({
  username: '',
  password: '',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// 注册表单与服务端同一套策略：账号名形状、口令长度（12~128）都在这里先拦一次，
// 服务端仍会独立校验，前端校验只负责减少往返。
const MIN_PASSWORD_LENGTH = 12

const registerMode = ref(false)
const registerFormRef = ref()
const registerLoading = ref(false)
const registerForm = reactive({
  username: '',
  displayName: '',
  password: '',
  confirmPassword: '',
})

const registerRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_.-]{3,50}$/,
      message: '3~50 位，仅限字母、数字、下划线、点和连字符',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    {
      min: MIN_PASSWORD_LENGTH,
      max: 128,
      message: `密码长度需为 ${MIN_PASSWORD_LENGTH}~128 位`,
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value === registerForm.password) {
          callback()
          return
        }
        callback(new Error('两次输入的密码不一致'))
      },
      trigger: 'blur',
    },
  ],
}

/** 切到注册形态；进入时清掉上一次飞书回调留下的失败提示。 */
function enterRegisterMode() {
  feishuLoginError.value = false
  registerMode.value = true
}

/** 切回登录形态，保留已经填过的登录表单内容。 */
function backToLogin() {
  registerMode.value = false
}

/**
 * 提交注册：服务端只建档，不直接签发会话；成功后立刻用同一套登录链路建立会话并跳转首页。
 * 失败按服务端稳定原因码给出具体提示，而不是统一的“注册失败”。
 */
async function handleRegister() {
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return

  registerLoading.value = true
  try {
    await registerApi({
      username: registerForm.username,
      password: registerForm.password,
      displayName: registerForm.displayName || undefined,
    })
    await auth.login(registerForm.username, registerForm.password)
    ElMessage.success('注册成功，已自动登录')
    const redirect = route.query.redirect as string | undefined
    if (redirect) await router.push(redirect)
    else await router.push(homeRouteForRole(auth.user?.role))
  } catch (error: any) {
    ElMessage.error(registerFailureMessage(error))
  } finally {
    registerLoading.value = false
  }
}

/** 把服务端的稳定原因码翻译成可操作的提示；未知原因保持通用文案。 */
function registerFailureMessage(error: any): string {
  const reason = String(error?.response?.data?.details?.reasonCode || '')
  const message = String(error?.response?.data?.message || '')
  const code = `${reason} ${message}`
  if (code.includes('USERNAME_TAKEN')) return '该用户名已被占用，请换一个'
  if (code.includes('USERNAME_INVALID')) return '用户名格式不合法：3~50 位字母、数字、下划线、点或连字符'
  if (code.includes('PASSWORD_INVALID')) return `密码长度需为 ${MIN_PASSWORD_LENGTH}~128 位`
  if (code.includes('LOGIN_RATE_LIMITED')) return '尝试过于频繁，请稍后重试'
  if (code.includes('DISABLED') || error?.response?.status === 404) {
    return '本机未开启注册入口（服务端 app.auth.registration.enabled=false）'
  }
  return '注册失败，请稍后重试'
}

/**
 * 校验并提交本地账号密码。
 * 登录成功后优先回到路由守卫记录的 redirect，否则进入当前角色的默认首页。
 */
async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await auth.login(loginForm.username, loginForm.password)
    const redirect = route.query.redirect as string | undefined
    if (redirect) await router.push(redirect)
    else await router.push(homeRouteForRole(auth.user?.role))
  } catch {
    ElMessage.error('登录失败，请检查用户名和密码')
  } finally {
    loading.value = false
  }
}

/**
 * 向后端申请一次飞书授权地址，并把当前页面交给飞书 OAuth 流程。
 * returnPath 会随服务端 state 保存，回调兑换成功后用于恢复用户原本要访问的页面。
 */
async function handleFeishuLogin() {
  feishuLoginError.value = false
  feishuLoading.value = true
  try {
    const returnPath = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    const result = await beginFeishuLogin(returnPath)
    window.location.assign(result.url)
  } catch {
    feishuLoading.value = false
    ElMessage.error('飞书登录暂不可用，请稍后重试')
  }
}

// 同一个飞书临时码只能兑换一次；标签页级锁用于拦截刷新或生命周期重复执行产生的并发兑换。
const PROCESSED_CODE_KEY = 'feishu_processed_code'

/** 从地址栏移除已消费或已失效的临时码，避免刷新页面再次触发兑换。 */
async function clearFeishuCodeFromUrl() {
  const { query } = route
  if (query.feishu_code) {
    const restQuery = Object.fromEntries(Object.entries(query).filter(([key]) => key !== 'feishu_code'))
    await router.replace({ query: restQuery })
  }
}

/**
 * 处理飞书授权回跳：加锁、兑换一次性登录码、恢复目标路由，并结束鉴权启动遮罩。
 * 失败时释放锁，使用户可以重新发起完整授权；已失效的旧码不会在当前 URL 中保留。
 */
onMounted(async () => {
  const code = typeof route.query.feishu_code === 'string' ? route.query.feishu_code : ''
  if (!code) return

  // 场景 1：此前兑换已成功但页面刷新，复用现有会话并直接进入角色首页。
  if (auth.isLoggedIn) {
    const target = homeRouteForRole(auth.user?.role)
    await router.replace(target)
    dismissAuthBootAfterPaint()
    return
  }

  // 场景 2：相同 code 正在处理或已经处理，清理 URL 后停止，避免一次性码被重复消费。
  const processed = sessionStorage.getItem(PROCESSED_CODE_KEY)
  if (processed === code) {
    await clearFeishuCodeFromUrl()
    hideAuthBootImmediately()
    return
  }

  // 场景 3：首次见到该 code，先加锁再请求后端兑换。
  sessionStorage.setItem(PROCESSED_CODE_KEY, code)

  feishuLoading.value = true
  try {
    const returnPath = await auth.loginWithFeishuCode(code)
    // 成功后保留锁至标签页关闭，杜绝浏览器历史回退再次兑换。
    const target = !returnPath || returnPath === '/'
      ? homeRouteForRole(auth.user?.role)
      : returnPath
    await router.replace(target)
    dismissAuthBootAfterPaint()
  } catch (error: any) {
    // 失败后释放锁允许重新授权；当前 code 通常已经失效，不能原地重放。
    sessionStorage.removeItem(PROCESSED_CODE_KEY)

    const msg = error?.response?.data?.message || error?.message || ''
    if (msg.includes('ALREADY_USED') || msg.includes('EXPIRED') || msg.includes('invalid')) {
      ElMessage.error('飞书登录码已失效，请重新点击“使用飞书登录”')
    } else {
      ElMessage.error('飞书登录失败，请稍后重试')
    }
    feishuLoginError.value = true
    await clearFeishuCodeFromUrl()
    hideAuthBootImmediately()
  } finally {
    feishuLoading.value = false
  }
})
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #1d1e2c 0%, #2a3a5c 50%, #1a3a6e 100%);
}

.login-card {
  width: 420px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.feishu-login { width: 100%; color: #3370ff; border-color: #3370ff; }

.feishu-login-status { margin-bottom: 20px; }

.register-hint { margin-bottom: 20px; }

.login-mode-switch {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}

.feishu-completing-card {
  box-sizing: border-box;
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.feishu-completing-icon {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  margin-bottom: 24px;
  border-radius: 20px;
  background: rgba(51, 112, 255, 0.1);
}

.feishu-completing-spinner {
  box-sizing: border-box;
  width: 28px;
  height: 28px;
  border: 3px solid rgba(51, 112, 255, 0.22);
  border-top-color: #3370ff;
  border-radius: 50%;
  animation: feishu-completing-spin 0.8s linear infinite;
}

@keyframes feishu-completing-spin { to { transform: rotate(360deg); } }

@media (prefers-reduced-motion: reduce) {
  .feishu-completing-spinner { animation: none; }
}

.feishu-completing-card h1 {
  margin: 0;
  color: var(--text-primary);
  font-size: 24px;
}

.feishu-completing-card p {
  margin: 12px 0 0;
  color: var(--text-secondary);
  font-size: 15px;
}

.feishu-completing-card span {
  margin-top: 8px;
  color: #909399;
  font-size: 13px;
}

.login-submit-row :deep(.el-form-item__content) {
  justify-content: center;
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.login-header h1 {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
}

.login-header p {
  font-size: 14px;
  color: var(--text-secondary);
  margin-top: 8px;
}
</style>
