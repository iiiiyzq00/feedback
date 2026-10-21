<template>
  <div class="page-container">
    <ChannelPageHero
      channel="quality"
      title="质量平台反馈"
      eyebrow="QUALITY RETURNS"
      intro="围绕商返事实查看产品型号、派单日期、地区与换机需求，辅助质量问题处置。"
      :facts="[
        { label: '接入方式', value: '只读文件导入' },
        { label: '业务主键', value: '商返单号' },
        { label: '核心时间', value: '商返申请日期' },
      ]"
    />

    <ChannelFeedbackList channel="quality" :extra-filters="extraFilters" />
  </div>
</template>

<script setup lang="ts">
import ChannelFeedbackList from './components/ChannelFeedbackList.vue';
import ChannelPageHero from './components/ChannelPageHero.vue';

const extraFilters = [
  { key: 'province', label: '省份', type: 'input' },
  { key: 'city', label: '城市', type: 'input' },
  {
    key: 'applicationType',
    label: '申请类型',
    type: 'select',
    options: [
      { label: '换机', value: '换机' },
      { label: '维修', value: '维修' },
      { label: '投诉', value: '投诉' },
      { label: '咨询', value: '咨询' },
    ],
  },
  { key: 'repairRegion', label: '报修区域', type: 'input' },
  { key: 'appVersion', label: '应用版本', type: 'input' },
  {
    key: 'exchangeRequest',
    label: '换机需求',
    type: 'select',
    options: [
      { label: '是', value: '1' },
      { label: '否', value: '0' },
    ],
  },
  { key: 'batchId', label: '导入批次', type: 'input' },
];
</script>
