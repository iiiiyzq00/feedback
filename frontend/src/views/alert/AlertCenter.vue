<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2>告警中心</h2>
        <p>集中查看业务风险，完成确认、转派与处置闭环</p>
      </div>
      <div class="header-actions">
        <span class="metric-meta">数据更新于 {{ formatTime(generatedAt) }}</span
        ><el-button :icon="Refresh" :loading="loading" @click="loadAlerts"
          >刷新</el-button
        >
      </div>
    </div>
    <div class="alert-stats">
      <button
        v-for="lvl in ['P0', 'P1', 'P2', 'P3']"
        :key="lvl"
        class="alert-stat-item"
        :class="[
          'level-' + lvl.toLowerCase(),
          { active: filters.level === lvl },
        ]"
        @click="applyLevel(lvl)"
      >
        <span class="stat-copy"
          ><b>{{ lvl }}</b
          ><small>未处理</small></span
        ><span class="level-count">{{ stats[lvl] ?? 0 }}</span></button
      ><button
        class="alert-stat-item processed"
        :class="{ active: filters.status === 'RESOLVED' }"
        @click="applyStatus('RESOLVED')"
      >
        <span class="stat-copy"><b>今日</b><small>已处理</small></span
        ><span class="level-count">{{ stats.processedToday ?? 0 }}</span>
      </button>
    </div>
    <el-alert class="p3-digest" type="info" :closable="false" show-icon
      ><template #title
        ><span
          >昨日 P3 汇总 <b>{{ p3Digest.count || 0 }}</b> 条，每日
          {{ p3Digest.schedule || "09:00" }} 生成</span
        ><el-button link type="primary" @click="applyLevel('P3')"
          >查看 P3 告警</el-button
        ></template
      ></el-alert
    >
    <div class="alert-layout">
      <div class="alert-list-panel">
        <DataTable
          :data="alerts"
          :loading="loading"
          :pagination="pagination"
          density="compact"
          empty-text="当前筛选条件下暂无告警"
          @row-click="selectAlert"
          @page-change="pageChange"
          @size-change="sizeChange"
          ><template #toolbar
            ><div class="workbench-toolbar">
              <div class="toolbar-title">
                <b>告警列表</b><span>共 {{ pagination.total }} 条</span>
              </div>
              <div class="filters">
                <el-select
                  v-model="filters.level"
                  placeholder="全部级别"
                  clearable
                  @change="reloadFirst"
                  ><el-option
                    v-for="l in ['P0', 'P1', 'P2', 'P3']"
                    :key="l"
                    :label="l"
                    :value="l" /></el-select
                ><el-select
                  v-model="filters.status"
                  placeholder="全部状态"
                  clearable
                  @change="reloadFirst"
                  ><el-option label="活跃告警" value="active" /><el-option
                    label="待触发"
                    value="PENDING" /><el-option
                    label="触发中"
                    value="FIRING" /><el-option
                    label="已确认"
                    value="ACKNOWLEDGED" /><el-option
                    label="已抑制"
                    value="SUPPRESSED" /><el-option
                    label="已升级"
                    value="ESCALATED" /><el-option
                    label="已恢复"
                    value="RECOVERED" /><el-option
                    label="已关闭"
                    value="CLOSED" /><el-option
                    label="已归档"
                    value="ARCHIVED" /></el-select
                ><el-button link type="primary" @click="resetFilters"
                  >清空筛选</el-button
                >
              </div>
            </div></template
          ><el-table-column prop="level" label="级别" width="68"
            ><template #default="{ row }"
              ><el-tag
                :type="levelType(row.level)"
                effect="dark"
                size="small"
                >{{ row.level }}</el-tag
              ></template
            ></el-table-column
          ><el-table-column label="告警信息" min-width="260"
            ><template #default="{ row }"
              ><div class="alert-title" :title="row.title">{{ row.title }}</div>
              <div class="alert-sub">
                <span>{{ row.ownerTeam || "未分配团队" }}</span
                ><span>编号 #{{ row.id }}</span
                ><span>{{ formatTime(row.triggeredAt) }}</span>
              </div></template
            ></el-table-column
          ><el-table-column
            prop="affectedDevices"
            label="影响值"
            width="76"
            align="center" /><el-table-column
            prop="status"
            label="状态"
            width="96"
            ><template #default="{ row }"
              ><StatusBadge :status="row.status" /></template></el-table-column
        ></DataTable>
      </div>
      <div v-if="selectedAlert" class="alert-detail-panel">
        <el-card shadow="never"
          ><template #header
            ><div class="detail-header">
              <span>{{ selectedAlert.title }}</span
              ><el-button
                text
                :icon="Close"
                @click="selectedAlert = null"
              /></div
          ></template>
          <el-descriptions :column="1" border size="small"
            ><el-descriptions-item label="触发规则"
              >{{ selectedAlert.ruleName }}（{{
                selectedAlert.ruleCode
              }}）</el-descriptions-item
            ><el-descriptions-item label="触发条件">
              <pre>{{
                pretty(selectedAlert.condition)
              }}</pre></el-descriptions-item
            ><el-descriptions-item label="告警来源">{{
              selectedAlert.evidenceDetail?.alertSource ||
              selectedAlert.route?.source ||
              "规则引擎"
            }}</el-descriptions-item
            ><el-descriptions-item label="证据数据">
              <pre>{{ pretty(selectedAlert.evidenceDetail?.evidence) }}</pre>
              <small>评估于 {{ formatTime(selectedAlert.evidenceDetail?.evaluatedAt) }} · 不含原始正文</small>
            </el-descriptions-item
            ><el-descriptions-item label="影响"
              >设备/指标 {{ selectedAlert.affectedDevices }} · 影响分
              {{ selectedAlert.impactScore }} · 同源概率
              {{ selectedAlert.sameSourceProbability }}%</el-descriptions-item
            ><el-descriptions-item label="路由"
              >来源 {{ selectedAlert.route?.source || "规则引擎" }} · 主责
              {{ selectedAlert.ownerTeam || "-" }} · 值班人
              {{
                selectedAlert.commanderName || "未转派"
              }}</el-descriptions-item
            ><el-descriptions-item label="通知渠道">{{
              (selectedAlert.route?.channels || []).join("、") || "-"
            }}</el-descriptions-item
            ><el-descriptions-item
              v-if="selectedAlert.activeSilence"
              label="静默至"
              >{{
                formatTime(selectedAlert.activeSilence.endsAt)
              }}</el-descriptions-item
            ></el-descriptions
          >
          <template v-if="selectedAlert.escalationChain"
            ><h4>
              通知升级链
              <el-tag
                size="small"
                :type="
                  selectedAlert.escalationChain.noEligibleOncall
                    ? 'danger'
                    : 'info'
                "
                >{{ chainStatus(selectedAlert.escalationChain.status) }}</el-tag
              >
            </h4>
            <el-alert
              v-if="selectedAlert.escalationChain.noEligibleOncall"
              class="oncall-blocked"
              title="未配置有效值班人"
              type="error"
              :closable="false"
              show-icon
              ><p>
                主责团队“{{
                  selectedAlert.escalationChain.ownerTeam ||
                  selectedAlert.ownerTeam ||
                  "未配置"
                }}”当前没有有效主值班、备值班或团队负责人，本告警尚未通知到具体责任人。
              </p>
              <p>
                业务告警级别仍为
                {{
                  selectedAlert.escalationChain.originalIncidentSeverity ||
                  selectedAlert.level
                }}；通知治理风险为
                {{ selectedAlert.escalationChain.notificationRisk || "P0" }}。
              </p>
              <el-button
                v-if="auth.hasPermission('access:routing_config')"
                type="danger"
                size="small"
                @click="goOncallConfig"
                >前往值班排班</el-button
              ><span v-else class="permission-help"
                >请联系具有“告警规则配置”权限的管理员维护排班；紧急情况下可使用下方“临时人工转派”。</span
              ></el-alert
            >
            <div
              v-if="selectedAlert.escalationChain.targets?.length"
              class="chain"
            >
              <div
                v-for="t in selectedAlert.escalationChain.targets"
                :key="t.targetOrder"
                class="chain-step"
                :class="`chain-${String(t.status).toLowerCase()}`"
              >
                <b>{{ t.targetOrder }}. {{ targetType(t.targetType) }}</b
                ><span>{{ t.displayName }}</span
                ><small
                  >{{ channels(t.channels) }} ·
                  {{ targetStatus(t.status) }}</small
                >
              </div>
            </div>
            <el-alert
              v-if="
                selectedAlert.escalationChain.lastFailureReason &&
                !selectedAlert.escalationChain.noEligibleOncall
              "
              :title="selectedAlert.escalationChain.lastFailureReason"
              type="warning"
              :closable="false"
          /></template>
          <h4>升级与操作时间轴</h4>
          <el-timeline
            ><el-timeline-item
              v-for="e in selectedAlert.escalations"
              :key="e.id"
              :timestamp="formatTime(e.time)"
              :type="e.type"
              >{{ e.action }}</el-timeline-item
            ></el-timeline
          >
          <h4>历史知识方案</h4>
          <div v-if="selectedAlert.knowledgeMatches?.length">
            <el-card
              v-for="k in selectedAlert.knowledgeMatches"
              :key="k.id"
              shadow="never"
              class="knowledge"
              ><b>{{ k.title }}</b>
              <p>{{ k.solution }}</p></el-card
            >
          </div>
          <span v-else class="muted">暂无已发布匹配知识</span>
          <div
            v-if="auth.hasPermission('action:ack_alert')"
            class="alert-actions"
          >
            <el-button
              type="primary"
              :loading="actionLoading"
              :disabled="selectedAlert.status !== 'FIRING'"
              @click="acknowledge"
              >确认</el-button
            ><el-button
              :disabled="actionLoading || !selectedAlert.incidentId"
              @click="openTransfer"
              >转派</el-button
            ><el-dropdown
              :disabled="
                actionLoading ||
                selectedAlert.level === 'P0' ||
                !!selectedAlert.activeSilence ||
                !['FIRING', 'ACKNOWLEDGED', 'ESCALATED'].includes(
                  selectedAlert.status,
                )
              "
              @command="openSilence"
              ><el-button
                >静默<el-icon><ArrowDown /></el-icon></el-button
              ><template #dropdown
                ><el-dropdown-menu
                  ><el-dropdown-item :command="30">30分钟</el-dropdown-item
                  ><el-dropdown-item :command="60">1小时</el-dropdown-item
                  ><el-dropdown-item :command="120"
                    >2小时</el-dropdown-item
                  ></el-dropdown-menu
                ></template
              ></el-dropdown
            ><el-button
              type="success"
              :loading="actionLoading"
              :disabled="selectedAlert.status !== 'ACKNOWLEDGED'"
              @click="openResolve"
              >解决</el-button
            >
          </div>
        </el-card>
      </div>
      <el-card v-else shadow="never" class="detail-empty"
        ><div class="empty-illustration">!</div>
        <h3>选择一条告警开始处置</h3>
        <p>
          点击左侧告警后，可查看触发证据、影响范围和处置时间轴，并执行确认、转派、静默或解决。
        </p>
        <div class="empty-capabilities">
          <span>确认告警</span><span>转派值班人</span><span>静默降噪</span
          ><span>处置闭环</span>
        </div></el-card
      >
    </div>
    <el-dialog v-model="transferDialog" title="转派告警" width="420px"
      ><el-form label-width="80px"
        ><el-form-item label="值班人"
          ><el-select v-model="actionForm.assigneeId" filterable
            ><el-option
              v-for="u in assignees"
              :key="u.id"
              :label="u.displayName"
              :value="u.id" /></el-select></el-form-item
        ><el-form-item label="原因"
          ><el-input
            v-model="actionForm.reason"
            type="textarea"
            maxlength="300"
            show-word-limit /></el-form-item></el-form
      ><template #footer
        ><el-button @click="transferDialog = false">取消</el-button
        ><el-button type="primary" @click="confirmTransfer"
          >确认转派</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="reasonDialog"
      :title="
        actionMode === 'resolve'
          ? '解决告警'
          : `静默 ${actionForm.minutes} 分钟`
      "
      width="420px"
      ><el-alert
        v-if="actionMode === 'resolve' && selectedAlert?.level === 'P0'"
        title="P0 必须填写人工处置结论，规则自动恢复不会代替闭环"
        type="warning"
        :closable="false"
      /><el-input
        v-model="actionForm.reason"
        type="textarea"
        maxlength="500"
        show-word-limit
        placeholder="请输入可审计的原因/处理结论"
      /><template #footer
        ><el-button @click="reasonDialog = false">取消</el-button
        ><el-button type="primary" @click="confirmReasonAction"
          >确认</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Close, ArrowDown, Refresh } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import DataTable from "@/components/common/DataTable.vue";
import StatusBadge from "@/components/common/StatusBadge.vue";
import {
  ackAlert,
  getAlertDetail,
  getAlertEvidence,
  getAlertList,
  getAlertStatistics,
  getP3Digest,
  getAlertAssignees,
  resolveAlert,
  transferAlert,
  silenceAlert,
} from "@/api/alert";
const route = useRoute(),
  router = useRouter(),
  auth = useAuthStore(),
  loading = ref(false),
  actionLoading = ref(false),
  alerts = ref<any[]>([]),
  selectedAlert = ref<any>(null),
  generatedAt = ref<any>(null),
  transferDialog = ref(false),
  reasonDialog = ref(false),
  actionMode = ref(""),
  assignees = ref<any[]>([]);
const pagination = reactive({ page: 1, size: 20, total: 0 }),
  stats = reactive<any>({ P0: 0, P1: 0, P2: 0, P3: 0, processedToday: 0 }),
  p3Digest = reactive<any>({}),
  filters = reactive<any>({
    level: "",
    status: "active",
    alertId: undefined,
    clusterId: undefined,
  }),
  actionForm = reactive<any>({ assigneeId: null, reason: "", minutes: 30 });
function levelType(l: string) {
  return l === "P0"
    ? "danger"
    : l === "P1"
      ? "warning"
      : l === "P2"
        ? "primary"
        : "info";
}
async function loadAlerts() {
  loading.value = true;
  try {
    const [list, summary, digest] = await Promise.all([
      getAlertList({
        page: pagination.page,
        size: pagination.size,
        level: filters.level || undefined,
        status: filters.status || undefined,
        alertId: filters.alertId,
        clusterId: filters.clusterId,
      }),
      getAlertStatistics(),
      getP3Digest(),
    ]);
    alerts.value = list.records ?? [];
    pagination.total = list.total ?? 0;
    generatedAt.value = list.generatedAt;
    Object.assign(stats, summary);
    Object.assign(p3Digest, digest);
    if (filters.alertId && alerts.value.length)
      await selectAlert(alerts.value[0]);
  } finally {
    loading.value = false;
  }
}
async function selectAlert(row: any) {
  const [detail, evidence] = await Promise.all([
    getAlertDetail(row.id),
    getAlertEvidence(row.id),
  ]);
  selectedAlert.value = { ...detail, evidenceDetail: evidence };
}
function reloadFirst() {
  pagination.page = 1;
  filters.alertId = undefined;
  loadAlerts();
}
function applyLevel(v: string) {
  filters.level = v;
  filters.status = v === "P3" ? "" : "active";
  reloadFirst();
}
function applyStatus(v: string) {
  filters.status = v;
  filters.level = "";
  reloadFirst();
}
function resetFilters() {
  Object.assign(filters, {
    level: "",
    status: "active",
    alertId: undefined,
    clusterId: undefined,
  });
  reloadFirst();
}
function pageChange(p: number) {
  pagination.page = p;
  loadAlerts();
}
function sizeChange(s: number) {
  pagination.size = s;
  reloadFirst();
}
async function refreshDetail() {
  if (selectedAlert.value)
    selectedAlert.value = await getAlertDetail(selectedAlert.value.id);
  await loadAlerts();
}
async function runAction(action: () => Promise<any>, success: string) {
  actionLoading.value = true;
  try {
    await action();
    ElMessage.success(success);
  } catch {
    ElMessage.warning("操作未生效，告警可能已被其他人更新，已刷新最新状态");
  } finally {
    await refreshDetail();
    actionLoading.value = false;
  }
}
async function acknowledge() {
  await runAction(
    () =>
      ackAlert(
        selectedAlert.value.id,
        Number(selectedAlert.value.lifecycleVersion),
      ),
    "已确认",
  );
}
function openTransfer() {
  actionForm.assigneeId = null;
  actionForm.reason = "";
  transferDialog.value = true;
}
async function confirmTransfer() {
  if (!actionForm.assigneeId || actionForm.reason.trim().length < 2)
    return ElMessage.warning("请选择值班人并填写原因");
  await runAction(
    () =>
      transferAlert(selectedAlert.value.id, {
        assigneeId: actionForm.assigneeId,
        reason: actionForm.reason,
        expectedIncidentVersion: Number(
          selectedAlert.value.incidentLifecycleVersion,
        ),
      }),
    "转派成功",
  );
  transferDialog.value = false;
}
function openSilence(m: number) {
  actionMode.value = "silence";
  actionForm.minutes = m;
  actionForm.reason = "";
  reasonDialog.value = true;
}
function openResolve() {
  actionMode.value = "resolve";
  actionForm.reason = "";
  reasonDialog.value = true;
}
async function confirmReasonAction() {
  if (actionForm.reason.trim().length < 2)
    return ElMessage.warning("请填写原因");
  const version = Number(selectedAlert.value.lifecycleVersion);
  await runAction(
    () =>
      actionMode.value === "resolve"
        ? resolveAlert(selectedAlert.value.id, {
            resolution: actionForm.reason,
            expectedVersion: version,
          })
        : silenceAlert(selectedAlert.value.id, {
            minutes: actionForm.minutes,
            reason: actionForm.reason,
            expectedVersion: version,
          }),
    "操作成功",
  );
  reasonDialog.value = false;
}
function pretty(v: any) {
  return v ? JSON.stringify(v, null, 2) : "-";
}
function formatTime(v: any) {
  return v ? new Date(v).toLocaleString("zh-CN") : "-";
}
function goOncallConfig() {
  router.push(
    selectedAlert.value?.escalationChain?.configurationPath ||
      "/admin/alerts?section=oncall",
  );
}
function chainStatus(v: string) {
  return (
    (
      {
        RUNNING: "升级中",
        ACKNOWLEDGED: "已确认",
        EXHAUSTED: "已转人工",
        CANCELLED: "已停止",
      } as any
    )[v] || v
  );
}
function targetType(v: string) {
  return (
    (
      {
        PRIMARY: "主值班",
        BACKUP: "备值班",
        LEADER: "团队负责人",
        PUBLIC: "公共渠道",
      } as any
    )[v] || v
  );
}
function targetStatus(v: string) {
  return (
    (
      {
        PENDING: "等待",
        IN_PROGRESS: "通知中",
        ACKNOWLEDGED: "已确认",
        FAILED: "未确认",
        SKIPPED: "已跳过",
      } as any
    )[v] || v
  );
}
function channels(v: any) {
  try {
    return (Array.isArray(v) ? v : JSON.parse(v || "[]")).join(" → ");
  } catch {
    return "-";
  }
}
onMounted(() => {
  filters.level =
    typeof route.query.level === "string" ? route.query.level : "";
  filters.status =
    typeof route.query.status === "string" ? route.query.status : "active";
  filters.alertId = Number(route.query.alertId) || undefined;
  filters.clusterId = Number(route.query.clusterId) || undefined;
  void loadAlerts();
  void getAlertAssignees().then((data) => {
    assignees.value = data ?? [];
  });
});
</script>
<style scoped>
.page-header,
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-header {
  margin-bottom: 16px;
}
.page-header h2 {
  margin: 0 0 5px;
  font-size: 24px;
  color: var(--text-primary);
}
.page-header p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.metric-meta,
.muted {
  font-size: 12px;
  color: var(--text-secondary);
}
.alert-stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}
.alert-stat-item {
  appearance: none;
  min-width: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid rgba(214, 223, 235, 0.9);
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(31, 45, 61, 0.05);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.2s,
    box-shadow 0.2s,
    transform 0.2s;
}
.alert-stat-item:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-hover);
}
.alert-stat-item.active {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.14);
}
.stat-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-copy b {
  font-size: 15px;
}
.stat-copy small {
  font-size: 12px;
  color: var(--text-secondary);
}
.level-count {
  font-size: 25px;
  line-height: 1;
  font-weight: 700;
}
.level-p0 .level-count {
  color: #a8071a;
}
.level-p1 .level-count {
  color: #e54949;
}
.level-p2 .level-count {
  color: #d48806;
}
.level-p3 .level-count {
  color: #389e0d;
}
.processed .level-count {
  color: #1677ff;
}
.p3-digest {
  margin-bottom: 12px;
}
.p3-digest :deep(.el-alert__title) {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.alert-layout {
  display: grid;
  grid-template-columns: minmax(0, 3fr) minmax(360px, 2fr);
  gap: 14px;
  align-items: start;
}
.alert-list-panel,
.alert-detail-panel,
.detail-empty {
  min-width: 0;
}
.workbench-toolbar {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.toolbar-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  white-space: nowrap;
}
.toolbar-title b {
  font-size: 16px;
}
.toolbar-title span {
  font-size: 12px;
  color: var(--text-secondary);
}
.filters {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}
.filters :deep(.el-select) {
  width: 126px;
}
.alert-list-panel :deep(.table-toolbar) {
  margin-bottom: 12px;
}
.alert-list-panel :deep(.table-scroll .el-table) {
  min-width: 100%;
}
.alert-title {
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.alert-sub {
  display: flex;
  gap: 10px;
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 11px;
}
.alert-sub span {
  white-space: nowrap;
}
.alert-sub span + span:before {
  content: "·";
  margin-right: 10px;
  color: #c0c4cc;
}
.alert-detail-panel {
  max-height: calc(100vh - 110px);
  overflow: auto;
  position: sticky;
  top: 16px;
}
.alert-detail-panel :deep(.el-card__header) {
  padding: 14px 16px;
}
.detail-header > span {
  font-weight: 650;
  line-height: 1.45;
}
.alert-detail-panel pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 0;
  font-size: 12px;
}
.alert-detail-panel h4 {
  margin: 16px 0 8px;
}
.alert-actions {
  position: sticky;
  bottom: 0;
  z-index: 2;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 18px -16px -16px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.96);
  border-top: 1px solid #ebeef5;
  backdrop-filter: blur(8px);
}
.knowledge {
  margin-bottom: 6px;
}
.knowledge p {
  margin: 5px 0;
  font-size: 13px;
}
.chain {
  display: grid;
  gap: 7px;
  margin-bottom: 9px;
}
.chain-step {
  display: grid;
  grid-template-columns: 86px 1fr;
  gap: 3px 8px;
  padding: 8px 10px;
  border-left: 3px solid #c0c4cc;
  background: #f7f8fa;
  border-radius: 4px;
}
.chain-step small {
  grid-column: 1/-1;
  color: var(--text-secondary);
}
.chain-in_progress {
  border-color: #409eff;
  background: #ecf5ff;
}
.chain-acknowledged {
  border-color: #67c23a;
  background: #f0f9eb;
}
.chain-failed {
  border-color: #e6a23c;
  background: #fdf6ec;
}
.oncall-blocked {
  margin-bottom: 10px;
}
.oncall-blocked p {
  margin: 4px 0 8px;
  line-height: 1.55;
}
.permission-help {
  display: block;
  font-size: 12px;
  line-height: 1.55;
}
.oncall-blocked :deep(.el-alert__content) {
  width: 100%;
}
.rca-section {
  margin-top: 18px;
}
.rca-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.rca-heading h4 {
  margin: 0;
}
.rca-heading span {
  font-size: 12px;
  color: var(--text-secondary);
}
.quick-rca-card,
.deep-rca-card {
  padding: 14px;
  border: 1px solid #e5eaf2;
  border-radius: 10px;
  background: #fbfcfe;
}
.quick-rca-card {
  border-left: 4px solid #e6a23c;
  background: #fffaf2;
}
.deep-rca-card {
  border-left: 4px solid #409eff;
}
.rca-title-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.rca-conclusion {
  margin: 10px 0;
  line-height: 1.7;
  font-size: 13px;
  color: #303133;
}
.rca-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 10px 0;
}
.rca-facts > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px 10px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.8);
}
.rca-facts span {
  font-size: 11px;
  color: var(--text-secondary);
}
.rca-facts b {
  font-size: 13px;
  overflow-wrap: anywhere;
}
.rca-clues {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0;
}
.rca-clues > span {
  font-size: 12px;
  color: var(--text-secondary);
}
.rca-actions-guide {
  margin: 10px 0;
  font-size: 13px;
}
.rca-actions-guide ol {
  margin: 6px 0 0;
  padding-left: 20px;
}
.rca-actions-guide li {
  margin: 4px 0;
  line-height: 1.55;
}
.deep-summary {
  display: grid;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}
.detail-empty {
  position: sticky;
  top: 16px;
  min-height: 360px;
  text-align: center;
}
.detail-empty :deep(.el-card__body) {
  display: flex;
  min-height: 326px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 36px;
}
.empty-illustration {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin-bottom: 14px;
  border-radius: 16px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 28px;
  font-weight: 700;
}
.detail-empty h3 {
  margin: 0 0 8px;
  font-size: 17px;
}
.detail-empty p {
  max-width: 360px;
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
}
.empty-capabilities {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 18px;
}
.empty-capabilities span {
  padding: 5px 9px;
  border-radius: 6px;
  background: #f5f7fa;
  color: #606266;
  font-size: 12px;
}
@media (max-width: 1280px) {
  .alert-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .alert-layout {
    grid-template-columns: minmax(0, 58fr) minmax(340px, 42fr);
  }
  .workbench-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
  .filters {
    width: 100%;
    justify-content: flex-start;
  }
}
@media (max-width: 1100px) {
  .alert-layout {
    grid-template-columns: 1fr;
  }
  .alert-detail-panel,
  .detail-empty {
    position: static;
    max-height: none;
  }
  .alert-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 680px) {
  .page-header {
    align-items: flex-start;
  }
  .header-actions {
    align-items: flex-end;
    flex-direction: column;
    gap: 6px;
  }
  .metric-meta {
    display: none;
  }
  .alert-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .filters {
    flex-wrap: wrap;
  }
  .filters :deep(.el-select) {
    flex: 1;
    min-width: 120px;
  }
  .p3-digest :deep(.el-alert__title) {
    align-items: flex-start;
    flex-direction: column;
  }
  .alert-sub span:nth-child(2) {
    display: none;
  }
}
</style>
