<template>
  <span class="source-icon" :class="'source-' + normalized">
    <span class="source-mark">
      <LearningMachineIcon v-if="normalized === 'sdk'" />
      <template v-else>{{ iconMap[normalized] || '📋' }}</template>
    </span>
    <span v-if="showLabel" class="source-label">{{
      labelMap[normalized] || channel || '-'
    }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import LearningMachineIcon from '@/components/common/LearningMachineIcon.vue';
import type { FeedbackSourceChannel } from '@/api/feedback';
const props = withDefaults(
  defineProps<{ channel: FeedbackSourceChannel | ''; showLabel?: boolean }>(),
  { showLabel: false },
);
const normalized = computed(() => String(props.channel || '').toLowerCase());

const iconMap: Record<string, string> = {
  jira: '🎫',
  quality: '🔧',
  redblack: '📊',
  station_feedback: '⚡',
};
const labelMap: Record<string, string> = {
  sdk: '学习机反馈（SDK）',
  jira: '400 Jira',
  quality: '质量平台',
  redblack: '红黑榜',
  station_feedback: '学习机反馈（原平台）',
};
</script>

<style scoped>
.source-icon {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  justify-content: center;
  max-width: 100%;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
}
.source-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  line-height: 1;
}
.source-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-primary);
}
.source-sdk {
  color: var(--channel-sdk);
}
.source-sdk :deep(svg) {
  width: 18px;
  height: 18px;
}
</style>
