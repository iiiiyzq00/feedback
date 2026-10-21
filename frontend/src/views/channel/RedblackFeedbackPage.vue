<template>
  <div class="page-container">
    <ChannelPageHero
      channel="redblack"
      title="红黑榜反馈"
      eyebrow="PUBLIC SENTIMENT"
      intro="查看公开评价事实、情感与评价对象；该渠道以发布时间为核心时间，不产生故障时间。"
      :facts="[
        { label: '接入方式', value: '只读文件导入' },
        { label: '数据属性', value: '公开评价事实' },
        { label: '核心时间', value: '发布时间' },
      ]"
    />

    <ChannelFeedbackList channel="redblack" :extra-filters="extraFilters" />
  </div>
</template>

<script setup lang="ts">
import ChannelFeedbackList from './components/ChannelFeedbackList.vue';
import ChannelPageHero from './components/ChannelPageHero.vue';

const extraFilters = [
  {
    key: 'listType',
    label: '红黑榜类型',
    type: 'select',
    options: [
      { label: '红榜', value: 'RED' },
      { label: '黑榜', value: 'BLACK' },
    ],
  },
  {
    key: 'emotionType',
    label: '情感类型',
    type: 'select',
    options: [
      { label: '正面', value: 'POSITIVE' },
      { label: '中性', value: 'NEUTRAL' },
      { label: '负面', value: 'NEGATIVE' },
    ],
  },
  { key: 'sourcePlatform', label: '来源平台', type: 'input' },
  { key: 'evaluationTarget', label: '评价对象', type: 'input' },
  { key: 'channelKeyword', label: '渠道关键词', type: 'input' },
  { key: 'batchId', label: '导入批次', type: 'input' },
];
</script>
