<template>
  <div class="page-container" data-testid="cluster-evaluation-page">
    <div class="page-header"><h2>归簇质量统一验收</h2><el-button v-if="latest.status !== 'MISSING'" data-testid="export-evaluation" @click="exportReport">下载验收报告</el-button></div>
    <el-alert type="info" :closable="false" title="只接受100～5000条、四渠道覆盖的脱敏人工标注集；评估始终运行在影子模式，不修改主关联。" />
    <el-card shadow="never" class="summary" v-loading="loading">
      <template #header><span>最新生产门禁证据</span></template>
      <el-empty v-if="latest.status === 'MISSING'" description="尚无评估记录" />
      <el-descriptions v-else :column="3" border data-testid="latest-evaluation">
        <el-descriptions-item label="任务状态">{{ latest.status }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ latest.model }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ latest.completedAt }}</el-descriptions-item>
        <el-descriptions-item label="门禁">{{ summary.gate }}</el-descriptions-item>
        <el-descriptions-item label="样本数">{{ summary.sampleCount }}</el-descriptions-item>
        <el-descriptions-item label="F1">{{ summary.f1 }}</el-descriptions-item>
        <el-descriptions-item label="Precision">{{ summary.precision }}</el-descriptions-item>
        <el-descriptions-item label="Recall">{{ summary.recall }}</el-descriptions-item>
        <el-descriptions-item label="错误合并率">{{ summary.errorMergeRate }}</el-descriptions-item>
        <el-descriptions-item label="簇纯度">{{ metric(summary.purity) }}</el-descriptions-item>
        <el-descriptions-item label="错误拆分率">{{ metric(summary.falseSplitRate) }}</el-descriptions-item>
        <el-descriptions-item label="未归簇率">{{ metric(summary.unclusteredRate) }}</el-descriptions-item>
        <el-descriptions-item label="最终处置结论">{{ summary.remediationConclusionLabel || '-' }}（{{ summary.remediationConclusion || '-' }}）</el-descriptions-item>
        <el-descriptions-item label="平均簇大小">{{ metric(summary.averageClusterSize) }}</el-descriptions-item>
        <el-descriptions-item label="孤立簇比例">{{ metric(summary.singletonClusterRate) }}</el-descriptions-item>
        <el-descriptions-item label="人工好/坏簇">{{ summary.goodClusterCount || 0 }}/{{ summary.badClusterCount || 0 }}</el-descriptions-item>
        <el-descriptions-item label="人工标注覆盖">{{ summary.annotationsComplete ? '完整' : '不完整' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="!summary.clusterMetricsAvailable || !summary.annotationsComplete" type="warning" :closable="false" title="预测簇、金标准簇或人工好/坏簇标注不完整，本次正式门禁不能通过，处置结论为“补齐”。" />
      <template v-if="summary.similarityDistribution">
        <h3>成员相似度分布</h3>
        <el-descriptions :column="4" border data-testid="similarity-distribution">
          <el-descriptions-item label="样本数">{{ summary.similarityDistribution.count }}</el-descriptions-item>
          <el-descriptions-item label="最小值">{{ summary.similarityDistribution.min }}</el-descriptions-item>
          <el-descriptions-item label="P50">{{ summary.similarityDistribution.p50 }}</el-descriptions-item>
          <el-descriptions-item label="P95">{{ summary.similarityDistribution.p95 }}</el-descriptions-item>
          <el-descriptions-item label="低于 0.50">{{ summary.similarityDistribution.below050 }}</el-descriptions-item>
          <el-descriptions-item label="0.50～0.85">{{ summary.similarityDistribution.from050To084 }}</el-descriptions-item>
          <el-descriptions-item label="不低于 0.85">{{ summary.similarityDistribution.atLeast085 }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <h3>验收阈值</h3>
      <el-table :data="thresholdRows" size="small" data-testid="threshold-table">
        <el-table-column prop="label" label="指标" /><el-table-column prop="rule" label="通过条件" />
      </el-table>
      <h3>四渠道切片</h3>
      <el-table :data="channelRows" size="small" data-testid="channel-slices">
        <el-table-column prop="channel" label="渠道" /><el-table-column prop="count" label="样本数" /><el-table-column prop="accuracy" label="准确率" />
      </el-table>
      <template v-if="summary.clusterMetricsAvailable">
        <h3>单簇质量与异常样本</h3>
        <el-table :data="summary.clusterSummaries || []" size="small" data-testid="cluster-summaries">
          <el-table-column prop="clusterId" label="预测簇 ID" /><el-table-column prop="sampleCount" label="样本数" />
          <el-table-column prop="dominantExpectedClusterId" label="主要金标准簇" /><el-table-column prop="purity" label="纯度" />
          <el-table-column prop="qualityVerdict" label="人工结论" /><el-table-column prop="qualityReason" label="标注理由" min-width="180" />
          <el-table-column label="渠道"><template #default="{ row }">{{ row.channels?.join('、') || '-' }}</template></el-table-column>
          <el-table-column label="异常样本"><template #default="{ row }">{{ row.anomalyCaseIds?.join('、') || '-' }}</template></el-table-column>
        </el-table>
      </template>
      <h3>错误与人工推翻样本</h3>
      <el-table :data="summary.errorCases || []" size="small" data-testid="error-cases" empty-text="本次未发现错误或人工推翻样本">
        <el-table-column prop="caseId" label="样本 ID" /><el-table-column prop="channel" label="渠道" />
        <el-table-column prop="predictedClusterId" label="预测簇" /><el-table-column prop="expectedClusterId" label="金标准簇" />
        <el-table-column prop="similarity" label="相似度" /><el-table-column prop="evidence" label="审核证据" />
      </el-table>
    </el-card>
    <el-card shadow="never">
      <template #header><span>提交已审批标注集 JSON</span></template>
      <input data-testid="dataset-file" type="file" accept="application/json,.json" @change="selectFile" />
      <el-button data-testid="submit-evaluation" type="primary" :disabled="!payload" :loading="submitting" @click="submit">执行影子评估</el-button>
      <p class="hint">文件必须包含 datasetVersion、decisionVersion、model、shadow=true、cases 和覆盖全部预测簇的 clusterAnnotations；联系方式、身份或设备指纹不得作为标签依据。</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportLatestClusterEvaluation, getLatestClusterEvaluation, submitClusterEvaluation } from '@/api/admin'

const latest = ref<any>({ status: 'MISSING' }); const loading = ref(false)
const submitting = ref(false); const payload = ref<any>(null)
const summary = computed(() => typeof latest.value.summary === 'string' ? JSON.parse(latest.value.summary) : (latest.value.summary || {}))
const metric=(value:any)=>value===null||value===undefined?'未计算':value
const thresholdLabels:Record<string,string>={f1Min:'F1',errorMergeRateMax:'错误合并率',humanOverturnRateMax:'人工推翻率',purityMin:'簇纯度',falseSplitRateMax:'错误拆分率',unclusteredRateMax:'未归簇率'}
const thresholdRows=computed(()=>Object.entries(summary.value.thresholds||{}).map(([key,value])=>({label:thresholdLabels[key]||key,rule:`${key.endsWith('Min')?'≥':'≤'} ${value}`})))
const channelRows=computed(()=>Object.entries(summary.value.channelSlices||{}).map(([channel,value]:any)=>({channel,count:value.count,accuracy:value.accuracy})))
async function load(){ loading.value=true; try { latest.value=await getLatestClusterEvaluation() } finally { loading.value=false } }
async function selectFile(event: Event){
  const file=(event.target as HTMLInputElement).files?.[0]; payload.value=null
  if(!file)return
  try{ payload.value=JSON.parse(await file.text()) }catch{ ElMessage.error('标注集不是有效 JSON') }
}
async function submit(){ submitting.value=true; try{ const result=await submitClusterEvaluation(payload.value); ElMessage[result.passed?'success':'error'](`评估${result.gate}`); await load() }finally{submitting.value=false} }
async function exportReport(){const blob=await exportLatestClusterEvaluation();const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download='cluster-evaluation-latest.json';link.click();URL.revokeObjectURL(url)}
onMounted(load)
</script>

<style scoped>.page-header{display:flex;align-items:center;justify-content:space-between}.summary{margin:16px 0}.summary h3{margin:20px 0 8px}.hint{color:var(--text-secondary);font-size:12px}.el-button{margin-left:12px}</style>
