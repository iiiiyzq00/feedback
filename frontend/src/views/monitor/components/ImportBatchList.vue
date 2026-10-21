<template>
  <DataTable
    class="import-batch-table"
    :data="batches"
    :loading="loading"
    :error="error"
    :empty-text="emptyText"
    :empty-action-text="currentStatus ? '查看全部批次' : '上传文件'"
    :pagination="pagination"
    :default-sort="{ prop: 'createdAt', order: 'descending' }"
    @retry="$emit('retry')"
    @page-change="$emit('page-change', $event)"
    @size-change="$emit('size-change', $event)"
    @empty-action="$emit('empty-action')"
  >
    <el-table-column prop="batchId" label="批次ID" min-width="180"
      ><template #default="{ row }"
        ><span class="full-value">{{ row.batchId }}</span></template
      ></el-table-column
    >
    <el-table-column prop="channel" label="渠道" min-width="96"
      ><template #default="{ row }"
        ><el-tag :type="channelType(row.channel)" size="small">{{
          channelName(row.channel)
        }}</el-tag></template
      ></el-table-column
    >
    <el-table-column label="接入方式" width="96"
      ><template #default="{ row }">{{ ingestLabel(row) }}</template></el-table-column
    >
    <el-table-column prop="totalRows" label="总行数" width="68" />
    <el-table-column label="新增" width="60"><template #default="{ row }">{{ row.insertedRows ?? 0 }}</template></el-table-column>
    <el-table-column label="更新" width="60"><template #default="{ row }">{{ row.updatedRows ?? 0 }}</template></el-table-column>
    <el-table-column prop="skippedRows" label="跳过" width="60"
      ><template #default="{ row }"
        ><span class="skip-count">{{ row.skippedRows }}</span></template
      ></el-table-column
    >
    <el-table-column label="失败/复核" width="80"
      ><template #default="{ row }"
        ><span
          :class="{ 'failed-count': row.failedRows + row.reviewedRows > 0 }"
          >{{ row.failedRows + row.reviewedRows }}</span
        ></template
      ></el-table-column
    >
    <el-table-column prop="status" label="状态" min-width="104"
      ><template #default="{ row }"
        ><StatusBadge :status="row.status" /></template
    ></el-table-column>
    <el-table-column v-if="batches.some(row => row.governanceStatus !== 'ACTIVE')" label="治理" min-width="100">
      <template #default="{ row }"><el-tag v-if="row.governanceStatus !== 'ACTIVE'" type="info">{{ row.governanceStatus === 'SUPERSEDED' ? '已被替代' : '零变更' }}</el-tag><span v-else>当前</span></template>
    </el-table-column>
    <el-table-column prop="createdAt" label="创建时间" min-width="145"
      ><template #default="{ row }"
        ><span class="full-value">{{ row.createdAt }}</span></template
      ></el-table-column
    >
    <el-table-column prop="sourceDataUpdatedAt" label="来源更新时间" min-width="145"
      ><template #default="{ row }"
        ><span class="full-value">{{ row.sourceDataUpdatedAt || "—" }}</span></template
      ></el-table-column
    >
    <el-table-column label="操作" min-width="190"
      ><template #default="{ row }">
        <div class="table-actions">
          <el-button
            v-if="row.status === 'AWAITING_MAPPING' && !isRawExpired(row)"
            size="small"
            text
            type="primary"
            @click="$emit('continue-mapping', row)"
            >处理字段变更</el-button
          >
          <el-button
            v-if="row.status === 'AWAITING_MAPPING' && isRawExpired(row)"
            size="small"
            text
            type="warning"
            @click="$emit('new-upload')"
            >重新上传</el-button
          >
          <el-button
            size="small"
            text
            type="primary"
            @click="$emit('detail', row)"
            >详情</el-button
          ><el-button
            v-if="!['AWAITING_MAPPING', 'PROCESSING'].includes(row.status)"
            size="small"
            text
            type="success"
            @click="$emit('export', row)"
            >对账导出</el-button
          >
          <el-button
            v-if="row.canRollback && isAdmin"
            size="small"
            text
            type="warning"
            @click="$emit('rollback', row)"
            >回滚</el-button
          >
        </div>
      </template></el-table-column
    >
  </DataTable>
</template>
<script setup lang="ts">
import DataTable from "@/components/common/DataTable.vue";
import StatusBadge from "@/components/common/StatusBadge.vue";
import type { ImportBatchView } from "@/api/import";
defineProps<{
  batches: ImportBatchView[];
  loading: boolean;
  error?: string;
  pagination: { page: number; size: number; total: number };
  isAdmin: boolean;
  currentStatus?: string;
  emptyText: string;
}>();
defineEmits<{
  retry: [];
  "page-change": [number];
  "size-change": [number];
  detail: [ImportBatchView];
  export: [ImportBatchView];
  rollback: [ImportBatchView];
  "retry-batch": [ImportBatchView];
  "continue-mapping": [ImportBatchView];
  "new-upload": [];
  "empty-action": [];
}>();
function isRawExpired(row: ImportBatchView) {
  return (
    !!row.rawRetentionUntil &&
    new Date(row.rawRetentionUntil).getTime() <= Date.now()
  );
}
function channelType(ch: string) {
  return ch === "SDK"
    ? "success"
    : ch === "JIRA"
      ? "primary"
      : ch === "QUALITY"
        ? "warning"
        : "danger";
}
function channelName(ch: string) {
  return (
    (
      {
        SDK: "SDK 模拟数据",
        JIRA: "400 Jira",
        QUALITY: "质量平台",
        REDBLACK: "红黑榜",
      } as Record<string, string>
    )[ch] || ch
  );
}
function ingestLabel(row: ImportBatchView) {
  if (row.ingestMethod !== "API") return "文件";
  return row.triggerType === "SCHEDULED"
    ? "API自动"
    : row.triggerType === "BACKFILL"
      ? "API补数"
      : "API手动";
}
</script>
<style scoped>
.import-batch-table :deep(.table-scroll) {
  overflow-x: hidden;
}
.import-batch-table :deep(.el-table) {
  width: 100% !important;
  min-width: 0;
}
.success-count {
  color: var(--color-success);
  font-weight: 600;
}
.skip-count {
  color: var(--color-warning);
  font-weight: 600;
}
.failed-count {
  color: var(--color-danger);
  font-weight: 600;
}
.full-value {
  display: block;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.table-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  width: 100%;
  white-space: nowrap;
}
.table-actions :deep(.el-button) {
  margin-left: 0;
  padding-inline: 5px;
}
</style>
