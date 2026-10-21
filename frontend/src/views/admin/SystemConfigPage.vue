<template>
  <div class="page-container">
    <div class="page-header"><div><h2>动态配置</h2><p>配置按模块分组，保存后通过配置目录热加载；每次修改都会生成版本与审计记录。</p></div><div><el-button @click="openHistory()">版本历史</el-button><el-button type="primary" :disabled="!changedRows.length" @click="saveConfig">保存 {{ changedRows.length }} 项变更</el-button></div></div>
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false"><el-button text @click="loadConfig">重试</el-button></el-alert>
    <el-tabs v-else v-model="activeGroup" tab-position="left" class="config-groups" v-loading="loading">
      <el-tab-pane v-for="group in groups" :key="group.key" :name="group.key" :label="`${groupLabel(group.key)}（${group.rows.length}）`">
        <el-card v-for="row in group.rows" :key="row.configKey" shadow="never" class="config-item" :class="{changed:isChanged(row)}">
          <div class="config-copy"><b>{{ configLabel(row) }}</b><code>{{ row.configKey }}</code><p>{{ row.description || '暂无说明' }}</p><span><el-tag size="small" :type="row.hotReload?'success':'warning'">{{ row.hotReload?'支持热加载':'需重启生效' }}</el-tag> 当前版本 {{ row.version }} · 范围 {{ row.minValue }}～{{ row.maxValue }}</span></div>
          <el-input-number v-if="['INTEGER','DECIMAL'].includes(row.valueType)" v-model="row.editValue" :min="Number(row.minValue)" :max="Number(row.maxValue)" :step="row.valueType==='DECIMAL'?0.01:1" controls-position="right" />
          <el-switch v-else-if="row.valueType==='BOOLEAN'" v-model="row.editValue" />
          <el-input v-else v-model="row.editValue" />
          <el-button text @click="openHistory(row.configKey)">历史</el-button>
        </el-card>
        <el-empty v-if="!group.rows.length" description="该分组暂无动态配置" />
      </el-tab-pane>
    </el-tabs>
    <el-drawer v-model="historyVisible" title="配置版本历史" size="60%">
      <el-select v-model="historyKey" filterable @change="loadHistory" style="width:100%;margin-bottom:14px"><el-option v-for="row in configs" :key="row.configKey" :label="`${configLabel(row)}（${row.configKey}）`" :value="row.configKey"/></el-select>
      <el-table :data="history" v-loading="historyLoading"><el-table-column prop="version" label="版本" width="90"/><el-table-column prop="value" label="值" width="110"/><el-table-column prop="reason" label="变更原因" min-width="180"/><el-table-column prop="createdAt" label="时间" width="180"><template #default="{row}">{{formatDateTime(row.createdAt)}}</template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}"><el-tag v-if="row.active" type="success">当前</el-tag><el-button v-else text type="primary" @click="rollback(row)">回滚</el-button></template></el-table-column></el-table>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import { computed,onMounted,ref } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { getSystemConfigHistory,getSystemConfigs,rollbackSystemConfig,updateSystemConfigBatch } from '@/api/admin'
const loading=ref(false),loadError=ref(''),configs=ref<any[]>([]),activeGroup=ref('SDK')
const historyVisible=ref(false),historyKey=ref(''),history=ref<any[]>([]),historyLoading=ref(false)
const labels:Record<string,string>={'sdk.sign-window-seconds':'SDK 签名时间窗口（秒）','sdk.rate-limit':'SDK 应用请求速率限制（次/分钟）','sdk.ip-rate-limit':'SDK IP 请求速率限制（次/分钟）','sdk.nonce-expire-minutes':'Nonce 过期时间（分钟）','attachment.max-mb':'附件最大大小（MB）','cipher.retention-days':'密文保留天数','cluster.top-k':'归簇候选 Top-K','ai.timeout-seconds':'AI 超时时间（秒）','import.rollback-hours':'导入回滚窗口（小时）','alert.governance.expected-rule-count':'告警治理目录预期数量','masking.rule-version':'脱敏规则版本'}
const groupLabels:Record<string,string>={SDK:'SDK 接入',INGEST:'接入与附件',INGESTION:'接入与限流',IMPORT:'导入',MASKING:'脱敏',PRIVACY:'脱敏与隐私',SECURITY:'安全与隐私',DEDUP:'去重归簇',CLUSTER:'去重归簇',ALERT:'告警',AI:'AI 分析',SYSTEM:'系统'}
const groups=computed(()=>{const map=new Map<string,any[]>();for(const row of configs.value){const key=String(row.ownerModule||inferGroup(row.configKey));if(!map.has(key))map.set(key,[]);map.get(key)!.push(row)}return [...map].map(([key,rows])=>({key,rows}))})
const changedRows=computed(()=>configs.value.filter(isChanged))
function inferGroup(key:string){if(key.startsWith('sdk.'))return'SDK';if(key.includes('mask')||key.includes('cipher'))return'MASKING';if(key.includes('alert'))return'ALERT';if(key.includes('cluster')||key.includes('dedup'))return'CLUSTER';if(key.includes('import'))return'IMPORT';return'SYSTEM'}
function groupLabel(key:string){return groupLabels[key]||key} function configLabel(row:any){return labels[row.configKey]||row.description||row.configKey}
function isChanged(row:any){return JSON.stringify(row.editValue)!==JSON.stringify(row.originalValue)}
async function loadConfig(){loading.value=true;loadError.value='';try{const rows=await getSystemConfigs();configs.value=rows.map((row:any)=>{const value=['INTEGER','DECIMAL'].includes(row.valueType)?Number(row.value):row.value;return {...row,originalValue:value,editValue:value}});const available=groups.value.map(x=>x.key);if(!available.includes(activeGroup.value))activeGroup.value=available[0]||''}catch{loadError.value='动态配置加载失败，请检查服务状态'}finally{loading.value=false}}
async function saveConfig(){const changes=changedRows.value;if(!changes.length)return;const{value:reason}=await ElMessageBox.prompt(`将原子保存 ${changes.length} 项配置，请输入变更原因`,'保存动态配置',{inputValidator:v=>v.trim().length>=2||'至少2个字符'});await updateSystemConfigBatch({reason,changes:changes.map(row=>({key:row.configKey,value:row.editValue,expectedVersion:row.version}))});ElMessage.success('配置已生成新版本并热加载');await loadConfig()}
async function openHistory(key?:string){historyKey.value=key||historyKey.value||configs.value[0]?.configKey||'';historyVisible.value=true;if(historyKey.value)await loadHistory()}
async function loadHistory(){historyLoading.value=true;try{const res=await getSystemConfigHistory(historyKey.value,{page:1,size:50});history.value=res.records}finally{historyLoading.value=false}}
async function rollback(row:any){const current=configs.value.find(x=>x.configKey===historyKey.value);const{value:reason}=await ElMessageBox.prompt(`确认回滚到 ${row.version}？请输入原因`,'配置回滚',{inputValidator:v=>v.trim().length>=2||'至少2个字符'});await rollbackSystemConfig(historyKey.value,{targetVersion:row.version,expectedVersion:current?.version,reason});ElMessage.success('已创建回滚版本并按配置策略生效');await Promise.all([loadConfig(),loadHistory()])}
function formatDateTime(value?:string){if(!value)return'-';const date=new Date(value);return Number.isNaN(date.getTime())?value:new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'medium',hour12:false}).format(date)}
onMounted(loadConfig)
</script>
<style scoped>
.page-header p{margin:6px 0 0;color:var(--text-secondary)}.config-groups{background:#fff;border-radius:var(--radius-card);padding:16px;min-height:430px}.config-item{margin-bottom:12px}.config-item.changed{border-color:var(--el-color-warning)}.config-item :deep(.el-card__body){display:grid;grid-template-columns:minmax(0,1fr) 180px 60px;align-items:center;gap:16px}.config-copy{display:grid;gap:5px}.config-copy code{color:var(--text-secondary)}.config-copy p{margin:0;color:var(--text-secondary)}.config-copy span{font-size:12px;color:var(--text-secondary)}@media(max-width:800px){.config-item :deep(.el-card__body){grid-template-columns:1fr}.config-groups{padding:8px}}
</style>
