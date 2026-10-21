<template>
  <el-tag :type="tagType" :size="size" :effect="effect">
    {{ label }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { TagProps } from 'element-plus'

type BadgeDefinition = {
  type: NonNullable<TagProps['type']>
  label: string
}

const props = withDefaults(
  defineProps<{
    status?: string | null
    size?: 'small' | 'default' | 'large'
    effect?: 'light' | 'dark' | 'plain'
  }>(),
  { size: 'small', effect: 'light' },
)

// 严重度映射
const severityMap: Record<string, BadgeDefinition> = {
  S0: { type: 'danger', label: 'S0-致命' },
  S1: { type: 'danger', label: 'S1-严重' },
  S2: { type: 'warning', label: 'S2-中等' },
  S3: { type: 'info', label: 'S3-轻微' },
  S4: { type: 'success', label: 'S4-无故障影响' },
}

// 状态映射
const statusMap: Record<string, BadgeDefinition> = {
  NEW: { type: 'info', label: '新建' },
  DISTRIBUTED: { type: 'primary', label: '已分发' },
  PENDING: { type: 'info', label: '待处理' },
  PROCESSING: { type: 'warning', label: '处理中' },
  SUSPENDED: { type: 'warning', label: '挂起' },
  UNASSIGNED: { type: 'info', label: '未分发' },
  NOTIFYING: { type: 'warning', label: '通知中' },
  PENDING_ACCEPTANCE: { type: 'warning', label: '待接收' },
  ACCEPTED: { type: 'success', label: '已接收' },
  ROLLED_BACK: { type: 'danger', label: '已回滚' },
  CANCELLED: { type: 'info', label: '已撤回' },
  AWAITING_MAPPING: { type: 'warning', label: '字段变更待处理' },
  RESOLVED: { type: 'success', label: '已解决' },
  CLOSED: { type: 'info', label: '已关闭' },
  PENDING_REVIEW: { type: 'warning', label: '待审核' },
  PUBLISHED: { type: 'success', label: '已发布' },
  ARCHIVED: { type: 'info', label: '已归档' },
  AUTO_HIGH: { type: 'success', label: '自动归簇-高置信' },
  AUTO_MEDIUM: { type: 'primary', label: '自动归簇-中置信' },
  NEW_CLUSTER: { type: 'info', label: '自动新建簇' },
  CANDIDATE: { type: 'warning', label: '候选关系' },
  MANUAL_CONFIRMED: { type: 'success', label: '人工确认' },
  MANUAL_REASSIGNED: { type: 'warning', label: '人工重新分配' },
  REJECTED: { type: 'danger', label: '已拒绝关联' },
  COMPLETED: { type: 'success', label: '已完成' },
  PARTIAL_SUCCESS: { type: 'warning', label: '部分成功' },
  FAILED: { type: 'danger', label: '失败' },
  IMPORTING: { type: 'primary', label: '导入中' },
}

const tagType = computed(() => {
  const status = props.status
  if (!status) return 'info'
  return severityMap[status]?.type
    || statusMap[status]?.type
    || 'info'
})

const label = computed(() => {
  const status = props.status
  if (!status) return '未分级'
  return severityMap[status]?.label
    || statusMap[status]?.label
    || status
})
</script>
