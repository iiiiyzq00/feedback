<template>
  <div class="state-result" :class="`is-${type}`" role="status">
    <el-result :icon="type" :title="title" :sub-title="description">
      <template #extra><el-button v-if="retryable" type="primary" :loading="retrying" @click="$emit('retry')">重新加载</el-button></template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ type?: 'info'|'warning'|'error'|'success'; title?: string; description?: string; retryable?: boolean; retrying?: boolean }>(), {
  type: 'info', title: '暂无数据', description: '当前条件下没有可展示的内容', retryable: false, retrying: false,
})
defineEmits<{ retry: [] }>()
</script>

<style scoped>
.state-result{display:flex;min-height:260px;align-items:center;justify-content:center;background:#fff;border:1px dashed var(--border-color);border-radius:var(--radius-card)}.state-result :deep(.el-result){padding:28px}.state-result :deep(.el-result__title p){font-size:17px}
</style>
