<template>
  <v-chart :option="chartOption" :style="{ height: height + 'px' }" autoresize @click="(p:any)=>emit('chart-click',p)" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
  GridComponent,
} from 'echarts/components'

use([CanvasRenderer, BarChart, TitleComponent, TooltipComponent, VisualMapComponent, GridComponent])

const props = withDefaults(
  defineProps<{
    title?: string
    // 地区热度数据。未配置行政区 GeoJSON 时使用强度排名，避免伪造地图坐标。
    data: { name: string; value: number }[]
    height?: number
  }>(),
  { height: 400 },
)
const emit = defineEmits<{ 'chart-click': [params:any] }>()

const chartOption = computed(() => {
  const max = Math.max(...props.data.map((d) => d.value), 1)
  return {
    title: props.title ? { text: props.title, left: 'center', textStyle: { fontSize: 16 } } : undefined,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: '3%', right: '8%', top: '3%', bottom: '12%', containLabel: true },
    xAxis: { type: 'value', name: '反馈数' },
    yAxis: {
      type: 'category', inverse: true,
      data: props.data.slice(0, 12).map((item) => item.name),
      axisLabel: { width: 100, overflow: 'truncate' },
    },
    visualMap: {
      min: 0,
      max,
      calculable: true,
      show: false,
      inRange: { color: ['#e0f3d8', '#a3d9a5', '#ffd580', '#ff8888', '#cc3333'] },
    },
    series: [
      {
        type: 'bar',
        data: props.data.slice(0, 12).map((item) => item.value),
        itemStyle: { color: '#f56c6c' },
      },
    ],
  }
})
</script>
