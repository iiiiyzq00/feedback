<template>
  <div class="page-container">
    <ChannelPageHero
      channel="sdk"
      title="SDK 反馈"
      eyebrow="REAL-TIME SDK"
      intro="查看通用反馈 SDK 的实时上报、客户端上下文与安全处理结果。"
      :facts="[
        { label: '接入方式', value: 'SDK 实时上报' },
        { label: '数据边界', value: '独立于旧版入口' },
        { label: '重点证据', value: '设备、版本与日志' },
      ]"
    />

    <ChannelFeedbackList channel="sdk" :extra-filters="extraFilters" />
  </div>
</template>

<script setup lang="ts">
import ChannelFeedbackList from './components/ChannelFeedbackList.vue';
import ChannelPageHero from './components/ChannelPageHero.vue';

const extraFilters = [
  {
    key: 'appId',
    label: '应用',
    type: 'select',
    options: [
      { label: '课程', value: 'course' },
      { label: '题库', value: 'question-bank' },
      { label: '错题本', value: 'wrong-book' },
      { label: 'AI辅导', value: 'ai-tutor' },
      { label: '应用商店', value: 'app-store' },
    ],
  },
  { key: 'deviceModel', label: '设备型号', type: 'input' },
  { key: 'osVersion', label: '系统版本', type: 'input' },
  { key: 'appVersion', label: '应用版本', type: 'input' },
  {
    key: 'networkType',
    label: '网络类型',
    type: 'select',
    options: [
      { label: 'WiFi', value: 'WIFI' },
      { label: '4G', value: '4G' },
      { label: '5G', value: '5G' },
      { label: '离线', value: 'OFFLINE' },
    ],
  },
  { key: 'functionModule', label: '功能模块', type: 'input' },
];
</script>
