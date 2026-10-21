<template>
  <div class="page-container">
    <ChannelPageHero
      channel="jira"
      title="Jira 反馈"
      eyebrow="400 JIRA"
      intro="统一查看 Jira 安全反馈事实；平台操作始终保持来源只读。"
      :facts="[
        { label: '接入方式', value: '只读 API / 文件补数' },
        { label: '处置边界', value: '不回写 Jira' },
        { label: '查看口径', value: '全部安全数据' },
      ]"
    />
    <ChannelFeedbackList channel="jira" :extra-filters="[]" />
  </div>
</template>

<script setup lang="ts">
import ChannelFeedbackList from './components/ChannelFeedbackList.vue';
import ChannelPageHero from './components/ChannelPageHero.vue';
</script>
