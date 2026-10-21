import { get } from "./request";

/** 问题簇的应用或地域维度计数；具体字段由所在响应位置决定。 */
export interface ProblemClusterDimension {
  application?: string;
  region?: string;
  count: number;
}

export interface ProblemClusterItem {
  clusterId: number;
  phenomenon: string;
  recordCount: number;
  userCount: number | null;
  deviceCount: number;
  affectedApplications: ProblemClusterDimension[];
  firstOccurredAt: string | null;
  lastOccurredAt: string | null;
  attachmentCount: number;
  screenshotCount: number;
  logCount: number;
  regions: ProblemClusterDimension[];
}

/** 两个分析区各自持有一份该分页合同，页码和总数互不联动。 */
export interface ProblemClusterPage {
  items: ProblemClusterItem[];
  total: number;
  page: number;
  size: number;
}

/** 固定一次服务端分析结果；时间范围用于后续簇详情追溯，不应由尚未提交的筛选值替换。 */
export interface ProblemAnalysisResponse {
  allClusters: ProblemClusterPage;
  crossChannelClusters: ProblemClusterPage;
  startTime: string | null;
  endTime: string | null;
  generatedAt: string;
  metricVersion: string;
}

/** startTime/endTime 必须同时省略或同时提供；省略表示全部历史。 */
export interface ProblemAnalysisRequest {
  startTime?: string;
  endTime?: string;
  allPage: number;
  allSize: number;
  crossChannelPage: number;
  crossChannelSize: number;
}

export function getProblemAnalysis(params: ProblemAnalysisRequest): Promise<ProblemAnalysisResponse> {
  return get("/dashboard/problem-analysis", { params });
}
