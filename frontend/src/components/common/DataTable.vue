<template>
  <div class="data-table-wrapper">
    <div class="table-toolbar" v-if="$slots.toolbar">
      <div><slot name="toolbar" /></div>
    </div>

    <StateResult v-if="error && !data.length" type="error" title="数据加载失败" :description="error" retryable :retrying="loading" @retry="$emit('retry')" />
    <div v-else class="table-scroll">
      <el-table
        :data="data"
        v-loading="loading"
        :default-sort="defaultSort"
        :row-key="rowKey"
        border
        stripe
        highlight-current-row
        :class="`table-density-${density}`"
        @sort-change="forwardSortChange"
        @selection-change="forwardSelectionChange"
        @row-click="forwardRowClick"
      >
      <el-table-column
        v-if="selectable"
        type="selection"
        width="48"
        :reserve-selection="reserveSelection"
        :selectable="rowSelectable"
      />

      <slot />

      <template #empty>
        <el-empty :description="emptyText" :image-size="80"><el-button v-if="emptyActionText" type="primary" text @click="$emit('empty-action')">{{ emptyActionText }}</el-button></el-empty>
      </template>
      </el-table>
    </div>

    <div class="table-pagination" v-if="pagination">
      <el-pagination
        :current-page="pagination.page"
        :page-size="pagination.size"
        :total="pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="(s: number) => $emit('size-change', s)"
        @current-change="(p: number) => $emit('page-change', p)"
      />
    </div>
  </div>
</template>

<script setup lang="ts" generic="Row extends object">
import StateResult from './StateResult.vue'

interface DataTableSortChange {
  prop: string | null
  order: 'ascending' | 'descending' | null
}

withDefaults(
  defineProps<{
    data: Row[]
    loading?: boolean
    selectable?: boolean
    rowSelectable?: (row: Row, index: number) => boolean
    reserveSelection?: boolean
    rowKey?: string
    defaultSort?: { prop: string; order: 'ascending' | 'descending' }
    pagination?: { page: number; size: number; total: number } | null
    emptyText?: string
    emptyActionText?: string
    error?: string
    density?: 'compact' | 'standard' | 'comfortable'
  }>(),
  {
    loading: false,
    selectable: false,
    reserveSelection: true,
    rowKey: 'id',
    emptyText: '暂无数据',
    pagination: null,
    density: 'standard',
  },
)

const emit = defineEmits<{
  'sort-change': [value: DataTableSortChange]
  'selection-change': [value: Row[]]
  'row-click': [row: Row]
  'page-change': [page: number]
  'size-change': [size: number]
  'retry': []
  'empty-action': []
}>()

function forwardSortChange(value: DataTableSortChange) {
  emit('sort-change', value)
}

function forwardSelectionChange(value: Row[]) {
  emit('selection-change', value)
}

function forwardRowClick(row: Row) {
  emit('row-click', row)
}
</script>

<style scoped>
.data-table-wrapper {
  min-width: 0;
  background: #fff;
  border: 1px solid rgba(214, 223, 235, 0.8);
  border-radius: var(--radius-card);
  padding: 12px;
  box-shadow: var(--shadow-card);
}

.table-scroll { width: 100%; min-width: 0; overflow-x: auto; }
.table-scroll :deep(.el-table) { min-width: 720px; }

.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  overflow-x: auto;
}
</style>
