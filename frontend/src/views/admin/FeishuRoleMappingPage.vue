<template>
  <div class="page-container">
    <div class="page-header">
      <div><h2>飞书权限映射</h2><p>配置飞书身份与组织事实到平台角色的候选规则，由项目负责人审核决定。</p></div>
      <div class="header-actions"><el-button @click="previewDialog=true">权限模拟</el-button><el-button :disabled="!sync.enabled" :loading="syncing" @click="runSync">立即同步</el-button><el-button type="primary" :icon="Plus" @click="openCreate">新增策略草稿</el-button></div>
    </div>

    <el-alert type="warning" :closable="false" show-icon title="当前为决策配置阶段"
      :description="sync.enabled ? '组织同步已启用：系统只消费已批准规则，并在角色或数据范围变化时撤销旧会话。' : '组织同步尚未启用：已批准规则不会改变用户权限。请先在飞书开放平台授予用户、部门和用户组只读权限。'" />

    <div class="summary-grid">
      <el-card shadow="never"><span>规则总数</span><strong>{{ model.items.length }}</strong></el-card>
      <el-card shadow="never"><span>待负责人决定</span><strong>{{ draftCount }}</strong></el-card>
      <el-card shadow="never"><span>已批准</span><strong>{{ model.approvedCount }}</strong></el-card>
      <el-card shadow="never"><span>组织同步</span><strong>{{ sync.enabled ? '已启用' : '待配置飞书权限' }}</strong></el-card>
    </div>

    <el-card shadow="never" class="rules-card">
      <DataTable :data="model.items" :loading="loading" :error="error" empty-text="暂无飞书权限映射草稿" @retry="load">
        <el-table-column label="规则来源" width="120"><template #default="{row}">{{ typeLabel(row.ruleType) }}</template></el-table-column>
        <el-table-column prop="subjectName" label="对象名称" min-width="150" />
        <el-table-column label="飞书稳定 ID" min-width="210"><template #default="{row}"><code>{{ row.subjectId }}</code></template></el-table-column>
        <el-table-column label="授权模板" min-width="150"><template #default="{row}"><el-tag v-if="row.grantKind==='ROLE'" :type="roleType(row.roleCode)">{{ row.roleCode }}</el-tag><span v-else>{{ scopeLabel(row.scopeType) }}：{{ row.scopeValue }}</span></template></el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column label="决策状态" width="110"><template #default="{row}"><el-tag :type="statusType(row.decisionStatus)">{{ statusLabel(row.decisionStatus) }}</el-tag></template></el-table-column>
        <el-table-column prop="description" label="用途说明" min-width="180"><template #default="{row}">{{ row.description || '-' }}</template></el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{row}"><div class="actions">
            <el-button v-if="row.decisionStatus==='DRAFT'" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.decisionStatus==='DRAFT'" text type="success" @click="decide(row,'APPROVED')">批准</el-button>
            <el-button v-if="row.decisionStatus==='DRAFT'" text type="danger" @click="decide(row,'REJECTED')">拒绝</el-button>
            <el-button v-if="row.decisionStatus==='APPROVED'" text type="warning" @click="decide(row,'RETIRED')">退役</el-button>
          </div></template>
        </el-table-column>
      </DataTable>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑映射草稿' : '新增映射草稿'" width="580px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="规则来源" required><el-select v-model="form.ruleType" @change="normalizeDefault"><el-option label="指定用户" value="USER"/><el-option label="飞书用户组" value="USER_GROUP"/><el-option label="飞书部门" value="DEPARTMENT"/><el-option label="默认规则" value="DEFAULT"/></el-select></el-form-item>
        <el-form-item label="授权类型" required><el-radio-group v-model="form.grantKind"><el-radio-button value="ROLE">岗位角色</el-radio-button><el-radio-button value="DATA_SCOPE">数据范围</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="对象名称" required><el-input v-model="form.subjectName" placeholder="仅用于管理员识别" maxlength="100" show-word-limit /></el-form-item>
        <el-form-item label="飞书稳定 ID" required><el-input v-model="form.subjectId" :disabled="form.ruleType==='DEFAULT'" placeholder="open_id / 用户组 ID / department_id" maxlength="128" /></el-form-item>
        <el-form-item v-if="form.grantKind==='ROLE'" label="平台角色" required><el-select v-model="form.roleCode"><el-option v-for="role in model.roles" :key="role.roleCode" :label="`${role.roleName} (${role.roleCode})`" :value="role.roleCode"/></el-select></el-form-item>
        <template v-else><el-form-item label="范围类型" required><el-select v-model="form.scopeType"><el-option label="渠道" value="CHANNEL"/><el-option label="功能模块" value="MODULE"/><el-option label="区域" value="REGION"/></el-select></el-form-item><el-form-item label="范围值" required><el-input v-model="form.scopeValue" placeholder="例如 SDK、JIRA 或区域编码" /></el-form-item></template>
        <el-form-item label="优先级" required><el-input-number v-model="form.priority" :min="1" :max="1000" /></el-form-item>
        <el-form-item label="用途说明"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
        <el-alert v-if="form.grantKind==='ROLE'&&form.roleCode==='ADMIN'" type="error" :closable="false" title="管理员映射属于高风险决策，请确保对象使用稳定 ID，并在批准理由中写明负责人和用途。" />
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>

    <el-dialog v-model="previewDialog" title="权限计算模拟" width="620px"><el-alert type="info" :closable="false" title="只计算已批准策略，不会修改任何用户。"/><el-form label-width="120px" style="margin-top:16px"><el-form-item label="飞书 open_id"><el-input v-model="preview.openId" placeholder="可选：用于指定用户例外规则"/></el-form-item><el-form-item label="用户组 ID"><el-input v-model="preview.groups" type="textarea" placeholder="多个 ID 用逗号分隔"/></el-form-item><el-form-item label="部门 ID"><el-input v-model="preview.departments" type="textarea" placeholder="多个 ID 用逗号分隔"/></el-form-item></el-form><el-descriptions v-if="previewResult" :column="1" border><el-descriptions-item label="最终角色">{{ previewResult.roleCode }}</el-descriptions-item><el-descriptions-item label="数据范围">{{ previewResult.dataScopes?.map((s:any)=>`${s.scopeType}:${s.scopeValue}`).join('、') || '无额外范围' }}</el-descriptions-item><el-descriptions-item label="命中规则数">{{ previewResult.matchedRuleCount }}</el-descriptions-item></el-descriptions><template #footer><el-button @click="previewDialog=false">关闭</el-button><el-button type="primary" @click="runPreview">开始模拟</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DataTable from '@/components/common/DataTable.vue'
import { createFeishuRoleMapping, decideFeishuRoleMapping, getFeishuRoleMappings, getFeishuPolicySyncStatus, previewFeishuPolicy, syncFeishuPolicies, updateFeishuRoleMapping, type FeishuRoleMappingInput } from '@/api/admin'

const loading=ref(false),saving=ref(false),error=ref(''),dialog=ref(false),editing=ref<any>(null)
const model=reactive<any>({items:[],roles:[],approvedCount:0,runtimeMode:'DECISION_ONLY'})
const sync=reactive<any>({enabled:false,lastRun:null});const syncing=ref(false)
const previewDialog=ref(false),previewResult=ref<any>(null);const preview=reactive({openId:'',groups:'',departments:''})
const form=reactive<FeishuRoleMappingInput>({ruleType:'USER_GROUP',subjectId:'',subjectName:'',grantKind:'ROLE',roleCode:'USER',priority:100,description:''})
const draftCount=computed(()=>model.items.filter((item:any)=>item.decisionStatus==='DRAFT').length)
const typeLabel=(v:string)=>({USER:'指定用户',USER_GROUP:'飞书用户组',DEPARTMENT:'飞书部门',DEFAULT:'默认规则'} as any)[v]||v
const statusLabel=(v:string)=>({DRAFT:'待决定',APPROVED:'已批准',REJECTED:'已拒绝',RETIRED:'已退役'} as any)[v]||v
const statusType=(v:string)=>v==='APPROVED'?'success':v==='REJECTED'?'danger':v==='RETIRED'?'info':'warning'
const roleType=(v:string)=>v==='ADMIN'?'danger':v==='OPERATOR'?'warning':v==='AUDITOR'?'primary':'info'
const scopeLabel=(v:string)=>({CHANNEL:'渠道',MODULE:'功能模块',REGION:'区域'} as any)[v]||v
async function load(){loading.value=true;error.value='';try{const [rules,status]=await Promise.all([getFeishuRoleMappings(),getFeishuPolicySyncStatus()]);Object.assign(model,rules);Object.assign(sync,status)}catch{error.value='飞书自动授权策略加载失败，请稍后重试'}finally{loading.value=false}}
function reset(){Object.assign(form,{ruleType:'USER_GROUP',subjectId:'',subjectName:'',grantKind:'ROLE',roleCode:'USER',scopeType:'CHANNEL',scopeValue:'',priority:100,description:'',expectedVersion:undefined})}
function openCreate(){editing.value=null;reset();dialog.value=true}
function openEdit(row:any){editing.value=row;Object.assign(form,{ruleType:row.ruleType,subjectId:row.subjectId,subjectName:row.subjectName,grantKind:row.grantKind,roleCode:row.roleCode||'USER',scopeType:row.scopeType||'CHANNEL',scopeValue:row.scopeValue||'',priority:row.priority,description:row.description||'',expectedVersion:row.version});dialog.value=true}
function normalizeDefault(){if(form.ruleType==='DEFAULT')form.subjectId='*';else if(form.subjectId==='*')form.subjectId=''}
async function save(){if(!form.subjectName.trim()||!form.subjectId.trim()){ElMessage.warning('请填写对象名称和飞书稳定 ID');return}saving.value=true;try{if(editing.value)await updateFeishuRoleMapping(editing.value.id,{...form});else await createFeishuRoleMapping({...form});ElMessage.success('映射草稿已保存');dialog.value=false;await load()}finally{saving.value=false}}
const split=(v:string)=>v.split(/[,，\n]/).map(x=>x.trim()).filter(Boolean)
async function runPreview(){previewResult.value=await previewFeishuPolicy({openId:preview.openId||undefined,groupIds:split(preview.groups),departmentIds:split(preview.departments)})}
async function runSync(){syncing.value=true;try{await syncFeishuPolicies();ElMessage.success('飞书组织同步已完成');await load()}finally{syncing.value=false}}
async function decide(row:any,status:'APPROVED'|'REJECTED'|'RETIRED'){const verb=status==='APPROVED'?'批准':status==='REJECTED'?'拒绝':'退役';try{const result=await ElMessageBox.prompt(`请输入${verb}理由（至少 5 个字符）。当前阶段不会自动改变用户权限。`,`${verb}映射规则`,{confirmButtonText:`确认${verb}`,cancelButtonText:'取消',inputValidator:(value)=>value.trim().length>=5||'理由至少需要 5 个字符'});await decideFeishuRoleMapping(row.id,status,result.value,row.version);ElMessage.success(`规则已${verb}`);await load()}catch{/* 取消 */}}
onMounted(load)
</script>

<style scoped>
.page-header p{margin:6px 0 0;color:var(--text-secondary)}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:16px 0}.summary-grid :deep(.el-card__body){display:grid;gap:8px}.summary-grid span{font-size:13px;color:var(--text-secondary)}.summary-grid strong{font-size:18px;color:var(--text-primary)}.rules-card code{word-break:break-all}.actions{display:flex;gap:4px}.actions :deep(.el-button){margin-left:0}@media(max-width:900px){.summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.summary-grid{grid-template-columns:1fr}}
</style>
