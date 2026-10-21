import { defineStore } from "pinia";
import { ref, reactive } from "vue";

export interface ChannelHealth {
  channel: string;
  label: string;
  status: "green" | "yellow" | "red";
  lastIngestTime: string | null;
  recordCount: number;
  statusReason?: string;
  latestBusinessTime?: string | null;
  freshnessLagSeconds?: number | null;
  freshnessThresholdSeconds?: number;
  freshnessSlaStatus?: "MET" | "BREACHED" | "NO_DATA";
  qualityPassRate?: number | null;
  qualityScore?: number | null;
  qualityLevel?: "green" | "yellow" | "red" | "none";
  qualitySampleCount?: number;
  qualityDefinition?: "CURRENT_EFFECTIVE_CHANNEL" | "RECORD_WEIGHTED" | "IMPORT_BATCH_WEIGHTED";
  batchFailedRows?: number;
  failureCount?: number;
  latestBatch?: Record<string, any> | null;
  passCount?: number;
  reviewCount?: number;
  failedCount?: number;
  mappingFailed?: number;
  suggestedAction?: { label: string; route: string } | null;
}

export interface PendingTask {
  type: string;
  label: string;
  count: number;
  icon: string;
  route?: string;
  urgent?: boolean;
  todayNew?: number;
  oldestCreatedAt?: string;
  overdueCount?: number;
  reviewCount?: number;
  failedCount?: number;
  scopeLabel?: string;
}

export const useFeedbackStore = defineStore("feedback", () => {
  // 渠道健康状态
  const channelHealth = ref<ChannelHealth[]>([
    {
      channel: "sdk",
      label: "SDK反馈",
      status: "green",
      lastIngestTime: null,
      recordCount: 0,
    },
    {
      channel: "station_feedback",
      label: "学习机反馈",
      status: "green",
      lastIngestTime: null,
      recordCount: 0,
    },
    {
      channel: "jira",
      label: "Jira",
      status: "green",
      lastIngestTime: null,
      recordCount: 0,
    },
    {
      channel: "quality",
      label: "质量平台",
      status: "green",
      lastIngestTime: null,
      recordCount: 0,
    },
    {
      channel: "redblack",
      label: "红黑榜",
      status: "green",
      lastIngestTime: null,
      recordCount: 0,
    },
  ]);

  // 待处理任务
  const pendingTasks = ref<PendingTask[]>([
    {
      type: "sdk_failed",
      label: "SDK处理异常",
      count: 0,
      icon: "Warning",
    },
    { type: "mapping_failed", label: "字段变更待处理", count: 0, icon: "Link" },
    {
      type: "import_failed",
      label: "导入失败批次",
      count: 0,
      icon: "UploadFilled",
    },
    {
      type: "masking_pending",
      label: "隐私复核待处理",
      count: 0,
      reviewCount: 0,
      failedCount: 0,
      icon: "Lock",
    },
    { type: "ai_review", label: "AI 分类待复核", count: 0, icon: "Cpu", scopeLabel: "当前待复核总量" },
    {
      type: "cluster_review",
      label: "归簇边界审核",
      count: 0,
      icon: "Connection",
    },
  ]);

  // 漏斗数据
  const funnelData = reactive({
    encrypted: 0,
    passed: 0,
    aiProcessed: 0,
    clustered: 0,
  });

  // SDK实时速率
  const sdkRateData = ref<{ time: string; rate: number }[]>([]);

  function updateChannelHealth(data: Partial<ChannelHealth>[]) {
    data.forEach((d) => {
      const idx = channelHealth.value.findIndex((c) => c.channel === d.channel);
      if (idx >= 0) {
        Object.assign(channelHealth.value[idx], d);
      }
    });
  }

  function updatePendingTasks(data: Partial<PendingTask>[]) {
    data.forEach((d) => {
      const idx = pendingTasks.value.findIndex((t) => t.type === d.type);
      if (idx >= 0) {
        Object.assign(pendingTasks.value[idx], d);
      }
    });
  }

  return {
    channelHealth,
    pendingTasks,
    funnelData,
    sdkRateData,
    updateChannelHealth,
    updatePendingTasks,
  };
});
