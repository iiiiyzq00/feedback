<template><div class="page-container"><div class="page-header"><div><h2>需求反馈</h2><p>提交字段缺失、展示、流程、权限、数据异常或新需求，系统会自动记录当前页面上下文。</p></div><el-button v-if="canManage" @click="download">导出评审清单</el-button></div>
  <el-card shadow="never"><el-form label-position="top"><el-form-item label="反馈类型"><el-select v-model="form.requestType"><el-option v-for="x in types" :key="x.value" :label="x.label" :value="x.value"/></el-select></el-form-item><el-form-item label="标题"><el-input v-model="form.title" maxlength="200"/></el-form-item><el-form-item label="详细说明"><el-input v-model="form.description" type="textarea" :rows="5" maxlength="2000" show-word-limit/></el-form-item><el-form-item label="附件说明"><input type="file" multiple @change="filesChanged"/><span class="hint">当前保存文件名、类型和大小元数据，最多5个；不上传文件正文。</span></el-form-item><el-button type="primary" :disabled="form.title.trim().length<2||form.description.trim().length<2" @click="submit">提交需求</el-button></el-form></el-card>
  <el-divider>需求清单</el-divider><el-alert type="info" :closable="false" title="处理流程：待评审 → 已受理 → 开发中 → 待验收 → 已完成；Jira关联仅提供只读外链，平台处理不会回写 Jira。"/><el-table :data="records"><el-table-column prop="requestNo" label="需求编号" width="210"/><el-table-column prop="requestType" label="类型" width="130"/><el-table-column prop="title" label="标题" min-width="220"/><el-table-column prop="sourceChannel" label="渠道" width="120"/><el-table-column prop="priorityCode" label="优先级" width="85"/><el-table-column prop="handlerName" label="负责人" width="110"/><el-table-column prop="status" label="状态" width="150"/><el-table-column label="处理" width="260"><template #default="{row}"><el-select v-if="canManage||row.status==='PENDING_ACCEPTANCE'" :model-value="row.status" size="small" @change="(v:string)=>changeStatus(row,v)"><el-option v-for="s in statuses" :key="s.value" :label="s.label" :value="s.value"/></el-select></template></el-table-column></el-table>
</div></template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { createProductFeedback, exportProductFeedback, getProductFeedback, updateProductFeedback } from '@/api/productFeedback'
const route=useRoute(),auth=useAuthStore(),records=ref<any[]>([]),attachments=ref<any[]>([])
const canManage=computed(()=>['ADMIN','OPERATOR'].includes(auth.user?.role||''))
const types=[{label:'字段缺失',value:'FIELD_MISSING'},{label:'展示问题',value:'DISPLAY'},{label:'流程问题',value:'WORKFLOW'},{label:'权限问题',value:'PERMISSION'},{label:'数据异常',value:'DATA_ISSUE'},{label:'新需求',value:'NEW_REQUIREMENT'}]
const statuses=[{label:'待评审',value:'OPEN'},{label:'已受理',value:'ACCEPTED'},{label:'开发中',value:'IN_PROGRESS'},{label:'待验收',value:'PENDING_ACCEPTANCE'},{label:'已完成',value:'COMPLETED'},{label:'已拒绝',value:'REJECTED'},{label:'暂缓',value:'DEFERRED'}]
const form=reactive({requestType:'NEW_REQUIREMENT',title:'',description:''})
function filesChanged(e:Event){attachments.value=Array.from((e.target as HTMLInputElement).files||[]).slice(0,5).map(f=>({name:f.name,size:f.size,type:f.type}))}
async function load(){const r:any=await getProductFeedback({mine:!canManage.value});records.value=r.records||[]}
async function submit(){await createProductFeedback({...form,pagePath:String(route.query.from||route.fullPath),pageContext:{query:route.query},feedbackSourceId:route.query.feedbackId?Number(route.query.feedbackId):undefined,sourceChannel:route.query.channel?String(route.query.channel):undefined,attachments:attachments.value});form.title='';form.description='';attachments.value=[];ElMessage.success('需求已提交');await load()}
async function changeStatus(row:any,status:string){const reason=window.prompt(status==='COMPLETED'?'请输入验收结论或退回原因':'请输入本次处理原因');if(!reason?.trim())return;const resolutionNote=status==='PENDING_ACCEPTANCE'?window.prompt('请输入完成说明')||'':undefined;if(status==='PENDING_ACCEPTANCE'&&!resolutionNote)return;await updateProductFeedback(row.id,{status,reason:reason.trim(),priorityCode:row.priorityCode||'P2',resolutionNote});ElMessage.success('状态及不可覆盖处理记录已更新');await load()}
async function download(){const blob=await exportProductFeedback();const u=URL.createObjectURL(blob);const a=document.createElement('a');a.href=u;a.download='product-feedback.csv';a.click();URL.revokeObjectURL(u)}
onMounted(load)
</script>
<style scoped>.el-card{max-width:760px}.hint{display:block;color:var(--text-secondary);font-size:12px;margin-left:10px}</style>
