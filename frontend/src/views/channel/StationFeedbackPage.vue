<template>
  <div class="page-container">
    <ChannelPageHero
      channel="station_feedback"
      title="学习机反馈"
      eyebrow="LEARNING DEVICE FEEDBACK"
      intro="统一查看 SDK 实时上报、旧反馈平台实时反馈与历史数据；底层渠道、来源记录和安全审计仍独立保留。"
      :facts="[
        { label: 'SDK 实时接入', value: '加密签名上报' },
        { label: '旧平台接入', value: 'V1 / V2 与历史数据' },
        { label: '数据边界', value: '统一展示、来源可追溯' },
      ]"
    />
    <ChannelFeedbackList
      view="learning_machine"
      :query-channels="['SDK', 'STATION_FEEDBACK']"
      :extra-filters="[]"
    />
  </div>
</template>

<script setup lang="ts">
import ChannelFeedbackList from './components/ChannelFeedbackList.vue';
import ChannelPageHero from './components/ChannelPageHero.vue';
</script>
