<template><article class="metric-card" :class="{clickable, 'long-value': isLongValue}" :tabindex="clickable?0:undefined" @keydown.enter="$emit('click')"><div class="metric-title">{{title}}<el-tooltip v-if="help" :content="help"><el-icon><QuestionFilled/></el-icon></el-tooltip></div><div class="metric-value">{{displayValue}}<small v-if="suffix">{{suffix}}</small></div><div v-if="description" class="metric-description">{{description}}</div><slot/></article></template>
<script setup lang="ts">
import { computed } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'

const props = defineProps<{
  title: string
  value: string | number
  suffix?: string
  description?: string
  help?: string
  clickable?: boolean
}>()
defineEmits<{ click: [] }>()
const displayValue = computed(() => typeof props.value === 'number' ? props.value.toLocaleString('zh-CN') : props.value)
const isLongValue = computed(() => String(displayValue.value ?? '').length > 12)
</script>
<style scoped>.metric-card{min-width:0;padding:16px 18px;background:#fff;border:1px solid rgba(214,223,235,.85);border-radius:var(--radius-card);box-shadow:var(--shadow-card)}.metric-card.clickable{cursor:pointer;transition:transform .2s,box-shadow .2s}.metric-card.clickable:hover,.metric-card.clickable:focus-visible{transform:translateY(-2px);box-shadow:var(--shadow-hover);outline:2px solid #b9d4ff;outline-offset:2px}.metric-title{display:flex;align-items:center;gap:5px;color:var(--text-secondary);font-size:13px}.metric-value{max-width:100%;margin-top:8px;color:var(--text-primary);font-size:26px;font-weight:720;line-height:1.2;white-space:pre-line;overflow-wrap:anywhere;word-break:break-word}.metric-card.long-value .metric-value{font-size:clamp(18px,2vw,22px);line-height:1.25}.metric-value small{margin-left:4px;font-size:13px;font-weight:500;color:var(--text-secondary)}.metric-description{margin-top:7px;color:var(--text-secondary);font-size:12px}</style>
