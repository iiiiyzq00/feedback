<template>
  <div class="page-container">
    <PageHeader title="数据接入管理" :description="viewDescription"
      ><template #actions
        ><el-button type="primary" :icon="Upload" @click="startUpload"
          >上传文件</el-button
        ><el-dropdown
          :disabled="!!downloadingChannel"
          @command="downloadTemplate"
          ><el-button :loading="!!downloadingChannel"
            >下载标准模板<el-icon class="dropdown-icon"
              ><ArrowDown /></el-icon></el-button
          ><template #dropdown
            ><el-dropdown-menu
              ><el-dropdown-item command="SDK"
                >SDK 模拟数据模板</el-dropdown-item
              ><el-dropdown-item command="JIRA">Jira 模板</el-dropdown-item
              ><el-dropdown-item command="QUALITY"
                >质量平台模板</el-dropdown-item
              ><el-dropdown-item command="REDBLACK"
                >红黑榜模板</el-dropdown-item
              ></el-dropdown-menu
            ></template
          ></el-dropdown
        ></template
      ></PageHeader
    >
    <el-card shadow="never" class="jira-sync-card">
      <template #header>
        <div class="quality-title"><strong>400 Jira API只读同步</strong><div>
          <el-button v-if="jiraStatus?.attachmentFailedCount" :loading="jiraSyncing" @click="retryJiraEvidence">重试证据拉取</el-button>
          <el-button type="primary" :loading="jiraSyncing" :disabled="!jiraStatus?.ready || ['QUEUED','RUNNING'].includes(jiraStatus?.status || '')" @click="syncJiraNow">立即同步</el-button>
        </div>
        </div>
      </template>
      <el-descriptions :column="3" border v-loading="jiraStatusQuery.isFetching.value">
        <el-descriptions-item label="连接状态"><StatusBadge :status="jiraStatus?.status || 'DISABLED'" /></el-descriptions-item>
        <el-descriptions-item label="最近拉取成功">{{ jiraStatus?.lastSuccessAt || "—" }}</el-descriptions-item>
        <el-descriptions-item label="同步水位">{{ jiraStatus?.watermark || "—" }}</el-descriptions-item>
        <el-descriptions-item label="最近批次">{{ jiraStatus?.lastBatchId || "—" }}</el-descriptions-item>
        <el-descriptions-item label="证据待拉取">{{ jiraStatus?.attachmentPendingCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="证据拉取失败">{{ jiraStatus?.attachmentFailedCount ?? 0 }}</el-descriptions-item>
      </el-descriptions>
      <div class="jira-backfill">
        <el-date-picker v-model="jiraBackfillRange" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
          range-separator="至" start-placeholder="补数开始时间" end-placeholder="补数结束时间" />
        <el-button :loading="jiraSyncing" :disabled="!jiraStatus?.ready || !jiraBackfillRange || ['QUEUED','RUNNING'].includes(jiraStatus?.status || '')" @click="syncJiraBackfill">按时间补数</el-button>
      </div>
      <el-alert v-if="jiraStatus && !jiraStatus.ready" type="info" :closable="false" show-icon
        title="Jira API尚未启用或外部契约未配置；现有Excel/CSV导入继续可用。" class="jira-sync-hint" />
      <el-alert v-else-if="jiraStatus?.lastErrorCode" type="warning" :closable="false" show-icon
        :title="`最近同步失败：${jiraStatus.lastErrorCode}`" class="jira-sync-hint" />
      <el-alert v-else-if="jiraStatus?.attachmentFailedCount" type="warning" :closable="false" show-icon
        :title="`有 ${jiraStatus.attachmentFailedCount} 条 Jira 证据拉取任务失败，请检查附件链路配置或外部附件状态。`" class="jira-sync-hint" />
    </el-card>
    <el-card shadow="never" class="quality-overview">
      <template #header><div class="quality-title"><strong>渠道数据质量（当前有效数据）</strong><el-button text type="primary" @click="refreshQuality">重新计算</el-button></div></template>
      <el-table :data="channelQuality" v-loading="qualityQuery.isFetching.value" empty-text="暂无渠道样本">
        <el-table-column prop="channel" label="渠道" width="100"><template #default="{ row }">{{ qualityChannelLabel(row.channel) }}</template></el-table-column>
        <el-table-column label="综合分" width="90"><template #default="{ row }">{{ qualityRate(row.qualityScore) }}</template></el-table-column>
        <el-table-column label="必填完整" width="100"><template #default="{ row }">{{ qualityRate(row.requiredCompletenessRate) }}</template></el-table-column>
        <el-table-column label="时间/枚举合法" min-width="130"><template #default="{ row }">{{ qualityRate(row.timeValidRate) }} / {{ qualityRate(row.enumValidRate) }}</template></el-table-column>
        <el-table-column label="脱敏通过" width="100"><template #default="{ row }">{{ qualityRate(row.maskingPassRate) }}</template></el-table-column>
        <el-table-column label="主键重复" width="100"><template #default="{ row }">{{ qualityRate(row.primaryKeyDuplicateRate) }}</template></el-table-column>
        <el-table-column label="跨渠道疑似重复" min-width="130"><template #default="{ row }">{{ qualityRate(row.crossChannelSuspectedDuplicateRate) }}</template></el-table-column>
        <el-table-column label="内容异常/隔离" width="130"><template #default="{ row }">{{ row.anomalyCount }} / {{ row.quarantineCount }}</template></el-table-column>
        <el-table-column prop="accessFailureCount" label="接入处理失败" width="120" />
        <el-table-column label="阈值" width="90"><template #default="{ row }"><el-tag :type="qualityThresholdType(row.thresholdStatus)">{{ qualityThresholdLabel(row.thresholdStatus) }}</el-tag></template></el-table-column>
      </el-table>
    </el-card>
    <el-tabs
      :model-value="status"
      class="status-tabs"
      @update:model-value="changeStatus"
    >
      <el-tab-pane
        v-for="item in statusViews"
        :key="item.value"
        :label="item.label"
        :name="item.value"
      />
    </el-tabs>
    <div class="batch-governance-toolbar">
      <el-switch v-model="includeGoverned" active-text="显示已清理批次" />
      <el-button v-if="auth.isAdmin" text type="primary" @click="cleanupGovernedBatches">清理重复/零变更批次</el-button>
    </div>
    <el-alert
      v-if="status"
      :title="currentView.hint"
      type="info"
      :closable="false"
      show-icon
      class="view-hint"
    />
    <ImportBatchList
      :batches="batches"
      :loading="isFetching"
      :error="queryError"
      :pagination="pagination"
      :is-admin="auth.isAdmin"
      :current-status="status"
      :empty-text="emptyText"
      @retry="refetch"
      @page-change="changePage"
      @size-change="changeSize"
      @detail="viewDetail"
      @export="exportBatch"
      @rollback="rollbackBatch"
      @retry-batch="retryBatch"
      @continue-mapping="continueMapping"
      @new-upload="startUpload"
      @empty-action="emptyAction"
    />
    <ImportUploadDialog
      v-model:visible="uploadVisible"
      :resume-batch-id="mappingBatch?.batchId"
      :resume-channel="mappingBatch?.channel"
      @completed="invalidateBatches"
    />
    <ImportBatchDetail
      v-model:visible="detailVisible"
      :detail="batchDetail"
      :loading="detailFetching"
      :error="detailError"
      :is-admin="auth.isAdmin"
      @retry="refetchDetail"
      @download-errors="downloadErrors"
      @remap="continueMapping"
      @retry-technical="retryTechnical"
    />
  </div>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch, watchEffect } from "vue";
import PageHeader from "@/components/common/PageHeader.vue";
import { ArrowDown, Upload } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import { useRoute, useRouter } from "vue-router";
import ImportBatchList from "./components/ImportBatchList.vue";
import ImportUploadDialog from "./components/ImportUploadDialog.vue";
import ImportBatchDetail from "./components/ImportBatchDetail.vue";
import StatusBadge from "@/components/common/StatusBadge.vue";
import {
  getImportBatches,
  getImportBatchDetail,
  rollbackImport,
  retryImport,
  retryImportTechnicalFailures,
  downloadImportTemplate,
  exportImportReconciliation,
  downloadImportErrors,
  type ImportBatchView,
  type ImportChannel,
  getChannelQuality,
  refreshChannelQuality,
  getJiraSyncStatus,
  enqueueJiraSync,
  retryJiraAttachmentTasks,
  cleanupImportBatches,
} from "@/api/import";
import { queryKeys } from "@/query/client";
import { useAuthStore } from "@/stores/auth";
const auth = useAuthStore(),
  client = useQueryClient(),
  route = useRoute(),
  router = useRouter(),
  uploadVisible = ref(false),
  detailVisible = ref(false),
  selectedBatchId = ref(""),
  mappingBatch = ref<ImportBatchView | null>(null),
  downloadingChannel = ref<ImportChannel | null>(null);
const includeGoverned=ref(false);
const pagination = reactive({ page: 1, size: 20, total: 0 });
const status = computed(() => String(route.query.status || ""));
const statusViews = [
  {
    value: "",
    label: "全部批次",
    description: "通过Jira API只读同步或上传文件接入数据，统一查看批次处理进度。",
    hint: "",
  },
  {
    value: "PROCESSING",
    label: "处理中",
    description: "查看当前正在执行的导入批次。",
    hint: "系统会自动刷新处理中批次的最新进度。",
  },
  {
    value: "PARTIAL_SUCCESS",
    label: "部分成功",
    description: "定位包含失败行或待审核行的批次。",
    hint: "批次已有部分数据导入成功，请查看行级错误后决定重试。",
  },
  {
    value: "FAILED",
    label: "导入失败",
    description: "诊断未能完成的导入批次并安全重试。",
    hint: "查看失败阶段和错误报告，修复源文件或配置后重新提交。",
  },
  {
    value: "AWAITING_MAPPING",
    label: "字段变更待处理",
    description: "处理表头新增、删除或含义变化，并发布新的映射版本。",
    hint: "仅字段结构偏离当前已发布模板时进入此处；标准文件会自动映射并导入。",
  },
  {
    value: "COMPLETED",
    label: "已完成",
    description: "查看已完成批次并执行对账导出。",
    hint: "已完成批次可查看详情、导出对账结果或在允许期限内回滚。",
  },
];
const currentView = computed(
  () => statusViews.find((v) => v.value === status.value) || statusViews[0],
);
const viewDescription = computed(() => currentView.value.description);
const emptyText = computed(
  () =>
    (
      ({
        AWAITING_MAPPING: "当前没有字段变更待处理的批次",
        PROCESSING: "当前没有正在处理的批次",
        PARTIAL_SUCCESS: "当前没有部分成功批次",
        FAILED: "当前没有导入失败批次",
        COMPLETED: "当前没有已完成批次",
      }) as Record<string, string>
    )[status.value] || "尚未上传任何表格文件",
);
function changeStatus(value: string | number) {
  router.replace({ query: { ...route.query, status: value || undefined } });
}
function emptyAction() {
  if (status.value) changeStatus("");
  else {
    mappingBatch.value = null;
    uploadVisible.value = true;
  }
}
function continueMapping(row: ImportBatchView) {
  mappingBatch.value = row;
  uploadVisible.value = true;
}
function startUpload() {
  mappingBatch.value = null;
  uploadVisible.value = true;
}
watch(status, () => {
  pagination.page = 1;
});
watch(includeGoverned, () => { pagination.page = 1; });
const batchQuery = useQuery({
  queryKey: computed(() =>
    queryKeys.imports.batches(pagination.page, pagination.size, status.value, includeGoverned.value),
  ),
  queryFn: () =>
    getImportBatches({
      page: pagination.page,
      size: pagination.size,
      status: status.value || undefined,
      includeGoverned: includeGoverned.value,
    }),
  refetchInterval: (q) =>
    q.state.data?.records?.some(
      (b: ImportBatchView) => b.status === "PROCESSING",
    )
      ? 3000
      : false,
  placeholderData: (old) => old,
});
const qualityQuery=useQuery({queryKey:["imports","channel-quality"],queryFn:getChannelQuality});
const jiraStatusQuery=useQuery({queryKey:["imports","jira-sync-status"],queryFn:getJiraSyncStatus,refetchInterval:5000});
const jiraStatus=computed(()=>jiraStatusQuery.data.value);
const jiraSyncing=ref(false);
const jiraBackfillRange=ref<[string,string]|null>(null);
async function syncJiraNow(){jiraSyncing.value=true;try{const result=await enqueueJiraSync();ElMessage.success(result.accepted?'Jira同步任务已排队':'已有Jira同步任务正在执行');await jiraStatusQuery.refetch();await batchQuery.refetch()}finally{jiraSyncing.value=false}}
async function syncJiraBackfill(){if(!jiraBackfillRange.value)return;jiraSyncing.value=true;try{const [from,to]=jiraBackfillRange.value;const result=await enqueueJiraSync({from,to});ElMessage.success(result.accepted?'Jira补数任务已排队':'已有Jira同步任务正在执行');if(result.accepted)jiraBackfillRange.value=null;await jiraStatusQuery.refetch();await batchQuery.refetch()}finally{jiraSyncing.value=false}}
async function retryJiraEvidence(){jiraSyncing.value=true;try{const result=await retryJiraAttachmentTasks();ElMessage.success(`已重新排队 ${result.retried} 条证据拉取任务`);await jiraStatusQuery.refetch()}finally{jiraSyncing.value=false}}
const channelQuality=computed(()=>qualityQuery.data.value??[]);
async function refreshQuality(){await refreshChannelQuality();await qualityQuery.refetch();ElMessage.success("渠道质量指标已重新计算")}
function qualityRate(value?:number|null){const numeric=Number(value);return value==null||!Number.isFinite(numeric)?'—':`${(numeric*100).toFixed(1)}%`}
function qualityThresholdLabel(value:string){return value==='NO_DATA'?'无样本':value}
function qualityThresholdType(value:string){return value==='ALERT'?'danger':value==='NO_DATA'?'info':'success'}
async function cleanupGovernedBatches(){
  const {value}=await ElMessageBox.prompt("请输入清理原因（至少10字）；批次、行、对账和审计事实不会物理删除。","批次治理",{inputValidator:v=>v.trim().length>=10||"清理原因至少10字",confirmButtonText:"确认清理",cancelButtonText:"取消"})
  const result=await cleanupImportBatches(value.trim())
  ElMessage.success(`已归档 ${result.supersededBatches} 个被替代批次、${result.noChangeBatches} 个零变更批次`)
  await batchQuery.refetch()
}
const batches = computed(() => batchQuery.data.value?.records ?? []);
const isFetching = computed(() => batchQuery.isFetching.value);
const queryError = computed(() =>
  batchQuery.error.value ? "暂时无法获取导入批次，请稍后重试" : "",
);
watchEffect(() => {
  pagination.total = batchQuery.data.value?.total ?? 0;
});
const detailQuery = useQuery({
  queryKey: computed(() => queryKeys.imports.detail(selectedBatchId.value)),
  queryFn: () => getImportBatchDetail(selectedBatchId.value),
  enabled: computed(() => detailVisible.value && !!selectedBatchId.value),
});
const batchDetail = computed(() =>
  detailQuery.data.value
    ? { ...(detailQuery.data.value as any).batch, ...detailQuery.data.value }
    : null,
);
const detailFetching = computed(() => detailQuery.isFetching.value);
const detailError = computed(() =>
  detailQuery.error.value ? "暂时无法获取批次详情" : "",
);
function changePage(v: number) {
  pagination.page = v;
}
function changeSize(v: number) {
  pagination.size = v;
  pagination.page = 1;
}
function viewDetail(row: ImportBatchView) {
  selectedBatchId.value = row.batchId;
  detailVisible.value = true;
}
function invalidateBatches() {
  mappingBatch.value = null;
  client.invalidateQueries({ queryKey: queryKeys.imports.all });
}
function refetch() {
  batchQuery.refetch();
}
function refetchDetail() {
  detailQuery.refetch();
}
function save(blob: Blob, name: string) {
  if (!(blob instanceof Blob) || !blob.size) throw new Error("下载文件为空");
  const u = URL.createObjectURL(blob),
    a = document.createElement("a");
  a.href = u;
  a.download = name;
  a.style.display = "none";
  document.body.appendChild(a);
  a.click();
  a.remove();
  globalThis.setTimeout(() => URL.revokeObjectURL(u), 1000);
}
async function downloadTemplate(ch: ImportChannel) {
  downloadingChannel.value = ch;
  try {
    save(
      await downloadImportTemplate(ch),
      `${ch.toLowerCase()}-import-template.csv`,
    );
    ElMessage.success(`${channelLabel(ch)}模板已开始下载`);
  } finally {
    downloadingChannel.value = null;
  }
}
function channelLabel(ch: ImportChannel) {
  return (
    {
      SDK: "SDK 模拟数据",
      JIRA: "Jira",
      QUALITY: "质量平台",
      REDBLACK: "红黑榜",
    } as Record<ImportChannel, string>
  )[ch];
}
function qualityChannelLabel(ch:ImportChannel|'STATION_FEEDBACK'){return ch==='SDK'?'SDK':ch==='STATION_FEEDBACK'?'学习机反馈':channelLabel(ch)}
async function exportBatch(row: ImportBatchView) {
  save(
    await exportImportReconciliation(row.batchId),
    `batch_${row.batchId}_reconciliation.xlsx`,
  );
}
async function downloadErrors() {
  if (selectedBatchId.value)
    save(
      await downloadImportErrors(selectedBatchId.value),
      `${selectedBatchId.value}_safe_errors.csv`,
    );
}
async function rollbackBatch(row: ImportBatchView) {
  try {
    await ElMessageBox.confirm(
      `确定回滚批次 ${row.batchId}？此操作将清除该批次导入的数据。`,
      "确认回滚",
      { type: "warning" },
    );
    await rollbackImport(row.batchId);
    ElMessage.success("回滚成功");
    invalidateBatches();
  } catch {
    /* 用户取消 */
  }
}
async function retryBatch(row: ImportBatchView) {
  await retryImport(row.batchId);
  ElMessage.success("已重新提交");
  invalidateBatches();
}
async function retryTechnical(row: ImportBatchView) {
  await retryImportTechnicalFailures(row.batchId);
  ElMessage.success("技术失败行已重新提交");
  detailVisible.value = false;
  invalidateBatches();
}
</script>
<style scoped>
.status-tabs {
  margin-bottom: 8px;
}
.quality-overview{margin-bottom:16px}.quality-title{display:flex;align-items:center;justify-content:space-between}.batch-governance-toolbar{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin:-8px 0 12px}
.jira-sync-card{margin-bottom:16px}.jira-sync-hint{margin-top:12px}.jira-backfill{display:flex;gap:10px;align-items:center;margin-top:12px}
.view-hint {
  margin-bottom: 14px;
}
.page-intro {
  margin-top: 5px;
  color: var(--text-secondary);
  font-size: 13px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.dropdown-icon {
  margin-left: 6px;
}
</style>
