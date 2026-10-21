<template>
  <div class="channel-feedback-list">
    <el-alert
      v-if="channel === 'sdk' && stats.newRecords5m && !newRecordsIgnored"
      type="info"
      show-icon
      :closable="false"
      class="new-banner"
    >
      <template #title
        >↑ 有 {{ stats.newRecords5m }} 条新记录（最近5分钟）</template
      >
      <el-button link type="primary" @click="loadNewest">加载新记录</el-button>
      <el-button link @click="newRecordsIgnored = true">忽略</el-button>
    </el-alert>
    <FilterPanel class="feedback-filters">
      <div class="filter-primary">
        <el-input
          v-model="filters.keyword"
          placeholder="搜索标题、描述或来源编号"
          :prefix-icon="Search"
          clearable
          class="keyword-filter"
          @input="debounceSearch"
          @keyup.enter="doSearch"
        />
        <el-date-picker
          v-model="filters.dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
        />
        <el-button-group class="query-actions"
          ><el-button type="primary" :icon="Search" @click="doSearch"
            >查询</el-button
          ><el-tooltip content="清空全部筛选条件"
            ><el-button
              :icon="RefreshLeft"
              aria-label="清空全部筛选条件"
              @click="resetFilters" /></el-tooltip
        ></el-button-group>
      </div>
      <template #actions
        ><el-button v-if="feedbackWorkspaceAccess" text @click="exportCurrent"
          >导出</el-button
        ></template
      >
    </FilterPanel>

    <DataTable
      class="feedback-table"
      :data="list"
      :loading="loading"
      :error="loadError"
      empty-text="没有找到符合条件的反馈"
      empty-action-text="重置筛选"
      :selectable="false"
      :pagination="pagination"
      @retry="loadData"
      @empty-action="resetFilters"
      @row-click="openDetail"
      @page-change="pageChanged"
      @size-change="sizeChanged"
      @sort-change="sortChanged"
    >
      <el-table-column
        v-if="!channel && view !== 'learning_machine'"
        label="来源渠道"
        width="124"
        ><template #default="{ row }"
          ><div class="channel-cell">
            <ChannelSourceIcon :channel="row.sourceChannel" show-label /><el-tag
              v-if="row.sdkDataType === 'SIMULATED_IMPORT'"
              size="small"
              type="warning"
              >模拟</el-tag
            >
          </div></template
        ></el-table-column
      >
      <el-table-column
        v-for="col in columns"
        :key="col.prop"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :sortable="col.sortable ? 'custom' : false"
        :class-name="isTextColumn(col.prop) ? 'cell-text' : ''"
        :label-class-name="isTextColumn(col.prop) ? 'cell-text' : ''"
      >
        <template #default="{ row }"
          ><el-button
            v-if="col.prop === 'issueKey'"
            link
            type="primary"
            @click.stop="openSource(row)"
            >{{
              formatColumnValue(
                col,
                col.meta ? channelMetaValue(row, col.meta) : undefined,
              )
            }}
            ↗</el-button
          >
          <div v-else-if="col.prop === 'diagnostics'">
            <div
              v-if="row.keyDiagnostics?.length"
              class="diagnostic-value-list"
            >
              <div
                v-for="(item, index) in row.keyDiagnostics"
                :key="`${item.type}-${index}`"
                class="diagnostic-value"
              >
                <span>{{ diagnosticTypeLabel(item.type) }}</span>
                <strong :title="item.value || '未提供'">{{ item.value || '未提供' }}</strong>
              </div>
            </div>
            <span v-else class="empty-related">当前记录无关键信息</span>
          </div>
          <div v-else-if="col.prop === 'evidence'" class="evidence-cell">
            <GovernedEvidenceViewer
              :source-id="Number(row.id)"
              :counts="{
                attachment: row.attachmentCount,
                image: row.screenshotCount,
                log: row.logCount,
                video: row.videoCount,
              }"
              @view-log="openDiagnostics(row, $event)"
            />
          </div>
          <span v-else-if="col.prop === 'learningMachineSource'">{{
            learningMachineSourceLabel(row.sourceChannel)
          }}</span
          ><span v-else-if="col.meta">{{
            formatColumnValue(col, channelMetaValue(row, col.meta))
          }}</span
          ><span v-else>{{
            isTimeColumn(col.prop)
              ? col.prop === 'reportedAt' &&
                !(row.reportedAt || row.publishedAt)
                ? '时间未提供'
                : formatInlineTime(
                    col.prop === 'reportedAt'
                      ? row.reportedAt || row.publishedAt
                      : rowValue(row, col.prop),
                  )
              : ['province', 'city', 'district'].includes(col.prop)
                ? regionName(rowValue(row, col.prop))
                : col.prop === 'issueCategory'
                  ? issueCategoryName(String(rowValue(row, col.prop) ?? ''))
                  : (rowValue(row, col.prop) ?? '-')
          }}</span
          ><el-tag
            v-if="col.prop === 'emotion' && row.sentimentConflict"
            class="conflict-tag"
            size="small"
            type="danger"
            >语义冲突</el-tag
          ></template
        >
      </el-table-column>
      <el-table-column
        label="操作"
        width="88"
        fixed="right"
        align="center"
        class-name="operation-column"
        label-class-name="operation-column"
      >
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openDetail(row)"
            >详情</el-button
          >
        </template>
      </el-table-column>
    </DataTable>
    <GovernedDiagnosticsDialog ref="diagnosticsDialog" />
  </div>
</template>

<script setup lang="ts">
import { RefreshLeft, Search } from '@element-plus/icons-vue';
import DataTable from '@/components/common/DataTable.vue';
import GovernedDiagnosticsDialog from '@/components/evidence/GovernedDiagnosticsDialog.vue';
import GovernedEvidenceViewer from '@/components/evidence/GovernedEvidenceViewer.vue';
import FilterPanel from '@/components/common/FilterPanel.vue';
import ChannelSourceIcon from '../ChannelSourceIcon.vue';
import { regionName } from '@/utils/region';
import { learningMachineSourceLabel } from './channelColumns';
import {
  channelMetaValue,
  diagnosticTypeLabel,
  formatColumnValue,
  formatInlineTime,
  isTextColumn,
  isTimeColumn,
  issueCategoryName,
  rowValue,
} from './feedbackListPresentation';
import {
  useFeedbackList,
  type FeedbackListProps,
} from './useFeedbackList';

const props = defineProps<FeedbackListProps>();
const {
  feedbackWorkspaceAccess,
  loading,
  loadError,
  list,
  newRecordsIgnored,
  diagnosticsDialog,
  stats,
  pagination,
  filters,
  columns,
  openDetail,
  openDiagnostics,
  loadData,
  debounceSearch,
  doSearch,
  loadNewest,
  resetFilters,
  pageChanged,
  sizeChanged,
  sortChanged,
  exportCurrent,
  openSource,
} = useFeedbackList(props);
</script>
<style scoped src="./ChannelFeedbackList.css"></style>
