<template>
  <div class="page-container">
    <div class="page-header">
      <el-button text :icon="ArrowLeft" @click="returnToAnalysis"
        >返回</el-button
      >
      <h2 style="margin: 0">问题簇详情</h2>
      <el-tag v-if="pageLoaded">{{ clusterStatusLabel }}</el-tag>
    </div>

    <el-alert
      v-if="pageLoadError"
      class="page-load-error"
      type="error"
      :closable="false"
      show-icon
      title="问题簇详情加载失败"
    >
      <template #default>
        <span>请检查网络或访问权限后重试。</span>
        <el-button text type="primary" :loading="pageLoading" @click="loadPage"
          >重新加载</el-button
        >
      </template>
    </el-alert>

    <!-- 簇统计 -->
    <el-card v-if="pageLoaded" class="section-card" shadow="never">
      <template #header><span class="section-title">簇统计</span></template>
      <div class="cluster-phenomenon">
        <span>问题簇内部名称</span>
        <strong>{{ cluster.name || '簇名称待补充' }}</strong>
      </div>
      <div class="cluster-fact-grid">
        <div><span>产品现象</span><strong>{{ cluster.canonicalSymptom || '产品现象待补充' }}</strong></div>
        <div><span>功能模块</span><strong>{{ cluster.functionModule || '模块待补充' }}</strong></div>
        <div><span>问题分类</span><strong>{{ cluster.issueCategory || '分类待补充' }}</strong></div>
        <div><span>影响应用</span><strong>当前接口未提供应用事实</strong></div>
      </div>
      <div class="analysis-scope">
        <span>统计范围</span><strong>{{ analysisRangeLabel }}</strong>
      </div>
      <div class="cluster-stats">
        <div class="cluster-stat">
          <span class="stat-label">反馈记录数</span>
          <span class="stat-value">{{ cluster.totalFeedback }}</span>
        </div>
        <div class="cluster-stat">
          <span class="stat-label">用户数量</span
          ><span class="stat-value">{{ metric(cluster.uniqueUsers) }}</span>
        </div>
        <div class="cluster-stat">
          <span class="stat-label">设备数量</span
          ><span class="stat-value">{{ metric(cluster.uniqueDevices) }}</span>
        </div>
        <div class="cluster-stat">
          <span class="stat-label">涉及渠道</span>
          <span class="stat-value">{{ cluster.channelCount }}</span>
        </div>
        <div class="cluster-stat">
          <span class="stat-label">最新反馈</span>
          <span class="stat-value-text">{{ cluster.latestTime }}</span>
        </div>
        <div class="cluster-stat">
          <span class="stat-label">活跃天数</span>
          <span class="stat-value">{{ cluster.activeDays }}</span>
        </div>
      </div>
      <el-descriptions :column="3" border class="distribution">
        <el-descriptions-item label="渠道分布"
          >学习机 {{ cluster.stationCount }} / Jira {{ cluster.jiraCount }} /
          质量 {{ cluster.qualityCount }} / 红黑榜
          {{ cluster.redblackCount }}</el-descriptions-item
        >
        <el-descriptions-item label="首次出现">{{
          cluster.firstTime || '-'
        }}</el-descriptions-item
        ><el-descriptions-item label="最近活跃">{{
          cluster.latestTime || '-'
        }}</el-descriptions-item
        ><el-descriptions-item label="持续时间"
          >{{ cluster.activeDays }} 天</el-descriptions-item
        >
      </el-descriptions>
    </el-card>

    <el-card v-if="pageLoaded" shadow="never" style="margin-top: 16px">
      <template #header
        ><span class="section-title">反馈记录与归簇依据</span></template
      >
      <el-alert
        v-if="feedbackError"
        class="feedback-error"
        type="error"
        :closable="false"
        show-icon
        title="反馈记录加载失败"
      >
        <template #default
          ><el-button
            text
            type="primary"
            :loading="feedbackLoading"
            @click="loadFeedback"
            >重新加载</el-button
          ></template
        >
      </el-alert>
      <DataTable :data="feedbackList" :loading="feedbackLoading">
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="decision-detail">
              <div>
                <span>归簇方式</span><strong>{{ decisionLabel(row) }}</strong>
              </div>
              <div>
                <span>程序相似度</span
                ><strong>{{ confidenceText(row.similarity) }}</strong>
              </div>
              <div>
                <span>模型置信度</span
                ><strong>{{ confidenceText(row.aiConfidence) }}</strong>
              </div>
              <div>
                <span>活动模型</span
                ><strong>{{ row.aiModel || '未调用模型' }}</strong>
              </div>
              <div class="decision-reason">
                <span>归簇依据</span
                ><strong>{{ row.decisionReason || '未提供' }}</strong>
              </div>
              <div class="decision-reason">
                <span>AI 解释</span><strong>{{ row.aiExplanation || '未提供' }}</strong>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="sourceRecordId" label="来源记录 ID" min-width="150" />
        <el-table-column
          prop="issueContent"
          label="问题内容"
          min-width="320"
          class-name="feedback-content"
        />
        <el-table-column label="来源" width="100"
          ><template #default="{ row }">{{
            channelLabel(row.sourceChannel)
          }}</template></el-table-column
        >
        <el-table-column label="关键信息" min-width="190"
          ><template #default="{ row }"
            ><div class="controlled-cell">
              <el-button
                v-for="type in controlledInfoTypes(row)"
                :key="type"
                text
                type="warning"
                size="small"
                @click.stop="openDiagnostics(row)"
                >{{ controlledInfoLabel(type) }}</el-button
              ><span v-if="!controlledInfoTypes(row).length"
                >当前记录无可查验数据</span
              >
            </div></template
          ></el-table-column
        >
        <el-table-column label="分析证据" min-width="230"
          ><template #default="{ row }"
            ><GovernedEvidenceViewer
              :source-id="Number(row.id)"
              :counts="{
                attachment: row.attachmentCount,
                image: row.screenshotCount,
                log: row.logCount,
                video: row.videoCount,
              }"
              @view-log="openDiagnostics(row, $event)" /></template
        ></el-table-column>
        <el-table-column label="故障/发布时间" width="190"
          ><template #default="{ row }"
            ><div class="feedback-time">
              <span>{{ feedbackTimeLabel(row.sourceChannel) }}</span
              ><strong>{{ formatDateTime(row.reportedAt) }}</strong>
            </div></template
          ></el-table-column
        >
        <el-table-column label="归簇方式" width="120"
          ><template #default="{ row }"
            ><el-tag size="small" :type="row.aiAction ? 'success' : 'info'">{{
              decisionLabel(row)
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column label="操作" width="72" fixed="right"
          ><template #default="{ row }"
            ><router-link
              class="detail-link"
              :to="{
                path: `/channel/${row.id}`,
                query: { from: route.fullPath },
              }"
              >详情</router-link
            ></template
          ></el-table-column
        >
      </DataTable>
      <el-pagination
        v-if="feedbackTotal > feedbackPageSize"
        class="feedback-pagination"
        layout="total, prev, pager, next"
        :total="feedbackTotal"
        :page-size="feedbackPageSize"
        :current-page="feedbackPage"
        @current-change="changeFeedbackPage"
      />
    </el-card>
    <GovernedDiagnosticsDialog ref="diagnosticsDialog" />
  </div>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft } from '@element-plus/icons-vue';
import DataTable from '@/components/common/DataTable.vue';
import GovernedDiagnosticsDialog from '@/components/evidence/GovernedDiagnosticsDialog.vue';
import GovernedEvidenceViewer from '@/components/evidence/GovernedEvidenceViewer.vue';
import {
  channelLabel,
  confidenceText,
  controlledInfoLabel,
  controlledInfoTypes,
  decisionLabel,
  feedbackTimeLabel,
  formatDateTime,
  metric,
} from './clusterPresentation';
import { useClusterDetail } from './useClusterDetail';

const route = useRoute();
const router = useRouter();
const {
  pageLoading,
  pageLoaded,
  pageLoadError,
  cluster,
  clusterStatusLabel,
  analysisRangeLabel,
  diagnosticsDialog,
  feedbackList,
  feedbackPage,
  feedbackPageSize,
  feedbackTotal,
  feedbackLoading,
  feedbackError,
  returnToAnalysis,
  openDiagnostics,
  loadFeedback,
  changeFeedbackPage,
  loadPage,
} = useClusterDetail(route, router);
</script>

<style scoped src="./ClusterDetail.css"></style>
