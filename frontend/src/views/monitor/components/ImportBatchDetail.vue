<template>
  <el-drawer
    :model-value="visible"
    title="导入批次详情"
    size="480px"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div v-loading="loading">
      <StateResult
        v-if="error"
        type="error"
        title="批次详情加载失败"
        :description="error"
        retryable
        @retry="$emit('retry')"
      />
      <el-descriptions v-else-if="detail" :column="1" border
        ><el-descriptions-item label="批次ID">{{
          detail.batchId
        }}</el-descriptions-item
        ><el-descriptions-item label="渠道">{{
          channelName(detail.channel)
        }}</el-descriptions-item
        ><el-descriptions-item label="状态"
          ><StatusBadge :status="detail.status" /></el-descriptions-item
        ><el-descriptions-item label="来源数据更新时间">{{
          formatTime(detail.sourceDataUpdatedAt)
        }}</el-descriptions-item
        ><el-descriptions-item label="总行数">{{
          detail.totalRows
        }}</el-descriptions-item
        ><el-descriptions-item label="成功/跳过"
          >{{ detail.successRows }} /
          {{ detail.skippedRows }}</el-descriptions-item
        ><el-descriptions-item label="失败/复核"
          >{{ detail.failedRows }} /
          {{ detail.reviewedRows }}</el-descriptions-item
        ><el-descriptions-item label="综合质量分">{{
          rate(detail.qualityMetrics?.qualityScore)
        }}</el-descriptions-item
        ><el-descriptions-item label="完整率">{{
          rate(detail.qualityMetrics?.completenessRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="脱敏通过率">{{
          rate(detail.qualityMetrics?.maskingPassRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="主键重复率">{{
          rate(detail.qualityMetrics?.duplicateRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="时间合法率">{{
          rate(detail.qualityMetrics?.timeValidRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="枚举合法率">{{
          rate(detail.qualityMetrics?.enumValidRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="应用/模块/版本映射率">{{
          [detail.qualityMetrics?.appMappingRate,detail.qualityMetrics?.moduleMappingRate,detail.qualityMetrics?.versionMappingRate].map(rate).join(' / ')
        }}</el-descriptions-item
        ><el-descriptions-item label="导入成功率">{{
          rate(detail.qualityMetrics?.importSuccessRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="跨渠道疑似重复率">{{
          rate(detail.qualityMetrics?.crossChannelSuspectedDuplicateRate)
        }}</el-descriptions-item
        ><el-descriptions-item label="异常/隔离数量">{{
          `${detail.qualityMetrics?.anomalyCount ?? 0} / ${detail.qualityMetrics?.quarantineCount ?? 0}`
        }}</el-descriptions-item
        ><el-descriptions-item label="阈值状态"
          ><el-tag :type="detail.qualityMetrics?.thresholdStatus === 'ALERT' ? 'danger' : 'success'">{{ detail.qualityMetrics?.thresholdStatus || '—' }}</el-tag>
        </el-descriptions-item
        ><el-descriptions-item label="处理进度"
          >{{ detail.progressPercent }}%</el-descriptions-item
        ><el-descriptions-item label="对账状态">{{
          detail.reconciliationStatus || "—"
        }}</el-descriptions-item
        ><el-descriptions-item v-if="detail.lastErrorCode" label="批次错误"
          >{{ detail.lastErrorCode }} ·
          {{ detail.lastErrorDetail || "—" }}</el-descriptions-item
        ><el-descriptions-item
          v-if="isActionable"
          label="原始行保留至"
          >{{ formatTime(detail.rawRetentionUntil) }}
          <el-tag :type="rawExpired ? 'danger' : 'warning'" size="small">
            {{ rawExpired ? "已过期" : "加密保留中" }}
          </el-tag></el-descriptions-item
        ></el-descriptions
      >
      <el-table
        v-if="detail?.errors?.length"
        :data="detail.errors"
        size="small"
        border
        class="errors"
        ><el-table-column
          prop="rowNumber"
          label="行"
          width="60" /><el-table-column
          prop="status"
          label="问题类型"
          width="110" /><el-table-column
          prop="errorCode"
          label="错误码" /><el-table-column
          prop="summary"
          label="错误摘要" /><el-table-column
          prop="suggestion"
          label="修正建议" /></el-table
      ><el-button
        v-if="detail?.errors?.length"
        class="download"
        @click="$emit('download-errors')"
        >下载安全错误报告</el-button
      >
      <div
        v-if="detail && isActionable"
        class="resolution-actions"
      >
        <el-alert
          v-if="rawExpired"
          title="原始行数据已超过 7 天安全保留期，无法继续映射或重试；请修正源文件后重新上传。"
          type="warning"
          :closable="false"
          show-icon
          class="retention-alert"
        />
        <el-button v-if="hasInvalid && !rawExpired" @click="$emit('remap', detail)"
          >调整字段映射</el-button
        >
        <el-button
          v-if="hasTechnical && detail.canRetry && !rawExpired && canRetryTechnical"
          type="primary"
          @click="$emit('retry-technical', detail)"
          >重试技术失败行</el-button
        >
        <el-tooltip
          v-else-if="hasTechnical && detail.canRetry && !rawExpired"
          content="SDK 模拟导入仅允许管理员重试"
          placement="top"
        >
          <span><el-button type="primary" disabled>重试技术失败行</el-button></span>
        </el-tooltip>
      </div>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import StateResult from "@/components/common/StateResult.vue";
import StatusBadge from "@/components/common/StatusBadge.vue";
import { computed } from "vue";
import type { ImportBatchDetailView } from "@/api/import";
const props = defineProps<{
  visible: boolean;
  detail: ImportBatchDetailView | null;
  loading: boolean;
  error?: string;
  isAdmin: boolean;
}>();
defineEmits<{
  "update:visible": [boolean];
  retry: [];
  "download-errors": [];
  remap: [ImportBatchDetailView];
  "retry-technical": [ImportBatchDetailView];
}>();
const hasInvalid = computed(
  () => props.detail?.errors?.some((e) => e.status === "INVALID") ?? false,
);
const hasTechnical = computed(
  () =>
    !!props.detail?.lastErrorCode ||
    (props.detail?.errors?.some((e) => e.status === "FAILED") ?? false),
);
const canRetryTechnical = computed(
  () => props.detail?.channel !== "SDK" || props.isAdmin,
);
const isActionable = computed(
  () =>
    !!props.detail &&
    ["AWAITING_MAPPING", "PARTIAL_SUCCESS", "FAILED"].includes(
      props.detail.status,
    ),
);
const rawExpired = computed(() => {
  const deadline = props.detail?.rawRetentionUntil;
  return !!deadline && new Date(deadline).getTime() <= Date.now();
});
function formatTime(value?: string) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN");
}
function channelName(ch: string) {
  return (
    (
      {
        SDK: "SDK 模拟数据",
        JIRA: "Jira",
        QUALITY: "质量平台",
        REDBLACK: "红黑榜",
      } as Record<string, string>
    )[ch] || ch
  );
}
function rate(v: unknown) {
  const n = Number(v);
  return Number.isFinite(n) ? `${(n * 100).toFixed(2)}%` : "-";
}
</script>
<style scoped>
.errors,
.download {
  margin-top: 12px;
}
.resolution-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.retention-alert {
  flex-basis: 100%;
}
.resolution-actions :deep(.el-button) {
  margin-left: 0;
}
</style>
