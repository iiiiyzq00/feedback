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
import { BarChart as Bar } from "echarts/charts";
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
} from "echarts/components";

use([
  CanvasRenderer,
  Bar,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
]);

const props = withDefaults(
  defineProps<{
    title?: string;
    categories: string[];
    data: { name: string; data: number[]; color?: string }[];
    height?: number;
    horizontal?: boolean;
    highlightIndexes?: number[];
  }>(),
  { height: 360, horizontal: false },
);
const emit = defineEmits<{ "chart-click": [params: any] }>();

const chartOption = computed(() => {
  const xAxis: any = {
    type: "category",
    data: props.categories,
    axisLabel: { rotate: props.categories.length > 8 ? 45 : 0 },
  };
  const yAxis: any = { type: "value" };

  if (props.horizontal) {
    // 交换轴
    return {
      title: props.title
        ? { text: props.title, left: "center", textStyle: { fontSize: 16 } }
        : undefined,
      tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
      legend: { bottom: 0 },
      grid: { left: 12, right: "4%", bottom: "12%", containLabel: true },
      xAxis: { type: "value" },
      yAxis: {
        type: "category",
        data: props.categories,
        axisLabel: { width: 210, overflow: "truncate" },
        inverse: true,
      },
      series: props.data.map((s) => ({
        name: s.name,
        type: "bar",
        data: s.data.map((value, index) =>
          props.highlightIndexes?.includes(index)
            ? { value, itemStyle: { color: "#f56c6c" } }
            : value,
        ),
        itemStyle: s.color ? { color: s.color } : undefined,
      })),
    };
  }

  return {
    title: props.title
      ? { text: props.title, left: "center", textStyle: { fontSize: 16 } }
      : undefined,
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
    legend: { bottom: 0 },
    grid: { left: "3%", right: "4%", bottom: "12%", containLabel: true },
    xAxis,
    yAxis,
    dataZoom:
      props.categories.length > 15
        ? [{ type: "slider", start: 0, end: 50 }]
        : undefined,
    series: props.data.map((s) => ({
      name: s.name,
      type: "bar",
      data: s.data.map((value, index) =>
        props.highlightIndexes?.includes(index)
          ? { value, itemStyle: { color: "#f56c6c" } }
          : value,
      ),
      itemStyle: s.color ? { color: s.color } : undefined,
    })),
  };
});
</script>
