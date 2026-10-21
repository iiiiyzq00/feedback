<template>
  <v-chart
    :option="chartOption"
    :style="{ height: height + 'px' }"
    autoresize
    @click="(p: any) => emit('chart-click', p)"
  />
</template>

<script setup lang="ts">
import { computed } from "vue";
import VChart from "vue-echarts";
import { use } from "echarts/core";
import { CanvasRenderer } from "echarts/renderers";
import { LineChart } from "echarts/charts";
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from "echarts/components";

use([
  CanvasRenderer,
  LineChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
]);

const props = withDefaults(
  defineProps<{
    title?: string;
    data: {
      name: string;
      data: [string, number][];
      comparison?: boolean;
      color?: string;
    }[];
    xAxisData?: string[];
    height?: number;
    showLegend?: boolean;
    markers?: { date: string; title: string; level: string; alertId: number }[];
    stacked?: boolean;
    area?: boolean;
  }>(),
  { height: 360, showLegend: true },
);
const emit = defineEmits<{ "chart-click": [params: any] }>();

const chartOption = computed(() => ({
  title: props.title
    ? { text: props.title, left: "center", textStyle: { fontSize: 16 } }
    : undefined,
  tooltip: { trigger: "axis" },
  legend: props.showLegend
    ? {
        bottom: 0,
        type: "scroll",
        data: props.data.filter((s) => !s.comparison).map((s) => s.name),
      }
    : undefined,
  grid: {
    left: "3%",
    right: "4%",
    bottom: props.showLegend ? "12%" : "3%",
    containLabel: true,
  },
  xAxis: {
    type: "category",
    boundaryGap: false,
    data: props.xAxisData || props.data[0]?.data.map((d) => d[0]) || [],
  },
  yAxis: { type: "value" },
  series: props.data.map((s, index) => ({
    name: s.name,
    type: "line",
    smooth: true,
    stack: props.stacked && !s.comparison ? "total" : undefined,
    lineStyle: {
      type: s.comparison ? "dashed" : "solid",
      width: s.comparison ? 1.5 : 2,
      opacity: s.comparison ? 0.75 : 1,
      color: s.color,
    },
    itemStyle: s.color ? { color: s.color } : undefined,
    areaStyle:
      props.area && !s.comparison
        ? { opacity: 0.18, color: s.color }
        : undefined,
    data: s.data.map((d) => d[1]),
    symbol: s.comparison ? "none" : "circle",
    symbolSize: 4,
    markPoint:
      index === 0 && props.markers?.length
        ? {
            symbol: "pin",
            symbolSize: 38,
            itemStyle: { color: "#f56c6c" },
            label: { formatter: "!" },
            tooltip: { trigger: "item", formatter: (p: any) => p.data.name },
            data: props.markers.map((m) => ({
              name: `${m.level} ${m.title}`,
              coord: [m.date, 0],
              value: m.level,
              alertId: m.alertId,
            })),
          }
        : undefined,
  })),
}));
</script>
