<template>
  <div class="page-container">
    <ChannelPageHero
      title="全部渠道反馈"
      eyebrow="UNIFIED CHANNELS"
      intro="集中查看学习机、Jira、质量平台和红黑榜反馈，并以统一公共字段快速定位问题。"
      :facts="[
        { label: '覆盖范围', value: '四个反馈渠道' },
        { label: '查看口径', value: '统一公共字段' },
        { label: '核心能力', value: '查询与详情追溯' },
      ]"
    />
    <ChannelFeedbackList :extra-filters="[]" />
  </div>
</template>

<script setup lang="ts">
import ChannelFeedbackList from "./components/ChannelFeedbackList.vue";
import ChannelPageHero from "./components/ChannelPageHero.vue";
</script>
