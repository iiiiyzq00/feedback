<template>
  <el-dialog
    :model-value="visible"
    :title="resumeBatchId ? '提交字段映射变更' : '上传反馈数据'"
    width="720px"
    @update:model-value="$emit('update:visible', $event)"
    @close="reset"
  >
    <el-upload
      v-if="!resumeBatchId"
      ref="uploadRef"
      drag
      :auto-upload="false"
      :limit="1"
      :on-change="fileChanged"
      accept=".xlsx,.xls,.csv"
      ><el-icon :size="48" color="#409eff"><UploadFilled /></el-icon>
      <div class="upload-text">拖拽文件到此处，或点击选择</div>
      <div class="hint">支持 .xlsx、.xls 和 .csv，最大 20MB</div></el-upload
    >
    <el-form v-if="!resumeBatchId" label-width="80px" class="form"
      ><el-form-item label="数据渠道" required
        ><el-select v-model="channel" :disabled="!!preview" class="full"
          ><el-option label="SDK 模拟数据" value="SDK" /><el-option
            label="400 Jira"
            value="JIRA" /><el-option
            label="质量平台"
            value="QUALITY" /><el-option
            label="红黑榜"
            value="REDBLACK" /></el-select></el-form-item
      ><el-form-item label="数据范围"
        ><el-date-picker
          v-model="dates"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期" /></el-form-item
    ></el-form>
    <el-alert
      v-if="channel === 'SDK'"
      type="warning"
      :closable="false"
      show-icon
      title="SDK模拟导入仅用于演示测试，不经过真实设备链路。"
    /><el-alert
      class="notice"
      type="info"
      :closable="false"
      title="系统优先使用当前已发布的字段映射版本自动处理；仅在表头发生变化时需要反馈并发布新版本。质量平台业务键由客户地址、故障现象确认、商返申请日期派生。"
    />
    <section v-if="preview" class="preview">
      <el-divider /><el-alert
        :title="preview.mappingMode === 'AUTO_VERSIONED'
          ? `已应用映射版本 ${preview.templateVersion || '-'}，任务已进入处理队列`
          : `检测到字段变化，请核对后发布新映射版本（当前 ${preview.templateVersion || '-'}）`"
        type="success"
        :closable="false"
        show-icon
      />
      <el-descriptions :column="1" border size="small" class="hashes"
        ><el-descriptions-item label="原始文件指纹"
          ><code>{{ preview.contentSha256 || "-" }}</code></el-descriptions-item
        ><el-descriptions-item label="判重结果">{{
          duplicateName(preview.duplicateResult)
        }}</el-descriptions-item></el-descriptions
      >
      <el-form-item v-if="(preview.sheets?.length || 0) > 1" label="工作表"
        ><el-select v-model="sheet" @change="reloadSheet"
          ><el-option
            v-for="s in preview.sheets"
            :key="s.name"
            :value="s.name"
            :label="`${s.name}（${s.dataRows}行）`" /></el-select
      ></el-form-item>
      <el-alert v-if="preview.mappingDecisionReason" class="notice" type="info" :closable="false" :title="preview.mappingDecisionReason" />
      <template v-if="preview.mappingMode !== 'AUTO_VERSIONED'">
      <h4>字段映射变更</h4>
      <div class="mapping">
        <div v-for="row in mappingRows" :key="row.target" class="mapping-row">
          <el-select
            v-model="preview.recommendedMapping[row.target]"
            clearable
            placeholder="选择源字段"
            ><el-option
              v-for="h in preview.headers"
              :key="h"
              :label="h"
              :value="h" /></el-select
          ><el-icon><ArrowRight /></el-icon><span>{{ row.target }}</span
          ><el-tag :type="confidenceType(row.confidence)" size="small"
            >{{ Math.round(row.confidence * 100) }}%</el-tag
          >
        </div>
      </div>
      </template>
      <h4>数据预览（前10行）</h4>
      <el-table :data="previewRows" size="small" max-height="240" border
        ><el-table-column
          v-for="h in preview.headers"
          :key="h"
          :prop="h"
          :label="h"
          min-width="120"
      /></el-table>
    </section>
    <template #footer
      ><el-button @click="$emit('update:visible', false)">取消</el-button
      ><el-button
        v-if="preview && preview.mappingMode !== 'AUTO_VERSIONED'"
        type="primary"
        :loading="submitting"
        :disabled="preview.duplicateResult !== 'NEW'"
        @click="confirm"
        >发布新版本并导入（{{ preview.totalRows }}行）</el-button
      ></template
    >
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { UploadFilled, ArrowRight } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import {
  uploadFile,
  confirmImport,
  uploadImportChunk,
  completeImportChunks,
  getImportMappingPreview,
  type ImportChannel,
  type ImportUploadResult,
} from "@/api/import";
const props = defineProps<{
  visible: boolean;
  resumeBatchId?: string;
  resumeChannel?: ImportChannel;
}>();
const emit = defineEmits<{ "update:visible": [boolean]; completed: [] }>();
const uploadRef = ref();
const file = ref<File | null>(null);
const channel = ref<ImportChannel>("JIRA");
const dates = ref<[string, string] | null>(null);
const sheet = ref("");
const preview = ref<ImportUploadResult | null>(null);
const submitting = ref(false);
watch(
  () => [props.visible, props.resumeBatchId] as const,
  async ([visible, batchId]) => {
    if (!visible || !batchId) return;
    channel.value = props.resumeChannel || "JIRA";
    preview.value = await getImportMappingPreview(batchId);
    sheet.value = preview.value.availableSheets?.[0] || "";
  },
);
const common = [
  "source_record_id",
  "description",
  "title",
  "function_module",
  "app_version",
  "source_event_time",
  "source_update_time",
  "severity_level",
  "problem_type",
  "processing_status",
  "source_url",
  "attachment_urls",
  "attachment_sha256",
  "attachment_size",
];
const specific: Record<ImportChannel, string[]> = {
  SDK: [
    "app_id",
    "package_name",
    "os_version",
    "network_type",
    "device_model",
    "screen_orientation",
    "app_session_id",
    "battery_level",
    "storage_available_mb",
    "trigger_type",
    "input_methods",
    "app_internal_logs",
    "crash_stack",
    "account_context",
    "course_id",
    "question_id",
    "order_id",
    "page_route",
    "business_id",
    "api_request_summary",
    "state_machine",
    "custom_events",
    "private_file_manifest",
  ],
  JIRA: [
    "jira_issue_type",
    "jira_issue_id",
    "status",
    "priority",
    "project_key",
    "project_name",
    "project_type",
    "project_lead",
    "project_description",
    "project_url",
    "resolution",
    "assignee",
    "reporter",
    "creator",
    "last_viewed_at",
    "resolved_at",
    "affected_version",
    "fix_version",
    "due_date",
    "votes",
    "label_1",
    "label_2",
    "label_3",
    "environment",
    "watchers",
    "worklog_time",
    "original_estimate_raw",
    "remaining_estimate_raw",
    "time_spent_raw",
    "work_ratio",
    "sigma_original_estimate",
    "sigma_remaining_estimate",
    "sigma_time_spent",
    "security_level",
    "inward_duplicate_link",
    "outward_duplicate_link",
  ],
  QUALITY: [
    "repair_region",
    "quality_province",
    "quality_city",
    "quality_county",
    "customer_address",
    "district",
    "application_type",
    "exchange_request",
    "return_reason",
    "fault_confirmed",
    "shipping_date",
  ],
  REDBLACK: [
    "original_id",
    "uid",
    "author",
    "url",
    "source_platform",
    "pub_type",
    "section",
    "folder_name",
    "origin_message",
    "keyword",
    "emotion",
    "push_time",
    "redblack_create_time",
    "cover_image_url",
    "image_urls",
    "video_urls",
    "is_deleted",
  ],
};
const mappingRows = computed(() =>
  [...common, ...specific[channel.value]].map((target) => ({
    target,
    confidence: preview.value?.mappingConfidence?.[target] ?? 0,
  })),
);
const previewRows = computed(() =>
  (preview.value?.preview ?? []).map((cells) =>
    Object.fromEntries(
      (preview.value?.headers ?? []).map((h, i) => [h, cells[i] ?? ""]),
    ),
  ),
);
function duplicateName(v: string) {
  return (
    (
      {
        NEW: "新文件",
        EXACT_DUPLICATE: "完全重复",
        BUSINESS_DUPLICATE: "业务内容重复",
      } as Record<string, string>
    )[v] || v
  );
}
function confidenceType(v: number) {
  return v >= 0.9 ? "success" : v >= 0.7 ? "warning" : "danger";
}
async function sha(blob: Blob) {
  const d = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
  return [...new Uint8Array(d)]
    .map((x) => x.toString(16).padStart(2, "0"))
    .join("");
}
async function chunks(f: File) {
  const bytes = 5 * 1024 * 1024,
    total = Math.ceil(f.size / bytes),
    id = crypto.randomUUID();
  for (let i = 0; i < total; i++) {
    const chunk = f.slice(i * bytes, Math.min(f.size, (i + 1) * bytes));
    await uploadImportChunk({
      fileId: id,
      channel: channel.value,
      chunkIndex: i,
      totalChunks: total,
      sha256: await sha(chunk),
      chunk,
    });
  }
  return completeImportChunks(
    id,
    f.name,
    await sha(f),
    sheet.value || undefined,
    dates.value || undefined,
  );
}
async function fileChanged(item: any) {
  file.value = item.raw;
  try {
    preview.value =
      item.raw.size > 5 * 1024 * 1024
        ? await chunks(item.raw)
        : await uploadFile(
            item.raw,
            channel.value,
            undefined,
            dates.value || undefined,
          );
    sheet.value = preview.value.availableSheets?.[0] || "";
    if (preview.value.duplicateResult !== "NEW")
      ElMessage.warning("该文件已导入，请在批次列表查看");
    else if (preview.value.autoQueued) {
      ElMessage.success(`已自动应用映射版本 ${preview.value.templateVersion || "-"}，无需人工确认`);
      emit("update:visible", false);
      emit("completed");
    }
  } catch {
    file.value = null;
    uploadRef.value?.clearFiles();
  }
}
async function reloadSheet() {
  if (file.value && sheet.value)
    preview.value =
      file.value.size > 5 * 1024 * 1024
        ? await chunks(file.value)
        : await uploadFile(
            file.value,
            channel.value,
            sheet.value,
            dates.value || undefined,
          );
}
async function confirm() {
  if (!preview.value) return;
  const m = preview.value.recommendedMapping;
  if (!m.description || (channel.value !== "QUALITY" && !m.source_record_id))
    return void ElMessage.warning(
      channel.value === "QUALITY"
        ? "请完成问题描述字段映射"
        : "请完成来源记录ID和问题描述字段映射",
    );
  submitting.value = true;
  try {
    await confirmImport(preview.value.batchId, m);
    ElMessage.success("新映射版本已发布，导入任务已创建");
    emit("update:visible", false);
    emit("completed");
  } finally {
    submitting.value = false;
  }
}
function reset() {
  file.value = null;
  preview.value = null;
  dates.value = null;
  uploadRef.value?.clearFiles();
}
</script>
<style scoped>
.form,
.preview {
  margin-top: 16px;
}
.full {
  width: 100%;
}
.notice,
.hashes {
  margin-top: 12px;
}
.upload-text {
  font-size: 15px;
  margin-top: 12px;
}
.hint {
  font-size: 12px;
  margin-top: 6px;
  color: var(--text-secondary);
}
.mapping {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 10px 0 16px;
}
.mapping-row {
  display: grid;
  grid-template-columns: minmax(150px, 1fr) 24px minmax(130px, 1fr) 64px;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--bg-body);
  border-radius: 6px;
}
</style>
