<template>
  <div class="page-container" data-testid="feedback-detail-page">
    <div class="page-header nav-header">
      <el-button text :icon="ArrowLeft" @click="returnToList">返回</el-button>
      <h2>反馈详情 #{{ id }}</h2>
      <div class="record-nav">
        <el-button
          :disabled="!detail.navigation?.previousId"
          @click="go(detail.navigation?.previousId)"
          >上一条</el-button
        >
        <el-button
          :disabled="!detail.navigation?.nextId"
          @click="go(detail.navigation?.nextId)"
          >下一条</el-button
        >
      </div>
    </div>

    <div class="detail-layout" v-loading="loading">
      <el-card class="detail-section summary-card" shadow="never">
        <div class="summary-heading">
          <div>
            <div class="summary-kicker">
              {{ channelLabel(detail.sourceChannel) }} ·
              {{ detail.sourceRecordId || `平台 #${detail.id}` }}
            </div>
            <h3>{{ detail.title || '未命名反馈' }}</h3>
            <p>{{ detail.description || '暂无问题描述' }}</p>
          </div>
          <StatusBadge :status="detail.severity" size="default" />
        </div>
        <div class="summary-grid summary-grid--compact">
          <div class="summary-item summary-item--time">
            <span>{{ primaryTimeLabel }}</span
            ><b>{{ primaryTimeValue }}</b
            ><small>{{ faultTimeEvidence }}</small>
          </div>
          <div class="summary-item">
            <span>来源记录</span
            ><b>{{ detail.sourceRecordId || `平台 #${detail.id}` }}</b
            ><small>{{ channelLabel(detail.sourceChannel) }}</small>
          </div>
          <div class="summary-item">
            <span>问题证据</span><b>{{ evidenceSummary }}</b
            ><small>{{ detail.module || '功能模块待分类' }}</small>
          </div>
        </div>
      </el-card>
      <el-card class="detail-section source-section" shadow="never">
        <template #header
          ><div class="section-heading">
            <div>
              <span class="section-eyebrow">SOURCE</span
              ><span class="section-title">反馈来源与记录信息</span>
            </div>
            <span class="section-hint"
              >来源事实、时间依据与入库信息集中展示</span
            >
          </div></template
        >
        <div class="top-bar">
          <div class="source-info">
            <ChannelSourceIcon :channel="detail.sourceChannel" /><b>{{
              channelLabel(detail.sourceChannel)
            }}</b>
            <el-tag
              size="small"
              :type="detail.externalLinkAvailable ? 'primary' : 'info'"
              >{{
                detail.externalLinkAvailable ? '可查看只读外链' : '无授权外链'
              }}</el-tag
            >
            <span class="source-resolution"
              >{{
                detail.sourceChannel === 'jira'
                  ? 'Jira解决结果'
                  : '来源解决结果'
              }}：{{ sourceResolution }}</span
            >
            <el-button
              v-if="detail.externalLinkAvailable"
              text
              type="primary"
              :icon="Link"
              @click="openExternal"
              >查看来源</el-button
            >
          </div>
          <div class="source-audit">
            <span>来源更新 {{ detail.sourceUpdatedAt || '-' }}</span
            ><span
              >平台同步
              {{ detail.platformUpdatedAt || detail.ingestTime || '-' }}</span
            >
          </div>
        </div>
        <div class="source-overview-grid section-body">
          <div>
            <span>来源记录 ID</span><b>{{ detail.sourceRecordId || '-' }}</b>
          </div>
          <div>
            <span>{{
              detail.sourceChannel === 'jira' ? 'Jira经办人' : '来源经办人'
            }}</span
            ><b>{{ sourceAssignee }}</b>
          </div>
          <div>
            <span>{{ primaryTimeLabel }}</span
            ><b>{{ primaryTimeValue }}</b>
          </div>
          <div v-if="detail.sourceChannel !== 'redblack'">
            <span>{{ timeEvidenceLabel }}</span
            ><b>{{ faultTimeEvidence }}</b>
          </div>
          <div>
            <span>{{
              detail.sourceChannel === 'jira' ? 'Jira解决结果' : '来源解决结果'
            }}</span
            ><b>{{ sourceResolution }}</b>
          </div>
          <div>
            <span>入库时间</span><b>{{ detail.ingestTime || '-' }}</b>
          </div>
          <div>
            <span>来源更新时间</span><b>{{ detail.sourceUpdatedAt || '-' }}</b>
          </div>
          <div>
            <span>脱敏 / 质量</span
            ><b
              >{{ detail.maskingStatus || '-' }} /
              {{ percent(detail.qualityScore) }}</b
            >
          </div>
        </div>
        <el-collapse class="technical-collapse section-body"
          ><el-collapse-item title="来源与入库技术信息">
            <el-descriptions :column="3" border>
              <el-descriptions-item label="平台 ID"
                >{{ detail.id }}
                <el-button text type="primary" @click="copyId"
                  >复制</el-button
                ></el-descriptions-item
              >
              <el-descriptions-item label="来源记录 ID">{{
                detail.sourceRecordId || '-'
              }}</el-descriptions-item>
              <el-descriptions-item :label="primaryTimeLabel">{{
                primaryTimeValue
              }}</el-descriptions-item>
              <el-descriptions-item
                v-if="detail.sourceChannel !== 'redblack'"
                :label="timeEvidenceLabel"
                >{{ faultTimeEvidence }}</el-descriptions-item
              >
              <el-descriptions-item label="入库时间">{{
                detail.ingestTime || '-'
              }}</el-descriptions-item>
              <el-descriptions-item label="来源更新时间">{{
                detail.sourceUpdatedAt || '-'
              }}</el-descriptions-item>
              <el-descriptions-item label="导入批次">{{
                detail.importBatchCode || detail.importBatchId || '实时接入'
              }}</el-descriptions-item>
              <el-descriptions-item label="批次状态"
                ><StatusBadge
                  v-if="detail.importBatchStatus"
                  :status="detail.importBatchStatus"
                /><span v-else>-</span></el-descriptions-item
              >
              <el-descriptions-item label="脱敏/质量"
                >{{ detail.maskingStatus || '-' }} /
                {{ percent(detail.qualityScore) }}</el-descriptions-item
              >
            </el-descriptions>
          </el-collapse-item></el-collapse
        >
      </el-card>

      <el-card class="detail-section basic-section" shadow="never">
        <template #header
          ><div class="section-heading">
            <div>
              <span class="section-eyebrow">OVERVIEW</span
              ><span class="section-title">问题画像</span>
            </div>
            <span class="section-hint">用于快速判断问题类型和影响范围</span>
          </div></template
        >
        <div class="horizontal-content horizontal-content--overview">
          <div class="fact-grid">
            <div class="fact-item fact-item--wide">
              <span>问题标题</span><b>{{ detail.title || '未命名反馈' }}</b>
            </div>
            <div class="fact-item">
              <span>功能模块</span><b>{{ detail.module || '待识别' }}</b>
            </div>
            <div class="fact-item">
              <span>问题类型</span
              ><b>{{
                detail.problemType || detail.issueCategory || '未提供'
              }}</b>
            </div>
            <div class="fact-item">
              <span>使用阶段</span><b>{{ detail.usageStage || '未提供' }}</b>
            </div>
            <div class="fact-item">
              <span>复现性</span><b>{{ detail.reproducibility || '未提供' }}</b>
            </div>
            <div class="fact-item">
              <span>严重度</span>
              <div class="inline-status">
                <StatusBadge :status="detail.severity ?? undefined" /><el-tag
                  v-if="detail.severityProvisional"
                  size="small"
                  type="warning"
                  effect="plain"
                  >AI建议·待复核</el-tag
                >
              </div>
            </div>
            <div class="fact-item">
              <span>情感 / 地区</span
              ><b>{{
                [detail.emotion, detail.region].filter(Boolean).join(' · ') ||
                '未提供'
              }}</b>
            </div>
            <div class="fact-item fact-item--wide">
              <span>设备 / 版本</span><b>{{ detail.deviceInfo || '未提供' }}</b>
            </div>
          </div>
          <div class="long-copy">
            <div class="long-copy__heading"><span>问题描述</span></div>
            <div>
              <p>{{ detail.description || '来源未提供问题描述' }}</p>
            </div>
          </div>
        </div>
      </el-card>
      <el-card class="detail-section ai-section" shadow="never">
        <template #header
          ><div class="section-heading">
            <div>
              <span class="section-eyebrow">ASSIST</span
              ><span class="section-title">AI 辅助分析</span>
            </div>
            <span class="section-hint">辅助判断，不替代人工结论</span>
          </div></template
        >
        <template v-if="detail.aiResult"
          ><div class="horizontal-content horizontal-content--ai">
            <div class="ai-result-grid">
              <div>
                <span>建议分类</span
                ><b>{{ detail.aiResult.category || '待识别' }}</b>
              </div>
              <div>
                <span>建议严重度</span
                ><b>{{ detail.aiResult.severity || '待识别' }}</b>
              </div>
              <div>
                <span>应用 / 模块</span
                ><b>{{
                  `${detail.aiResult.application || '待识别'} / ${detail.aiResult.functionModule || '待识别'}`
                }}</b>
              </div>
              <div>
                <span>情绪 / 风险</span
                ><b>{{
                  `${detail.aiResult.sentiment || '待识别'} / ${detail.aiResult.riskType || '待识别'}`
                }}</b>
              </div>
              <div>
                <span>建议团队</span
                ><b>{{ detail.aiResult.recommendedTeam || '待确认' }}</b>
              </div>
              <div class="confidence">
                <span>模型置信度</span
                ><el-progress
                  :percentage="
                    Math.round(Number(detail.aiResult.confidence || 0) * 100)
                  "
                  :stroke-width="8"
                />
              </div>
            </div>
            <div class="long-copy long-copy--ai">
              <div class="long-copy__heading">
                <span>分析摘要</span><small>仅作辅助判断</small>
              </div>
              <div>
                <p>{{ detail.aiResult.summary || '暂未生成摘要' }}</p>
                <p v-if="detail.aiResult.reasoning" class="reasoning-copy">
                  {{ detail.aiResult.reasoning }}
                </p>
              </div>
            </div>
          </div>
          <el-collapse class="technical-collapse"
            ><el-collapse-item title="分析技术信息"
              ><div class="technical-facts">
                <span
                  >数据来源
                  <b>{{ aiSources(detail.aiResult.dataSources) }}</b></span
                ><span
                  >处理时间
                  <b>{{ detail.aiResult.processedAt || '-' }}</b></span
                ><span
                  >模型版本
                  <b>{{ detail.aiResult.modelVersion || '-' }}</b></span
                ><span
                  >Prompt 版本
                  <b>{{ detail.aiResult.promptVersion || '-' }}</b></span
                >
              </div></el-collapse-item
            ></el-collapse
          ></template
        ><el-empty v-else description="尚未生成 AI 结果" />
      </el-card>
      <el-card class="detail-section channel-section" shadow="never">
        <template #header
          ><div class="section-heading">
            <div>
              <span class="section-eyebrow">EVIDENCE</span
              ><span class="section-title">渠道证据与关键信息</span>
            </div>
            <span class="section-hint">按技术/产品角色展示原文</span>
          </div></template
        >
        <div v-if="channelMetaEntries.length" class="channel-fact-grid">
          <div
            v-for="item in channelMetaEntries"
            :key="item.key"
            class="channel-fact"
          >
            <span>{{ item.label }}</span
            ><b :title="item.value">{{ item.value }}</b>
          </div>
        </div>
        <div v-else class="compact-empty">该渠道暂无扩展事实</div>
        <div
          class="diagnostic-access section-body"
          :class="{
            'diagnostic-access--empty':
              !detail.diagnosticAvailability?.available,
          }"
        >
          <div class="diagnostic-access-content">
            <b>关键信息</b>
            <div v-if="keyDiagnostics.length" class="diagnostic-values">
              <div v-for="(item, index) in keyDiagnostics" :key="`${item.type}-${index}`">
                <span>{{ diagnosticLabel(item.type) }}</span>
                <strong>{{ item.value || '未提供' }}</strong>
              </div>
            </div>
            <p v-else>{{ diagnosticsError || diagnosticAvailabilitySummary }}</p>
            <small v-if="keyDiagnostics.length">{{ diagnosticsVisibility === 'RAW' ? '当前展示原文' : '当前展示局部隐藏内容' }}</small>
          </div>
        </div>
        <template v-if="attachments.length || logDiagnostics.length">
          <GovernedEvidenceViewer
            class="section-body"
            :source-id="id"
            :attachments="attachments"
            :log-attachments="logDiagnostics"
            :counts="{
              attachment: attachments.length + evidenceCounts.log,
              image: evidenceCounts.image,
              log: evidenceCounts.log,
              video: evidenceCounts.video,
            }"
            mode="detail"
            @view-log="openLog" />
        </template>
        <div v-else class="attachment-empty">
          <span>暂无附件</span
          ><small>有安全附件时将在这里显示查验状态与授权入口</small>
        </div>
      </el-card>

      <el-card class="detail-section quality-section" shadow="never">
        <template #header
          ><div class="section-heading">
            <div>
              <span class="section-eyebrow">GOVERNANCE</span
              ><span class="section-title">数据可信度</span>
            </div>
            <span class="section-hint">脱敏、映射与质量证据</span>
          </div></template
        >
        <div class="horizontal-content horizontal-content--governance">
          <div class="governance-metrics">
            <div>
              <span>脱敏状态</span><b>{{ detail.maskingStatus || '未提供' }}</b>
            </div>
            <div>
              <span>质量评分</span><b>{{ percent(detail.qualityScore) }}</b>
            </div>
            <div>
              <span>转换历史</span
              ><el-button text type="primary" @click="showFieldHistory = true"
                >查看 {{ fieldHistory.length }} 条</el-button
              >
            </div>
          </div>
          <div class="version-list">
            <div>
              <span>映射规则</span
              ><code>{{ detail.mappingRule || '未记录' }}</code>
            </div>
            <div>
              <span>标准化版本</span
              ><code :title="detail.normalizationVersion ?? undefined">{{
                detail.normalizationVersion || '未记录'
              }}</code>
            </div>
            <div>
              <span>质量规则</span
              ><code>{{ detail.qualityRuleVersion || '未记录' }}</code>
            </div>
          </div>
        </div>
      </el-card>

      <el-card class="detail-section" shadow="never">
        <template #header
          ><div class="top-bar">
            <div>
              <span class="section-title">同类反馈</span
              ><span class="related-heading-hint"
                >每行对应一条具体反馈，不再用规范簇名代替反馈内容</span
              >
            </div>
            <el-button
              v-if="detail.clusterSummary"
              text
              type="primary"
              @click="
                router.push(
                  `/report/cluster/${detail.clusterSummary.clusterId}`,
                )
              "
              >查看完整簇（{{ detail.clusterSummary.total }}）</el-button
            >
          </div></template
        >
        <el-table
          v-loading="relatedLoading"
          :data="relatedRecords"
          empty-text="暂无其他同簇反馈"
        >
          <el-table-column label="反馈来源" min-width="180"
            ><template #default="{ row }"
              ><div class="related-source">
                <div>
                  <ChannelSourceIcon :channel="row.sourceChannel" /><b>{{
                    channelLabel(row.sourceChannel)
                  }}</b>
                </div>
                <small
                  >来源 {{ row.sourceRecordId || '-' }} · 平台 #{{
                    row.id
                  }}</small
                >
              </div></template
            ></el-table-column
          >
          <el-table-column label="反馈内容" min-width="420"
            ><template #default="{ row }"
              ><p class="related-feedback-content">
                {{ row.feedbackContent || '该反馈暂无问题内容' }}
              </p></template
            ></el-table-column
          >
          <el-table-column label="故障/发布时间" min-width="170"
            ><template #default="{ row }">{{
              row.occurredAt || '-'
            }}</template></el-table-column
          >
          <el-table-column label="归簇信息" min-width="260"
            ><template #default="{ row }"
              ><div class="related-cluster-evidence">
                <span
                  >{{ percent(row.similarity) }} ·
                  {{ row.decisionVersion || '版本未记录' }}</span
                >
                <p>{{ row.decisionReason || '未记录归簇依据' }}</p>
              </div></template
            ></el-table-column
          >
          <el-table-column label="操作" width="120" fixed="right"
            ><template #default="{ row }"
              ><el-button text type="primary" @click="go(row.id)"
                >详情</el-button
              ><el-button
                v-if="!feedbackOnly"
                text
                type="danger"
                @click="unlinkRelated(row)"
                >解除</el-button
              ></template
            ></el-table-column
          >
        </el-table>
        <div v-if="relatedTotal" class="related-pagination">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :current-page="relatedPage"
            :page-size="relatedPageSize"
            :total="relatedTotal"
            @current-change="changeRelatedPage"
          />
        </div>
      </el-card>

      <el-card class="detail-section" shadow="never">
        <template #header
          ><span class="section-title">数据处理时间轴</span></template
        >
        <el-timeline
          ><el-timeline-item
            v-for="event in timeline"
            :key="event.id"
            :timestamp="event.time"
          >
            <b>{{ event.action }}</b
            ><span v-if="event.operator"> · {{ event.operator }}</span>
            <div class="muted">{{ event.detail }}</div>
          </el-timeline-item></el-timeline
        >
      </el-card>
      <el-card v-if="!feedbackOnly" class="detail-section" shadow="never">
        <template #header
          ><span class="section-title">RCA 与知识关联</span></template
        >
        <el-collapse
          ><el-collapse-item
            :title="
              rcaKnowledge.cluster
                ? `所属问题簇 #${rcaKnowledge.cluster.id} ${rcaKnowledge.cluster.name} · 历史 RCA ${rcaKnowledge.rca.length} 次`
                : '该记录尚未归入问题簇'
            "
          >
            <template v-if="rcaKnowledge.cluster"
              ><el-timeline
                ><el-timeline-item
                  v-for="r in rcaKnowledge.rca"
                  :key="r.id"
                  :timestamp="r.createdAt"
                  ><b>{{ r.rootCause }}</b
                  >（{{ r.status }}）</el-timeline-item
                ></el-timeline
              >
              <el-divider content-position="left">关联知识条目</el-divider>
              <div
                v-for="k in rcaKnowledge.knowledge"
                :key="k.id"
                class="knowledge-line"
              >
                <b>{{ k.title }}</b> → {{ k.content }}（{{ k.status }}，采纳
                {{ k.adoptionCount }} 次）
              </div>
              <el-empty
                v-if="!rcaKnowledge.knowledge.length"
                description="暂无关联知识"
                :image-size="50"
              />
              <el-button
                text
                type="primary"
                @click="
                  router.push(`/report/cluster/${rcaKnowledge.cluster.id}`)
                "
                >查看完整问题簇 →</el-button
              ></template
            >
          </el-collapse-item></el-collapse
        >
      </el-card>
    </div>

    <el-dialog v-model="showFieldHistory" title="字段转换历史" width="720px">
      <el-timeline
        ><el-timeline-item
          v-for="h in fieldHistory"
          :key="h.id"
          :timestamp="h.createdAt"
        >
          <b>{{ h.fieldName }}</b
          >：{{ h.oldValue ?? '-' }} → {{ h.newValue ?? '-' }}
          <div class="muted">
            {{ h.changeType }} · 操作者
            {{ h.operator || h.changedByType || '-' }} · 映射
            {{ h.mappingVersion || '-' }} · 标准化
            {{ h.normalizationVersion || '-' }} · 原因
            {{ h.changeReason || '-' }}
          </div>
        </el-timeline-item></el-timeline
      >
    </el-dialog>
    <GovernedDiagnosticsDialog ref="diagnosticsDialog" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, Link } from '@element-plus/icons-vue';
import ChannelSourceIcon from './ChannelSourceIcon.vue';
import StatusBadge from '@/components/common/StatusBadge.vue';
import GovernedDiagnosticsDialog from '@/components/evidence/GovernedDiagnosticsDialog.vue';
import GovernedEvidenceViewer from '@/components/evidence/GovernedEvidenceViewer.vue';
import {
  aiSources,
  channelLabel,
  percent,
} from './feedbackDetailPresentation';
import { useFeedbackDetailCompatibility } from './useFeedbackDetailCompatibility';
import { useFeedbackDetailReadModel } from './useFeedbackDetailReadModel';

const route = useRoute();
const router = useRouter();
const readModel = useFeedbackDetailReadModel(route);
const {
  id,
  feedbackOnly,
  loading,
  showFieldHistory,
  timeline,
  fieldHistory,
  attachments,
  diagnostics,
  diagnosticsVisibility,
  diagnosticsError,
  diagnosticsDialog,
  detail,
  rcaKnowledge,
  channelMetaEntries,
  sourceAssignee,
  sourceResolution,
  primaryTimeLabel,
  timeEvidenceLabel,
  primaryTimeValue,
  faultTimeEvidence,
  evidenceCounts,
  evidenceSummary,
  diagnosticAvailabilitySummary,
  openLog,
  unlinkRelated,
  relatedRecords,
  relatedTotal,
  relatedPage,
  relatedPageSize,
  relatedLoading,
  changeRelatedPage,
} = readModel;
const logDiagnostics = computed(() => diagnostics.value.filter((item) => item.source === 'GOVERNED_ATTACHMENT'));
const keyDiagnostics = computed(() => diagnostics.value.filter((item) => item.source !== 'GOVERNED_ATTACHMENT'));
const diagnosticLabels: Record<string, string> = {
  PHONE: '手机号', EMAIL: '邮箱', DEVICE_SN: '设备序列号', USER_ID: '用户 ID',
  AUTHENTICATED_USER_ID: '认证用户 ID', ACCOUNT_CONTEXT: '账号上下文',
  PRIVATE_FILE_MANIFEST: '私有文件清单',
};
function diagnosticLabel(type: string) {
  return diagnosticLabels[type] || type;
}
const { returnToList, go, copyId, openExternal } =
  useFeedbackDetailCompatibility(route, router, id, detail);
</script>

<style scoped src="./FeedbackDetailPage.css"></style>
