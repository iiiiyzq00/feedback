<template>
  <div class="page-skeleton" aria-label="页面内容加载中" aria-busy="true">
    <el-skeleton animated>
      <template #template>
        <div class="skeleton-head"><el-skeleton-item variant="h1" /><el-skeleton-item variant="button" /></div>
        <div class="skeleton-cards"><el-skeleton-item v-for="i in cards" :key="i" variant="rect" /></div>
        <el-skeleton-item variant="rect" class="skeleton-table" />
      </template>
    </el-skeleton>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ cards?: number }>(), { cards: 4 })
</script>

<style scoped>
.page-skeleton{padding:24px clamp(16px,2vw,32px);max-width:1680px;margin:auto}.skeleton-head{display:flex;justify-content:space-between;gap:20px;margin-bottom:22px}.skeleton-head :first-child{width:180px;height:32px}.skeleton-head :last-child{width:88px;height:32px}.skeleton-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin-bottom:20px}.skeleton-cards .el-skeleton__item{height:112px;border-radius:12px}.skeleton-table{height:360px;border-radius:12px}@media(max-width:900px){.skeleton-cards{grid-template-columns:repeat(2,1fr)}}
</style>

