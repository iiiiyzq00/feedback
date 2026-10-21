const labels: Record<string, string> = {
  PENDING: "处理中",
  PASS: "已安全放行",
  REVIEW_NEEDED: "待人工复核",
  FAIL: "处理失败",
  FAILED: "处理失败",
};

export function privacyStatusLabel(status: unknown): string {
  const key = String(status || "").toUpperCase();
  return labels[key] || "未知状态";
}
