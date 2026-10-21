<template>
  <div class="page-container">
    <div class="page-header">
      <h2>AI报告</h2>
      <el-dropdown v-if="auth.hasPermission('action:generate_reports')" :disabled="generating" @command="generateReport">
        <el-button type="primary" :icon="Plus" :loading="generating">{{ generating ? `${generatingLabel}生成中` : '生成报告' }}</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="DAILY_INGEST">每日数据接入报告</el-dropdown-item>
            <el-dropdown-item command="JIRA_IMPORT_RECONCILIATION">Jira 数据接入与对账报告</el-dropdown-item>
            <el-dropdown-item command="WEEKLY_TREND">每周多渠道趋势报告</el-dropdown-item>
            <el-dropdown-item command="VERSION_RELEASE">版本上线观察报告</el-dropdown-item>
            <el-dropdown-item command="QUALITY_LINKAGE">质量关联报告</el-dropdown-item>
            <el-dropdown-item command="REDBLACK_ANALYSIS">红黑榜分析</el-dropdown-item>
            <el-dropdown-item command="RISK_BRIEF">风险与舆情简报</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <section v-if="activeTask" class="generation-task-card" aria-live="polite">
      <div class="task-icon"><el-icon :class="{ 'is-loading': !activeTask.cancelRequested }"><Loading /></el-icon></div>
      <div class="task-content">
        <div class="task-heading"><strong>{{ activeTask.cancelRequested ? `${generatingLabel}正在取消` : `${generatingLabel}正在生成` }}</strong><span>{{ activeTask.status === 'PENDING' ? '等待执行' : activeTask.cancelRequested ? '正在安全结束任务' : 'AI 分析中' }} · 已用时 {{ elapsedText }}</span></div>
        <el-progress :percentage="taskProgress" :indeterminate="!activeTask.cancelRequested" :duration="3" :show-text="false" />
        <p>{{ activeTask.cancelRequested ? '模型调用结束后将丢弃结果，不会新增报告。' : '任务在后台运行，您可以继续查看、下载、翻页或管理其他报告。' }}</p>
      </div>
      <el-button type="danger" plain :disabled="Boolean(activeTask.cancelRequested)" @click="cancelGeneration">{{ activeTask.cancelRequested ? '取消中…' : '取消生成' }}</el-button>
    </section>
    <el-collapse v-if="recentTasks.length" style="margin-bottom:12px"><el-collapse-item title="最近报告任务" name="tasks"><el-table :data="recentTasks" size="small"><el-table-column prop="id" label="任务" width="80"/><el-table-column prop="status" label="状态" width="130"/><el-table-column prop="retryCount" label="重试" width="80"/><el-table-column prop="errorCode" label="失败原因" min-width="220"/><el-table-column label="操作" width="90"><template #default="{row}"><el-button v-if="row.status==='RETRY_PENDING'&&auth.hasPermission('action:generate_reports')" text type="primary" @click="retryTask(row)">重试</el-button></template></el-table-column></el-table></el-collapse-item></el-collapse>

    <DataTable :data="reports" :loading="loading" :pagination="pagination" @page-change="changePage" @size-change="changePageSize">
      <el-table-column label="ID" width="64" align="center"><template #default="scope">{{ (pagination.page-1)*pagination.size+scope.$index+1 }}</template></el-table-column>
      <el-table-column prop="type" label="类型" width="106">
        <template #default="{ row }">
          <el-tag :type="reportTypeTag(row.type)" size="small">{{ reportTypeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="190" show-overflow-tooltip />
      <el-table-column prop="periodStart" label="周期起始" width="96" />
      <el-table-column prop="periodEnd" label="周期截止" width="96" />
      <el-table-column prop="validationStatus" label="数字校验" width="92"><template #default="{row}"><el-tag size="small" :type="row.validationStatus==='PASS'?'success':'warning'">{{ row.validationStatus==='PASS'?'通过':'待核实' }}</el-tag></template></el-table-column>
      <el-table-column prop="status" label="状态" width="86">
        <template #default="{ row }">
          <StatusBadge :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column prop="generatedAt" label="生成时间" width="168"><template #default="{row}">{{ formatDateTime(row.generatedAt) }}</template></el-table-column>
      <el-table-column label="操作" width="82" fixed="right" align="center" header-align="center">
        <template #default="{ row }">
          <div class="report-actions">
            <el-button size="small" text type="primary" @click="viewReport(row)">查看</el-button>
            <el-button size="small" text type="primary" @click="exportPDF(row)">下载PDF</el-button>
            <el-button v-if="auth.hasPermission('action:delete_reports')" size="small" text type="danger" @click="removeReport(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </DataTable>

    <el-dialog v-model="detailVisible" :title="detail?.title || pendingDetailTitle || '报告详情'" width="820px" :close-on-click-modal="!detailLoading">
      <div v-if="detailLoading" class="detail-loading" aria-live="polite">
        <el-skeleton :rows="8" animated />
        <p>正在加载报告内容，请稍候…</p>
      </div>
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="数据范围">{{ detail.periodStart }} 至 {{ detail.periodEnd }}</el-descriptions-item>
          <el-descriptions-item label="数据最新时间">{{ formatDateTime(detail.dataLatestTime) }}</el-descriptions-item>
          <el-descriptions-item label="原始记录数"><el-button link type="primary" @click="openDrilldown(detail.content?.summaryDrilldowns?.rawRecordCount)">{{ detail.rawRecordCount }}</el-button></el-descriptions-item>
          <el-descriptions-item label="去重问题数"><el-button link type="primary" @click="openDrilldown(detail.content?.summaryDrilldowns?.uniqueProblemCount)">{{ detail.uniqueProblemCount }}</el-button></el-descriptions-item>
          <el-descriptions-item label="Prompt / Skill">{{ detail.promptVersion || '-' }} / {{ detail.skillVersion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ detail.modelName || degradedModelLabel(detail) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert :title="detail.validationStatus==='PASS'?'✅ 全部数字校验通过':`⚠️ ${(detail.validationErrors || []).length}个数字待核实`" :type="detail.validationStatus==='PASS'?'success':'warning'" :closable="false" style="margin:16px 0" />
        <el-alert v-if="detail.degraded" :title="degradedMessage(detail)" type="warning" :closable="false" style="margin-bottom:12px" />
        <section v-else-if="detail.generatedSummary" class="ai-summary"><h3>🤖 AI 文字解读</h3><p>{{ detail.generatedSummary }}</p></section>
        <el-alert title="报告数字来自已校验的事实快照；点击蓝色数字或“查看支撑数据”可进入同口径筛选视图。" type="info" :closable="false" style="margin:16px 0" />
        <div v-for="(rows, section) in detail.content?.sections" :key="section" class="report-section">
          <h3>{{ sectionLabel(String(section)) }}</h3>
          <el-table :data="rows as any[]" border size="small">
            <el-table-column v-for="key in Object.keys((rows as any[])?.[0] || {}).filter(key=>key!=='_drilldown')" :key="key" :prop="key" :label="fieldLabel(key)"><template #default="{row}">{{ displayValue(key,row[key]) }}</template></el-table-column>
            <el-table-column label="支撑数据" width="110" fixed="right"><template #default="{row}"><el-button link type="primary" @click="openDrilldown(row._drilldown)">查看</el-button></template></el-table-column>
          </el-table>
        </div>
      </template>
    </el-dialog>
    <el-dialog v-model="versionDialog" title="生成版本上线观察报告" width="460px"><el-form label-width="90px"><el-form-item label="包名"><el-input v-model="versionForm.packageName" /></el-form-item><el-form-item label="版本号"><el-input v-model="versionForm.version" /></el-form-item><el-form-item label="发布日期"><el-date-picker v-model="versionForm.releasedAt" type="date" value-format="YYYY-MM-DD" /></el-form-item></el-form><template #footer><el-button @click="versionDialog=false">取消</el-button><el-button type="primary" @click="confirmVersion">生成</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted, onUnmounted } from 'vue'
import { Loading, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import DataTable from '@/components/common/DataTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import { cancelReportTask, deleteReport, downloadReportPdf, generateReport as createReport, getReportDetail, getReportList, getReportTask, getReportTasks, retryReportTask } from '@/api/report'

const loading = ref(false)
const reports = ref<any[]>([])
const pagination = reactive({ page: 1, size: 20, total: 0 })
const detail = ref<any>(null)
const detailVisible = ref(false)
const detailLoading = ref(false)
const pendingDetailTitle = ref('')
const generating = ref(false)
const generatingLabel = ref('报告')
const activeTask=ref<any>(null)
const elapsedSeconds=ref(0)
let taskPollTimer:number|undefined
const recentTasks=ref<any[]>([])
const versionDialog=ref(false);const versionForm=reactive({packageName:'',version:'',releasedAt:''});const auth=useAuthStore();const router=useRouter()

function reportTypeLabel(t: string): string {
  return ({
    DAILY_INGEST:'每日数据接入',JIRA_IMPORT_RECONCILIATION:'Jira导入对账',WEEKLY_TREND:'每周趋势',VERSION_RELEASE:'版本观察',
    QUALITY_LINKAGE:'质量关联',REDBLACK_ANALYSIS:'红黑榜分析',RISK_BRIEF:'风险简报',
  } as any)[t] || t
}
function reportTypeTag(t: string): string {
  return t === 'DAILY_INGEST' ? 'primary' : t === 'WEEKLY_TREND' ? 'success' : t === 'VERSION_RELEASE' ? 'warning' : 'info'
}
function formatDateTime(value:any):string { return value ? new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeStyle:'medium',timeZone:'Asia/Shanghai'}).format(new Date(value)) : '无有效数据' }
function openDrilldown(target:any){if(!target?.path)return ElMessage.warning('该指标暂无支撑数据入口');detailVisible.value=false;router.push({path:target.path,query:target.query||{}})}
function fieldLabel(key:string):string{return({title:'问题',channels:'来源渠道',channel:'来源渠道',clusterId:'问题簇编号',recordCount:'反馈数',channelCount:'渠道数',date:'日期',count:'数量',previousCount:'上期数量',changeRate:'环比变化率(%)',module:'功能模块',severity:'严重程度',version:'版本',status:'状态',qualityRate:'质量通过率(%)',requiredCompletenessRate:'必填完整率(%)',mappingSuccessRate:'字段映射成功率(%)',warning:'风险提示',alertId:'告警编号',level:'级别',ownerTeam:'主责团队',riskLevel:'风险级别',positiveCount:'正向反馈数',negativeCount:'负向反馈数',exclusiveSignal:'单渠道信号',sdk:'SDK',jira:'Jira',quality:'质量平台',redblack:'红黑榜',batchId:'导入批次',totalRows:'总行数',successRows:'成功行数',failedRows:'失败行数',skippedRows:'跳过行数',mappingVersion:'映射版本',taskType:'任务类型',packageName:'应用包名',appVersion:'应用版本',listType:'榜单类型',sentiment:'情感倾向'}as Record<string,string>)[key]||key.replace(/([a-z0-9])([A-Z])/g,'$1 $2')}
function displayValue(key:string,value:any):string{if(value===null||value===undefined||value==='')return '-';const labels:Record<string,string>={VIDEO_PLAYBACK:'视频播放',WRONG_QUESTION_BOOK:'错题本',CAMERA_MIC:'相机与麦克风',SYNC_COURSE:'同步课程',AI_TUTOR:'AI助教',RED:'红榜',BLACK:'黑榜',POSITIVE:'正向',NEGATIVE:'负向'};if(key==='warning'||key==='exclusiveSignal')return Number(value)===1?'是':'否';return labels[String(value)]||String(value)}
function degradedModelLabel(row:any):string {
  return row?.promptVersion === 'sql-fallback-v1' ? '未调用（模板缺失）' : row?.degraded ? '调用失败' : '未调用'
}
function degradedMessage(row:any):string {
  if (row?.promptVersion === 'sql-fallback-v1') return 'AI文字解读未生成：生成时报告模板尚未配置，本报告仅展示已校验的统计数据。'
  return row?.generatedSummary || 'AI文字解读生成失败：模型服务调用异常，本报告仅展示已校验的统计数据。'
}

async function loadReports() {
  loading.value = true
  try { const [data,tasks] = await Promise.all([getReportList({ page: pagination.page, size: pagination.size }),getReportTasks()]); reports.value = data.records; pagination.total = data.total;recentTasks.value=tasks||[] }
  finally { loading.value = false }
}
async function changePage(page:number){pagination.page=page;await loadReports()}
async function changePageSize(size:number){pagination.size=size;pagination.page=1;await loadReports()}
const elapsedText=computed(()=>{const m=Math.floor(elapsedSeconds.value/60);const s=elapsedSeconds.value%60;return m?`${m}分${String(s).padStart(2,'0')}秒`:`${s}秒`})
const taskProgress=computed(()=>activeTask.value?.cancelRequested?100:Math.min(92,12+Math.floor(elapsedSeconds.value/3)))
function stopTaskPolling(){if(taskPollTimer!==undefined){window.clearTimeout(taskPollTimer);taskPollTimer=undefined}}
function scheduleTaskPoll(){stopTaskPolling();taskPollTimer=window.setTimeout(pollActiveTask,1000)}
async function pollActiveTask(){
  if(!activeTask.value)return
  elapsedSeconds.value++
  try{
    const task=await getReportTask(activeTask.value.id);activeTask.value=task
    if(task.status==='COMPLETED'){stopTaskPolling();generating.value=false;activeTask.value=null;ElMessage.success(`${generatingLabel.value}生成完成`);await loadReports();return}
    if(task.status==='CANCELLED'){stopTaskPolling();generating.value=false;activeTask.value=null;ElMessage.info('报告生成已取消');await loadReports();return}
    if(task.status==='FAILED'){stopTaskPolling();generating.value=false;activeTask.value=null;ElMessage.error('报告生成失败，请在最近报告任务中查看原因');await loadReports();return}
  }finally{if(activeTask.value)scheduleTaskPoll()}
}
async function runGeneration(type:string,options:Record<string,any>={}){
  if(generating.value)return
  generating.value=true;generatingLabel.value=reportTypeLabel(type)
  try{const task=await createReport(type,options);activeTask.value=task;elapsedSeconds.value=0;scheduleTaskPoll();await loadReports()}
  catch(error){generating.value=false;throw error}
}
async function generateReport(type:string){if(type==='VERSION_RELEASE'){versionDialog.value=true;return}await runGeneration(type)}
async function confirmVersion(){if(!versionForm.packageName||!versionForm.version||!versionForm.releasedAt)return ElMessage.warning('请完整填写版本发布信息');versionDialog.value=false;await runGeneration('VERSION_RELEASE',{...versionForm})}
async function retryTask(row:any){const result=await retryReportTask(row.id);activeTask.value=result;generating.value=true;generatingLabel.value=reportTypeLabel(result.reportType||row.reportType);elapsedSeconds.value=0;scheduleTaskPoll();ElMessage.success('报告任务已重试');await loadReports()}
async function cancelGeneration(){
  if(!activeTask.value)return
  await ElMessageBox.confirm(`确定取消${generatingLabel.value}生成吗？已生成的中间结果不会保留。`,'取消报告生成',{type:'warning',confirmButtonText:'取消生成',cancelButtonText:'继续生成'})
  activeTask.value=await cancelReportTask(activeTask.value.id)
  if(activeTask.value.status==='CANCELLED'){stopTaskPolling();generating.value=false;activeTask.value=null;ElMessage.info('报告生成已取消');await loadReports()}
}
async function viewReport(row: any) {
  detail.value=null;pendingDetailTitle.value=row.title;detailVisible.value=true;detailLoading.value=true
  try{detail.value=await getReportDetail(row.id)}
  catch(error){detailVisible.value=false;throw error}
  finally{detailLoading.value=false}
}
async function exportPDF(row: any) {
  const blob = await downloadReportPdf(row.id); const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = `report-${row.id}.pdf`; a.click(); URL.revokeObjectURL(url)
}
async function removeReport(row:any){
  await ElMessageBox.confirm(`确定永久删除报告“${row.title}”吗？风险简报关联的通知、回执和投递明细也会一并删除，且无法恢复。`,'永久删除报告',{type:'warning',confirmButtonText:'永久删除',cancelButtonText:'取消',confirmButtonClass:'el-button--danger'})
  await deleteReport(row.id);ElMessage.success('报告及关联记录已永久删除');await loadReports()
}
function sectionLabel(key:string){return({ingestOverview:'接入总览',importBatchStatus:'导入批次状态',jiraImportBatches:'Jira导入批次',jiraPassRecords:'Jira脱敏通过记录',reconciliationMethodology:'对账口径',maskingStatus:'脱敏情况',dataQuality:'数据质量',pendingTasks:'待处理任务',dailyTrend:'趋势总览',mergedBus:'合并趋势',channelComparison:'渠道对比',categorySeverity:'问题类型×严重度',versionTop10:'应用版本TOP10',hotClusters:'热点问题TOP10',crossChannelClusters:'跨渠道关联',sentimentTrend:'情感趋势',releaseMetadata:'版本信息',versionOverview:'版本概况',baselineComparison:'对比基线',concentration:'问题集中度',linkageOverview:'关联概览',qualityOnly:'质量单向问题',userOnlyBlindSpots:'质量检测盲区',linkageMatrix:'关联矩阵',redblackOverview:'红黑榜概况',resonance:'共振检测',blackTop10:'黑榜问题TOP10',channelMatrix:'渠道共振矩阵',positiveModules:'红榜提取',burstCandidates:'刷屏/水军候选',riskLevel:'风险等级',activeAlerts:'活跃告警',crossChannelRisks:'跨渠道风险',hotProblems:'热点问题',attentionItems:'需关注项'}as any)[key]||key}
onMounted(async()=>{await loadReports();const running=recentTasks.value.find(task=>['PENDING','RUNNING','RETRY_PENDING'].includes(task.status));if(running){activeTask.value=running;generating.value=true;generatingLabel.value=reportTypeLabel(running.reportType);elapsedSeconds.value=Math.max(0,Math.floor((Date.now()-new Date(running.createdAt).getTime())/1000));scheduleTaskPoll()}})
onUnmounted(stopTaskPolling)
</script>

<style scoped>:deep(.el-table .el-table__cell){padding-left:4px;padding-right:4px}:deep(.el-table .cell){padding-left:4px;padding-right:4px}.generation-task-card{display:flex;align-items:center;gap:16px;margin-bottom:14px;padding:16px 18px;border:1px solid #b9dcff;border-left:5px solid #409eff;border-radius:8px;background:linear-gradient(90deg,#ecf5ff,#fff);box-shadow:0 3px 12px rgba(64,158,255,.1)}.task-icon{display:grid;place-items:center;width:42px;height:42px;border-radius:50%;background:#409eff;color:#fff;font-size:24px;flex:0 0 auto}.task-content{flex:1;min-width:0}.task-heading{display:flex;align-items:baseline;gap:12px;margin-bottom:9px}.task-heading strong{font-size:17px;color:#303133}.task-heading span,.task-content p{font-size:13px;color:#606266}.task-content p{margin:7px 0 0}.report-actions{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;white-space:nowrap}.report-actions :deep(.el-button+.el-button){margin-left:0}.report-section{margin-top:18px}.ai-summary{background:#f5f7fa;border-left:4px solid #909399;padding:10px 14px;color:#606266;font-style:italic}.ai-summary h3{margin:0 0 8px}.detail-loading{min-height:360px;padding:12px 6px}.detail-loading p{text-align:center;color:#606266;margin-top:18px}@media(max-width:760px){.generation-task-card{align-items:flex-start;flex-wrap:wrap}.task-content{min-width:calc(100% - 60px)}.generation-task-card>.el-button{margin-left:58px}}</style>
