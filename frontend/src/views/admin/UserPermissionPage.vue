<template>
  <div class="page-container">
    <div class="page-header">
      <h2>用户与权限管理</h2>
      <div class="header-actions">
        <el-input v-model="keyword" placeholder="搜索用户名或姓名" clearable @keyup.enter="searchUsers" />
        <el-button @click="searchUsers">搜索</el-button>
        <el-button type="primary" :icon="Plus" @click="showUserDialog(null)">添加用户</el-button>
      </div>
    </div>

    <el-alert v-if="matrix.unassignedPermissions?.length" type="warning" :closable="false"
      :title="`发现 ${matrix.unassignedPermissions.length} 个尚未分配给任何角色的权限码`"
      :description="matrix.unassignedPermissions.join('、')" style="margin-bottom:12px" />

    <DataTable class="user-table" :data="users" :loading="loading" :error="usersError" empty-text="暂无用户" :pagination="pagination" @retry="loadUsers" @page-change="pageChange" @size-change="sizeChange">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" min-width="220"><template #default="{row}"><span class="username">{{ row.username }}</span></template></el-table-column>
      <el-table-column prop="realName" label="姓名" min-width="180"><template #default="{row}"><div class="identity-cell"><span class="real-name">{{ userDisplayName(row) }}</span><el-tag v-if="isFeishuAccount(row)" type="success" size="small">飞书账号</el-tag></div></template></el-table-column>
      <el-table-column label="角色" min-width="190">
        <template #default="{ row }">
          <el-tag v-for="role in row.roles" :key="role" :type="roleTagType(role)" size="small" style="margin-right:4px">{{ role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginAt" label="最后登录" min-width="185"><template #default="{row}"><span class="login-time">{{ formatDateTime(row.lastLoginAt) }}</span></template></el-table-column>
      <el-table-column prop="active" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right" align="center" header-align="center">
        <template #default="{ row }">
          <div class="table-actions">
            <el-button size="small" text type="primary" @click="showUserDialog(row)">编辑</el-button>
            <el-button size="small" text type="warning" @click="manageRoles(row)">角色</el-button>
            <el-button size="small" text @click="manageScopes(row)">数据范围</el-button>
            <el-button v-if="row.active" size="small" text type="danger" @click="handleDisable(row)">停用</el-button>
            <span v-else class="disabled-action">已停用</span>
          </div>
        </template>
      </el-table-column>
    </DataTable>

    <el-alert v-if="matrix.unassignedPermissions.length" class="matrix-state" type="warning" :closable="false"
      :title="`发现 ${matrix.unassignedPermissions.length} 个未分配权限码，发布前必须明确分配`"
      :description="matrix.unassignedPermissions.join('、')" show-icon />
    <StateResult v-if="matrixError && !matrix.roles.length" class="matrix-state" type="error" title="权限矩阵加载失败" :description="matrixError" retryable :retrying="matrixLoading" @retry="loadMatrix" />
    <el-card v-else shadow="never" style="margin-top:16px" v-loading="matrixLoading">
      <template #header><strong>角色权限矩阵</strong><span style="margin-left:12px;color:var(--text-secondary)">权限变更会立即撤销该角色用户的现有会话</span></template>
      <el-tabs v-model="activeMatrixRole">
        <el-tab-pane v-for="role in matrix.roles" :key="role.roleCode" :label="`${role.roleName} (${role.roleCode})`" :name="role.roleCode">
          <div v-for="group in matrix.permissionGroups" :key="group.module" class="permission-group">
            <div class="permission-title">{{ moduleLabel(group.module) }}</div>
            <el-checkbox-group v-model="role.permissions" class="permission-options">
              <el-checkbox v-for="permission in group.permissions" :key="permission.permissionCode" :value="permission.permissionCode"
                :disabled="isFixedRole(role.roleCode) || (role.roleCode==='ADMIN' && ['admin:manage','access:user_management'].includes(permission.permissionCode))">
                {{ permission.permissionName }} <small>({{ permission.permissionCode }})</small>
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <el-alert v-if="isFixedRole(role.roleCode)" type="info" :closable="false" title="反馈技术/产品角色权限包固定，不可单独编辑。" />
          <el-button type="primary" :disabled="isFixedRole(role.roleCode)" @click="saveRolePermissions(role)">保存 {{ role.roleCode }} 权限</el-button>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 用户编辑对话框 -->
    <el-dialog v-model="userDialogVisible" :title="editingUser ? '编辑用户' : '添加用户'" width="480px">
      <el-form :model="userForm" label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="userForm.username" :disabled="!!editingUser" />
        </el-form-item>
        <el-form-item label="姓名"><el-input v-model="userForm.displayName" /></el-form-item>
        <el-form-item v-if="!editingUser" label="初始密码"><el-input v-model="userForm.password" type="password" show-password /></el-form-item>
        <el-form-item label="状态" v-if="editingUser"><el-switch v-model="userForm.active" /></el-form-item>
        <el-form-item label="角色" v-if="!editingUser">
          <el-checkbox-group v-model="userForm.roles"><el-checkbox v-for="r in allRoles" :key="r" :value="r">{{ r }}</el-checkbox></el-checkbox-group>
        </el-form-item>
        <el-alert v-if="!editingUser" title="手机号、邮箱和飞书身份必须通过授权联系人保险库维护，此处不接收联系方式明文。" type="info" :closable="false" />
        <el-form-item v-if="!editingUser" label="数据范围" class="create-scopes">
          <div class="scope-list"><div v-for="(scope,index) in userForm.scopes" :key="index" class="scope-row"><el-select v-model="scope.type"><el-option label="渠道" value="CHANNEL"/><el-option label="模块" value="MODULE"/><el-option label="区域" value="REGION"/><el-option label="字段" value="FIELD"/></el-select><el-input v-model="scope.value" placeholder="SDK / 模块 / 区域 / description"/><el-select v-model="scope.effect"><el-option label="允许" value="ALLOW"/><el-option label="拒绝" value="DENY"/></el-select><el-button text type="danger" @click="userForm.scopes.splice(index,1)">移除</el-button></div><el-button text type="primary" @click="userForm.scopes.push({type:'CHANNEL',value:'SDK',effect:'ALLOW'})">新增数据范围</el-button></div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scopeDialogVisible" title="行列级数据权限" width="560px">
      <el-alert title="用户级规则与角色规则叠加，DENY 优先；字段使用 API 字段名，例如 description、sourceRecordId。" type="info" :closable="false" />
      <div v-for="(scope, index) in scopes" :key="index" style="display:flex;gap:8px;margin-top:12px">
        <el-select v-model="scope.type" style="width:120px"><el-option label="渠道" value="CHANNEL" /><el-option label="模块" value="MODULE" /><el-option label="区域" value="REGION" /><el-option label="字段" value="FIELD" /></el-select>
        <el-input v-model="scope.value" placeholder="SDK / 模块 / 区域 / description" />
        <el-select v-model="scope.effect" style="width:100px"><el-option label="允许" value="ALLOW" /><el-option label="拒绝" value="DENY" /></el-select>
        <el-button type="danger" text @click="scopes.splice(index,1)">删除</el-button>
      </div>
      <el-button style="margin-top:12px" @click="scopes.push({ type:'CHANNEL', value:'SDK', effect:'ALLOW' })">新增范围</el-button>
      <template #footer><el-button @click="scopeDialogVisible=false">取消</el-button><el-button type="primary" @click="saveScopes">保存</el-button></template>
    </el-dialog>

    <!-- 角色分配对话框 -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="420px">
      <div v-if="roleTarget" class="role-target">
        <strong>{{ userDisplayName(roleTarget) }}</strong>
        <el-tag v-if="isFeishuAccount(roleTarget)" type="success" size="small">飞书账号</el-tag>
        <span v-if="roleTarget.username" class="role-target-username">{{ roleTarget.username }}</span>
      </div>
      <el-alert v-if="roleTarget && !canAssign(roleTarget)" type="warning" :closable="false"
        title="仅启用且已绑定飞书的 SSO 账号可分配反馈授权用户角色。" />
      <el-checkbox-group v-model="selectedRoles" @change="handleRoleSelection">
        <el-checkbox value="ADMIN">ADMIN - 全部功能</el-checkbox>
        <el-checkbox value="OPERATOR">OPERATOR - 数据查看+审核+告警处理</el-checkbox>
        <el-checkbox value="AUDITOR">AUDITOR - 渠道/看板/脱敏规则/审计只读</el-checkbox>
        <el-checkbox value="USER">USER - 基础查看（脱敏数据）</el-checkbox>
        <el-checkbox value="PHASE_ONE_TECHNICAL" :disabled="!canAssign(roleTarget)">
          PHASE_ONE_TECHNICAL - 技术：直接查看原文与分析证据（独占角色）
        </el-checkbox>
        <el-checkbox value="PHASE_ONE_PRODUCT" :disabled="!canAssign(roleTarget)">
          PHASE_ONE_PRODUCT - 产品：查看局部隐藏原文与分析证据（独占角色）
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DataTable from '@/components/common/DataTable.vue'
import StateResult from '@/components/common/StateResult.vue'
import { assignRole, createUser, disableUser, getUserList, getRolePermissionMatrix, updateDataPermissions, updateRolePermissions, updateUser } from '@/api/admin'

const loading = ref(false)
const usersError = ref('')
const matrixError = ref('')
const matrixLoading = ref(false)
const users = ref<any[]>([])
const pagination = reactive({ page: 1, size: 20, total: 0 })
const keyword = ref('')
const matrix = reactive<any>({ roles: [], permissionGroups: [], unassignedPermissions: [] })
const activeMatrixRole = ref('ADMIN')

const userDialogVisible = ref(false)
const editingUser = ref<any>(null)
const allRoles = ['ADMIN', 'OPERATOR', 'AUDITOR', 'USER']
const userForm = reactive({ username: '', displayName: '', password: '', active: true, roles: ['USER'] as string[], scopes:[] as any[] })

const roleDialogVisible = ref(false)
const roleTarget = ref<any>(null)
const selectedRoles = ref<string[]>([])
const roleSelectionBeforeChange = ref<string[]>([])
const FEEDBACK_ROLES = ['PHASE_ONE_TECHNICAL','PHASE_ONE_PRODUCT']
function isFixedRole(role:string){return FEEDBACK_ROLES.includes(role)}

function roleTagType(r: string) { return r === 'ADMIN' ? 'danger' : r === 'OPERATOR' ? 'warning' : r === 'AUDITOR' ? 'primary' : 'info' }
function formatDateTime(value?:string){if(!value)return '从未登录';const date=new Date(value);return Number.isNaN(date.getTime())?value:new Intl.DateTimeFormat('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(date)}
function userDisplayName(user:any){return user?.realName || user?.username || '-'}
function isFeishuAccount(user:any){return user?.authProvider === 'SSO' && user?.feishuBound === true}
function canAssign(user:any){return Boolean(user?.active && isFeishuAccount(user))}

function showUserDialog(user: any) {
  editingUser.value = user
  if (user) {
    Object.assign(userForm, { username: user.username, displayName: user.realName, password: '', active: user.active, roles: user.roles,scopes:[] })
  } else {
    Object.assign(userForm, { username: '', displayName: '', password: '', active: true, roles: ['USER'],scopes:[] })
  }
  userDialogVisible.value = true
}

async function loadUsers() { loading.value=true; usersError.value=''; try { const res=await getUserList({page:pagination.page,size:pagination.size,keyword:keyword.value||undefined}); users.value=res.items; pagination.total=res.total } catch { usersError.value='暂时无法获取用户列表，请稍后重试' } finally { loading.value=false } }
function searchUsers(){pagination.page=1;loadUsers()}
function pageChange(page:number){pagination.page=page;loadUsers()}
function sizeChange(size:number){pagination.size=size;pagination.page=1;loadUsers()}
async function loadMatrix(){matrixLoading.value=true;matrixError.value='';try{Object.assign(matrix,await getRolePermissionMatrix());if(!matrix.roles.some((r:any)=>r.roleCode===activeMatrixRole.value))activeMatrixRole.value=matrix.roles[0]?.roleCode||''}catch{matrixError.value='暂时无法获取角色和权限信息，请稍后重试'}finally{matrixLoading.value=false}}
async function saveRolePermissions(role:any){
  if(isFixedRole(role.roleCode)){ElMessage.info('反馈技术/产品权限包固定，不可单独编辑');return}
  if(!role.permissions.length){ElMessage.warning('角色至少需要一个权限');return}
  await updateRolePermissions(role.roleCode,role.permissions);ElMessage.success(`${role.roleCode} 权限已更新，相关会话已撤销`);await loadMatrix()
}
function moduleLabel(value:string){return ({INGESTION:'数据接入',CHANNEL:'渠道数据',DASHBOARD:'公共看板',ALERT:'告警',REPORT:'报告',SYSTEM:'系统管理',LEGACY:'兼容权限'} as Record<string,string>)[value]||value}
async function saveUser() {
  if (editingUser.value) await updateUser(editingUser.value.id, { displayName:userForm.displayName, active:userForm.active })
  else await createUser(userForm)
  ElMessage.success(editingUser.value ? '用户已更新' : '用户已创建')
  userDialogVisible.value = false
  await loadUsers()
}

function manageRoles(user: any) {
  roleTarget.value = user
  selectedRoles.value = Array.isArray(user.roles) ? [...user.roles] : []
  roleSelectionBeforeChange.value = [...selectedRoles.value]
  roleDialogVisible.value = true
}

function handleRoleSelection(nextRoles: string[]) {
  const roles = Array.isArray(nextRoles) ? nextRoles : []
  const addedPhaseRole=roles.find(role=>isFixedRole(role)&&!roleSelectionBeforeChange.value.includes(role))
  const selectedPhaseRole=addedPhaseRole||roles.find(role=>isFixedRole(role))
  if (addedPhaseRole) {
    selectedRoles.value = [addedPhaseRole]
  } else {
    selectedRoles.value = selectedPhaseRole ? [selectedPhaseRole] : roles.filter(role => !isFixedRole(role))
  }
  roleSelectionBeforeChange.value = [...selectedRoles.value]
}

async function saveRoles() {
  if (!roleTarget.value) return
  if (!selectedRoles.value.length) { ElMessage.warning('至少选择一个角色'); return }
  const fixedRole=selectedRoles.value.find(role=>isFixedRole(role))
  if (fixedRole && !canAssign(roleTarget.value)) {
    ElMessage.warning('仅启用且已绑定飞书的 SSO 账号可分配反馈授权用户角色')
    return
  }
  const roles = fixedRole ? [fixedRole] : selectedRoles.value
  await assignRole(roleTarget.value.id, roles)
  ElMessage.success('角色已更新')
  roleDialogVisible.value = false
  await loadUsers()
}

const scopeDialogVisible=ref(false); const scopeTarget=ref<any>(null); const scopes=ref<any[]>([])
function manageScopes(user:any){scopeTarget.value=user;scopes.value=(user.dataScopes||[]).map((s:any)=>({type:s.type,value:s.value,accessLevel:s.accessLevel,effect:s.effect}));scopeDialogVisible.value=true}
async function saveScopes(){await updateDataPermissions(scopeTarget.value.id,scopes.value);ElMessage.success('数据范围已更新');scopeDialogVisible.value=false}

async function handleDisable(user: any) {
  try {
    await ElMessageBox.confirm(`停用后用户“${user.username}”的现有会话将立即失效，确认继续？`, '确认停用用户', { type: 'warning', confirmButtonText:'确认停用' })
    await disableUser(user.id); ElMessage.success('用户已停用'); await loadUsers()
  } catch { /* 取消 */ }
}
onMounted(async()=>{await Promise.all([loadUsers(),loadMatrix()])})
</script>
<style scoped>
.header-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.header-actions :deep(.el-input){width:300px}.user-table{width:100%}.user-table :deep(.el-table){width:100%!important}.identity-cell{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.username,.real-name{display:block;line-height:1.5;word-break:normal;overflow-wrap:anywhere}.login-time{white-space:nowrap;font-variant-numeric:tabular-nums}.table-actions{display:flex;align-items:center;justify-content:center;gap:6px;width:100%;white-space:nowrap}.table-actions :deep(.el-button){margin-left:0;padding-inline:7px}.role-target{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:12px}.role-target-username{color:var(--text-secondary);font-size:12px}.permission-group{padding:14px 16px;margin-bottom:12px;background:#f8fafd;border:1px solid #e7edf5;border-radius:10px}.permission-title{font-weight:650;margin-bottom:10px;color:#46546a}.permission-options{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px 18px}.permission-options :deep(.el-checkbox){min-width:0;margin-right:0}.permission-options :deep(.el-checkbox__label){white-space:normal;line-height:1.45}.permission-options small{color:var(--text-secondary)}
@media(max-width:1100px){.permission-options{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:700px){.header-actions{width:100%}.header-actions :deep(.el-input){width:100%}.permission-options{grid-template-columns:1fr}}
.matrix-state{margin-top:16px}
.disabled-action{font-size:12px;color:var(--text-secondary);padding:0 7px}
.create-scopes{margin-top:14px}.scope-list{display:grid;gap:8px;width:100%}.scope-row{display:grid;grid-template-columns:110px 1fr 90px 50px;gap:6px;width:100%}
</style>
