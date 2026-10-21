<template>
  <div class="global-search">
    <el-autocomplete
      v-model="query"
      :fetch-suggestions="searchSuggestions"
      placeholder="输入关键词搜索（支持反馈ID、问题描述、模块名）"
      :trigger-on-focus="false"
      clearable
      class="search-input"
      @select="handleSelect"
    >
      <template #default="{ item }">
        <div class="search-result-item">
          <el-tag :type="item.typeColor" size="small">{{ item.typeLabel }}</el-tag>
          <span class="result-title">{{ item.title }}</span>
          <span class="result-desc">{{ item.description }}</span>
        </div>
      </template>
    </el-autocomplete>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { getSearchSuggestions, type SearchSuggestionItem } from '@/api/search'

const router = useRouter()
const app = useAppStore()
const query = ref('')

async function searchSuggestions(qs: string, cb: (results: SearchSuggestionItem[]) => void) {
  if (!qs || qs.length < 2) {
    cb([])
    return
  }
  controller?.abort()
  controller = new AbortController()
  // Abort 减少无用工作，序列号才是防止已完成旧请求回写新关键词结果的最终门禁。
  const seq = ++requestSequence
  try {
    const result = await getSearchSuggestions(qs, 10, controller.signal)
    if (seq === requestSequence) cb(result?.items ?? [])
  } catch {
    if (seq === requestSequence) cb([])
  }
}

let controller: AbortController | undefined
let requestSequence = 0

function handleSelect(item: SearchSuggestionItem) {
  app.closeGlobalSearch()
  // path 来自服务端具名合同，只导航站内受治理详情入口。
  if (item.path) {
    router.push(item.path)
  }
}
</script>

<style scoped>
.search-input {
  width: 100%;
}

.search-result-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-title {
  font-weight: 500;
  color: var(--text-primary);
}

.result-desc {
  font-size: 12px;
  color: var(--text-secondary);
  margin-left: auto;
}
</style>
