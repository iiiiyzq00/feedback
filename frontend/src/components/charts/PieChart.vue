<template>
  <v-chart :option="chartOption" :style="{ height: height + 'px' }" autoresize @click="(p: any) => emit('chart-click', p)" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart as Pie } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent } from 'echarts/components'

use([CanvasRenderer, Pie, TitleComponent, TooltipComponent, LegendComponent])

const props = withDefaults(
  defineProps<{
    title?: string
    data: { name: string; value: number }[]
    height?: number
    type?: 'pie' | 'ring'
  }>(),
  { height: 360, type: 'ring' },
)
const emit = defineEmits<{ 'chart-click': [params: any] }>()

const chartOption = computed(() => ({
  title: props.title ? { text: props.title, left: 'center', textStyle: { fontSize: 16 } } : undefined,
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0, type: 'scroll' },
  series: [
    {
      type: 'pie',
      radius: props.type === 'ring' ? ['40%', '70%'] : '70%',
      center: ['50%', '50%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      emphasis: {
        label: { show: true, fontSize: 14, fontWeight: 'bold' },
      },
      data: props.data,
    },
  ],
}))
</script>
