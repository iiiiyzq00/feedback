<template>
  <el-card shadow="never">
    <template #header>
      <div class="detail-header">
        <span>候选簇对比 - {{ item.sourceRecordId || `反馈 #${item.id}` }}</span>
        <span>{{ priorityLabel(item.priority) }} · {{ slaText(item) }}</span>
      </div>
    </template>
    <el-descriptions :column="2" border size="small" class="profile">
      <el-descriptions-item label="渠道">{{ item.sourceChannel }}</el-descriptions-item>
      <el-descriptions-item label="严重度">{{ item.severity }}</el-descriptions-item>
      <el-descriptions-item label="模块">{{ item.feedbackModule }}</el-descriptions-item>
      <el-descriptions-item label="问题类型">{{ item.issueCategory }}</el-descriptions-item>
      <el-descriptions-item label="决策版本">{{ item.decisionVersion }} / {{ item.decisionConfigVersion }}</el-descriptions-item>
      <el-descriptions-item label="审核版本">{{ item.reviewVersion }}</el-descriptions-item>
    </el-descriptions>
    <el-alert :title="item.decisionReason || '无决策说明'" type="info" :closable="false" class="profile" />
    <el-card shadow="never" class="profile">
      <template #header><b>待归簇记录画像</b></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="现象">{{ feature('symptom') || item.title || item.description || '-' }}</el-descriptions-item>
        <el-descriptions-item label="对象">{{ feature('object') || '-' }}</el-descriptions-item>
        <el-descriptions-item label="触发">{{ feature('trigger') || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误签名">{{ feature('error_signature') || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <div v-for="candidate in item.candidates" :key="candidate.clusterId" class="candidate" :class="{ recommended: candidate.recommended }">
      <div class="candidate-head">
        <b>{{ candidate.clusterName }}</b>
        <el-tag v-if="candidate.recommended" type="success" size="small">推荐</el-tag>
        <span>相似度 {{ Math.round(candidate.similarity * 100) }}%</span>
      </div>
      <p>共 {{ candidate.feedbackCount }} 条反馈 · 模块：{{ candidate.module }} · 类型：{{ candidate.issueCategory }}</p>
      <div class="compare">
        <span><b>标准现象：</b>{{ candidate.canonicalSymptom || '-' }}</span>
        <span><b>标准对象：</b>{{ candidate.canonicalObject || '-' }}</span>
        <span><b>标准触发：</b>{{ candidate.canonicalTrigger || '-' }}</span>
        <span><b>错误签名：</b>{{ candidate.errorSignature || '-' }}</span>
      </div>
      <details><summary>查看技术证据</summary><pre>{{ pretty(candidate.evidence) }}</pre></details>
      <div class="actions">
        <el-button size="small" type="primary" @click="emit('merge', candidate)">确认归入</el-button>
        <el-button v-if="candidate.clusterStatus === 'RESOLVED' && recurrence(candidate.evidence)" size="small" type="warning" @click="emit('reopen', candidate)">复发重开</el-button>
        <el-button size="small" @click="emit('govern', candidate.clusterId)">合并/拆分治理</el-button>
      </div>
    </div>
    <div class="footer-actions">
      <el-button type="success" plain @click="creating = true">+ 新建簇</el-button>
      <el-input v-if="creating" v-model="name" placeholder="输入新簇名称" class="name" />
      <el-button v-if="creating" type="primary" @click="emit('create', name)">创建</el-button>
      <el-button type="danger" plain @click="emit('reject')">拒绝全部候选</el-button>
      <el-button @click="emit('skip')">暂缓/待补充</el-button>
      <el-button @click="emit('unlink')">解除当前主关联</el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { ClusterCandidate, ClusterReviewItem, ClusterReviewPriority, JsonEvidence } from '@/api/cluster'

const props = defineProps<{ item: ClusterReviewItem }>()
const emit = defineEmits<{
  merge: [ClusterCandidate]
  reopen: [ClusterCandidate]
  govern: [number]
  create: [string]
  reject: []
  skip: []
  unlink: []
}>()
const creating = ref(false)
const name = ref('')

function parsed(value: JsonEvidence | undefined): Record<string, unknown> {
  try {
    const result = typeof value === 'string' ? JSON.parse(value) : value
    return result && typeof result === 'object' && !Array.isArray(result) ? result as Record<string, unknown> : {}
  } catch {
    return {}
  }
}
function feature(key: string) {
  const value = parsed(props.item.featureSnapshot)
  return value[key] ?? value[key.replace('_', '')]
}
function pretty(value: JsonEvidence | undefined) { return JSON.stringify(parsed(value), null, 2) }
function recurrence(value: JsonEvidence | undefined) { return parsed(value).historical_recurrence === true }
function priorityLabel(priority: ClusterReviewPriority) { return priority === 'P1' ? 'P1 紧急' : priority === 'P2' ? 'P2 较高' : 'P3 普通' }
function slaText(row: ClusterReviewItem) {
  if (!row.slaEnabled) return '开发阶段不计时'
  const seconds = Math.abs(row.slaRemainingSeconds || 0)
  const text = seconds >= 86_400 ? `${Math.ceil(seconds / 86_400)}天` : seconds >= 3_600 ? `${Math.ceil(seconds / 3_600)}小时` : `${Math.ceil(seconds / 60)}分钟`
  return row.overdue ? `超时 ${text}` : `剩余 ${text}`
}
</script>

<style scoped>
.detail-header,.candidate-head{display:flex;align-items:center;justify-content:space-between;gap:8px;font-weight:600}.profile{margin-bottom:12px}.candidate{padding:12px;border:1px solid var(--border-light);border-radius:8px;margin-bottom:10px}.recommended{border-color:var(--color-success);background:#f0f9eb}.candidate p,details{font-size:12px;color:var(--text-secondary);margin-top:7px}.compare{display:grid;grid-template-columns:1fr 1fr;gap:6px 16px;padding:8px 0;font-size:13px}pre{margin-top:5px;padding:8px;max-height:180px;overflow:auto;background:var(--bg-body);white-space:pre-wrap}.actions,.footer-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:9px}.footer-actions{padding-top:16px;border-top:1px solid var(--border-light)}.name{width:200px}@media(max-width:760px){.compare{grid-template-columns:1fr}.detail-header{align-items:flex-start;flex-direction:column}}
</style>
