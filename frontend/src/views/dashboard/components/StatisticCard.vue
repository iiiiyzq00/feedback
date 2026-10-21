<template>
  <el-card class="statistic-card" shadow="never" :class="{ clickable }" @click="clickable && emit('click')">
    <div class="statistic-title">{{ title }} <el-tooltip v-if="definition" :content="definition"><el-icon class="help" aria-label="口径说明"><InfoFilled /></el-icon></el-tooltip></div>
    <div class="statistic-value" :style="{ color }">
      {{ formatValue(value) }}
      <span class="statistic-suffix">{{ suffix }}</span>
    </div>
    <div v-if="changePercent != null" class="comparison" :class="changePercent >= 0 ? 'up' : 'down'">{{ changePercent >= 0 ? '↑' : '↓' }} {{ Math.abs(changePercent).toFixed(1) }}% vs 上周</div>
    <div v-else-if="showComparison" class="comparison">上周无基线</div>
    <slot />
  </el-card>
</template>

<script setup lang="ts">
import { InfoFilled } from '@element-plus/icons-vue'
defineProps<{
  title: string
  value: number
  suffix?: string
  color?: string
  definition?: string
  changePercent?: number | null
  showComparison?: boolean
  clickable?: boolean
}>()
const emit = defineEmits<{ click: [] }>()

function formatValue(n: number): string {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  if (n >= 1000) return n.toLocaleString()
  return String(n)
}
</script>

<style scoped>
.statistic-card {
  position:relative;
  min-height:142px;
  box-sizing:border-box;
  overflow:hidden;
  text-align:left;
  border:1px solid #dbe5f0;
  border-radius:14px;
  box-shadow:0 8px 24px rgba(31,60,96,.045);
  transition:transform .18s ease,border-color .18s ease,box-shadow .18s ease;
}
.statistic-card::after{position:absolute;inset:0 0 auto;height:3px;background:linear-gradient(90deg,v-bind(color),transparent);content:"";opacity:.85}.statistic-card :deep(.el-card__body){padding:19px 20px}
.statistic-title {
  font-size: 14px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.statistic-value {
  margin-top:12px;
  font-size:30px;
  font-weight:750;
  line-height:1.1;
}
.statistic-suffix {
  font-size: 14px;
  font-weight: 400;
  color: var(--text-secondary);
}
.help{display:inline-flex;align-items:center;justify-content:center;width:15px;height:15px;border:1px solid #a8abb2;border-radius:50%;font-size:11px}.comparison{margin-top:10px;font-size:12px;color:var(--text-secondary)}.comparison.up{color:#f56c6c}.comparison.down{color:#67c23a}.clickable{cursor:pointer}.clickable:hover{transform:translateY(-2px);border-color:#9fc2ed;box-shadow:0 12px 30px rgba(37,78,128,.09)}
</style>
