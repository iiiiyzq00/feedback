<template>
  <div class="page-container">
    <div class="page-header">
      <div><h2>AI 分类复核</h2><p>复核因高风险或低置信度需要人工把关的 AI 分类结果。</p></div>
      <el-button :disabled="!selectedRows.length || submitting" type="primary" @click="openBatch">批量采纳所选（{{ selectedRows.length }}）</el-button>
    </div>
    <el-alert type="info" :closable="false" show-icon
      :title="`复核门禁：置信度低于 ${thresholdLabel}，或 AI 判断为 S0/S1，必须人工复核。门槛来自统一治理配置。`" />
    <el-alert v-if="compatibilityMode" class="compatibility-alert" type="warning" :closable="false" show-icon
      title="当前服务端仍是旧版 AI 队列契约；复核原因已由前端按相同规则补算，分组数字仅代表当前页。请完成后端发布或重启后获取全量统计。" />
    <div class="review-summary">
      <button :class="{ active: !reason }" @click="setReason('')"><span>全部待复核</span><strong>{{ summary.total }}</strong></button>
      <button :class="{ active: reason === 'BOTH' }" @click="setReason('BOTH')"><span>高风险且低置信</span><strong>{{ summary.both }}</strong><small>最高优先</small></button>
      <button :class="{ active: reason === 'HIGH_RISK' }" @click="setReason('HIGH_RISK')"><span>S0/S1 高风险</span><strong>{{ summary.both + summary.highRiskOnly }}</strong><small>不受置信度豁免</small></button>
      <button :class="{ active: reason === 'LOW_CONFIDENCE' }" @click="setReason('LOW_CONFIDENCE')"><span>低于 {{ thresholdLabel }}</span><strong>{{ summary.both + summary.lowConfidenceOnly }}</strong><small>统一治理门槛</small></button>
      <button :class="{ active: reason === 'LEGACY_PENDING' }" @click="setReason('LEGACY_PENDING')"><span>历史待复核</span><strong>{{ summary.legacyPending }}</strong><small>缺少触发证据</small></button>
    </div>
    <div class="queue-note"><span>{{ queueDefinition }}</span><span v-if="summary.oldestProcessedAt">最早等待：{{ relativeAge(summary.oldestProcessedAt) }}</span></div>
    <DataTable :data="records" :loading="loading" :error="loadError" :pagination="pagination" selectable :reserve-selection="false" row-key="sourceId"
      @selection-change="handleSelectionChange" @retry="load"
      @page-change="(p:number) => { pagination.page=p; load() }"
      @size-change="(s:number) => { pagination.size=s; pagination.page=1; load() }">
      <el-table-column label="复核原因" width="170"><template #default="{row}"><el-tag :type="row.reviewReason === 'BOTH' ? 'danger' : row.reviewReason === 'HIGH_RISK' ? 'warning' : 'info'">{{ reasonLabel(row.reviewReason) }}</el-tag></template></el-table-column>
      <el-table-column prop="sourceChannel" label="来源" width="100" />
      <el-table-column prop="title" label="反馈" min-width="200" show-overflow-tooltip />
      <el-table-column prop="aiCategory" label="AI 分类" width="120" />
      <el-table-column label="AI 严重度" width="130"><template #default="{row}">{{ severityLabel(row.aiSeverity) }}</template></el-table-column>
      <el-table-column label="置信度" width="110"><template #default="{row}">{{ percent(row.confidence) }}</template></el-table-column>
      <el-table-column prop="explanation" label="判断依据" min-width="240" show-overflow-tooltip />
      <el-table-column prop="processedAt" label="进入队列时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button text type="primary" @click="open(row)">复核</el-button></template></el-table-column>
    </DataTable>
    <el-dialog v-model="visible" title="AI 分类复核" width="600px">
      <el-descriptions v-if="selected" :column="2" border class="review-evidence">
        <el-descriptions-item label="复核原因">{{ reasonLabel(selected.reviewReason) }}</el-descriptions-item>
        <el-descriptions-item label="置信度">{{ percent(selected.confidence) }}</el-descriptions-item>
        <el-descriptions-item label="AI 分类">{{ selected.aiCategory }}</el-descriptions-item>
        <el-descriptions-item label="AI 严重度">{{ severityLabel(selected.aiSeverity) }}</el-descriptions-item>
        <el-descriptions-item label="判断依据" :span="2">{{ selected.explanation || '未提供' }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px" class="review-form">
        <el-form-item label="处理方式"><el-radio-group v-model="form.action"><el-radio value="CONFIRM">采纳 AI 结果</el-radio><el-radio value="OVERRULE">修正分类</el-radio></el-radio-group></el-form-item>
        <template v-if="form.action==='OVERRULE'">
          <el-alert class="severity-guide" type="info" :closable="false" show-icon
            title="严重度按用户影响判定"
            description="S0 大范围不可用、不可恢复数据丢失或安全风险；S1 核心功能不可用；S2 重要错误或明显降级；S3 非阻断问题、咨询或建议；S4 纯正向且无故障影响。" />
          <el-form-item label="问题分类"><el-select v-model="form.category"><el-option v-for="v in categories" :key="v.code" :label="`${v.label}（${v.code}）`" :value="v.code" /></el-select></el-form-item>
          <el-form-item label="严重度"><el-select v-model="form.severity"><el-option v-for="v in severities" :key="v.value" :label="v.label" :value="v.value" /></el-select></el-form-item>
        </template>
        <el-form-item label="复核说明"><el-input v-model="form.comment" type="textarea" :rows="3" maxlength="1000" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="submitting" @click="visible=false">取消</el-button><el-button :loading="submitting" type="primary" @click="submit">提交复核</el-button></template>
    </el-dialog>
    <el-dialog v-model="batchVisible" title="批量采纳 AI 分类结果" width="560px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon
        :title="`将采纳已选择的 ${selectedRows.length} 条 AI 分类结果；此操作不会修改分类或严重度。`" />
      <el-form label-width="90px" class="review-form">
        <el-form-item label="已选择"><strong>{{ selectedRows.length }} 条</strong></el-form-item>
        <el-form-item label="复核说明" required>
          <el-input v-model="batchComment" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="请填写采纳依据，至少 2 个字符" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="batchVisible=false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="batchComment.trim().length < 2 || !selectedRows.length" @click="submitBatch">确认采纳</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import DataTable from '@/components/common/DataTable.vue'
import { getAiReviews, getAiTaxonomy, submitAiBatchReview, submitAiReview } from '@/api/aiReview'
import { batchReviewPayload, deriveReviewReason, matchesReviewReason, reviewSubmissionError, summarizeReviewQueue } from './aiReviewRules'

const records=ref<any[]>([]),loading=ref(false),loadError=ref(''),visible=ref(false),batchVisible=ref(false),submitting=ref(false),selected=ref<any>(null),selectedRows=ref<any[]>([]),batchComment=ref(''),reason=ref(''),queueDefinition=ref(''),compatibilityMode=ref(false)
const pagination=reactive({page:1,size:20,total:0})
const summary=reactive({total:0,both:0,highRiskOnly:0,lowConfidenceOnly:0,legacyPending:0,oldestProcessedAt:''})
const form=reactive({action:'CONFIRM',category:'',severity:'',comment:''})
const categories=ref<Array<{code:string;label:string;definition:string}>>([])
const severities=ref<Array<{value:string;label:string}>>([])
const threshold=ref(0.85),thresholdLabel=computed(()=>threshold.value.toFixed(2))

async function load(){loading.value=true;loadError.value='';selectedRows.value=[];try{const r=await getAiReviews({page:pagination.page,size:pagination.size,reason:reason.value||undefined});const configured=Number(r.threshold);if(Number.isFinite(configured)&&configured>=0&&configured<=1)threshold.value=configured;compatibilityMode.value=r.contractVersion!=='ai-review-queue-v2';const normalized=(r.records??[]).map((row:any)=>({...row,reviewReason:row.reviewReason||deriveReviewReason(row,threshold.value)}));records.value=compatibilityMode.value&&reason.value?normalized.filter((row:any)=>matchesReviewReason(row.reviewReason,reason.value)):normalized;pagination.total=compatibilityMode.value&&reason.value?records.value.length:Number(r.total??0);Object.assign(summary,r.summary??summarizeReviewQueue(normalized,Number(r.total??normalized.length)));queueDefinition.value=r.queueDefinition??`置信度低于${thresholdLabel.value}或严重度为S0/S1的已生成AI结果`}catch{loadError.value='AI 分类复核队列加载失败，请重试'}finally{loading.value=false}}
function setReason(value:string){reason.value=value;pagination.page=1;load()}
function reasonLabel(value:string){return ({BOTH:'高风险 + 低置信',HIGH_RISK:'S0/S1 高风险',LOW_CONFIDENCE:'低置信度',LEGACY_PENDING:'历史待复核'} as Record<string,string>)[value]??'原因待核验'}
function percent(value:any){return value==null?'—':`${(Number(value)*100).toFixed(1)}%`}
function severityLabel(value:string){return severities.value.find(item=>item.value===value)?.label??value??'—'}
function relativeAge(value:string){const hours=Math.max(0,Math.floor((Date.now()-new Date(value).getTime())/3600000));return hours<24?`${hours}小时`:`${Math.floor(hours/24)}天${hours%24}小时`}
function open(row:any){selected.value=row;Object.assign(form,{action:'CONFIRM',category:row.aiCategory??'',severity:row.aiSeverity??'',comment:''});visible.value=true}
function handleSelectionChange(rows:any[]){selectedRows.value=rows}
function openBatch(){if(!selectedRows.value.length)return;batchComment.value='';batchVisible.value=true}
async function submit(){const error=reviewSubmissionError(form);if(error){ElMessage.warning(error);return}submitting.value=true;try{await submitAiReview(selected.value.sourceId,{...form,comment:form.comment.trim()});visible.value=false;ElMessage.success(form.action==='CONFIRM'?'已采纳 AI 结果':'分类已修正');await load()}finally{submitting.value=false}}
async function submitBatch(){const result=batchReviewPayload(selectedRows.value,batchComment.value);if('error' in result){ElMessage.warning(result.error);return}submitting.value=true;try{await submitAiBatchReview(result.payload);batchVisible.value=false;ElMessage.success(`已采纳 ${result.payload.sourceIds.length} 条 AI 分类结果`);await load()}finally{submitting.value=false}}
onMounted(async()=>{try{const taxonomy=await getAiTaxonomy();categories.value=taxonomy.categories??[];severities.value=(taxonomy.severities??[]).map((item:any)=>({value:item.code,label:`${item.code} ${item.label}`}))}catch{categories.value=[{code:'BUG',label:'程序缺陷',definition:''},{code:'HARDWARE',label:'硬件问题',definition:''},{code:'CONTENT_ERROR',label:'内容错误',definition:''},{code:'DEMAND',label:'需求建议',definition:''},{code:'CONSULT',label:'使用咨询',definition:''},{code:'PRAISE',label:'正向反馈',definition:''},{code:'COMPLAINT',label:'投诉反馈',definition:''}];severities.value=['S0','S1','S2','S3','S4'].map(value=>({value,label:value}))}await load()})
</script>

<style scoped>
.page-header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.page-header h2{margin:0}.page-header p{margin:6px 0 0;color:var(--text-secondary);font-size:13px}.compatibility-alert{margin-top:10px}.review-summary{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin:16px 0}.review-summary button{display:grid;grid-template-columns:1fr auto;gap:3px 10px;padding:14px;text-align:left;border:1px solid var(--border-light);border-radius:9px;background:#fff;cursor:pointer}.review-summary button.active{border-color:var(--color-primary);box-shadow:0 0 0 2px rgba(64,158,255,.1)}.review-summary strong{font-size:20px}.review-summary small{grid-column:1/-1;color:var(--text-secondary)}.queue-note{display:flex;justify-content:space-between;gap:12px;margin-bottom:10px;color:var(--text-secondary);font-size:12px}.review-evidence{margin-bottom:18px}.review-form{margin-top:8px}.severity-guide{margin-bottom:16px}@media(max-width:1100px){.review-summary{grid-template-columns:1fr 1fr}}@media(max-width:600px){.review-summary{grid-template-columns:1fr}.page-header{flex-direction:column}}
</style>
