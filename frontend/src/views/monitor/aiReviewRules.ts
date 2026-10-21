export function deriveReviewReason(row: { aiSeverity?: unknown; confidence?: unknown }, threshold: number) {
  const high = ['S0', 'S1'].includes(String(row.aiSeverity ?? '').toUpperCase())
  const confidence = Number(row.confidence)
  const low = Number.isFinite(confidence) && confidence < threshold
  if (high && low) return 'BOTH'
  if (high) return 'HIGH_RISK'
  if (low) return 'LOW_CONFIDENCE'
  return 'LEGACY_PENDING'
}

export function matchesReviewReason(rowReason: string, filterReason: string) {
  if (!filterReason) return true
  if (filterReason === 'HIGH_RISK') return rowReason === 'HIGH_RISK' || rowReason === 'BOTH'
  if (filterReason === 'LOW_CONFIDENCE') return rowReason === 'LOW_CONFIDENCE' || rowReason === 'BOTH'
  return rowReason === filterReason
}

export function summarizeReviewQueue(rows: Array<{ reviewReason: string; processedAt?: string }>, total: number) {
  const counts = { total, both: 0, highRiskOnly: 0, lowConfidenceOnly: 0, legacyPending: 0, oldestProcessedAt: '' }
  for (const row of rows) {
    if (row.reviewReason === 'BOTH') counts.both++
    else if (row.reviewReason === 'HIGH_RISK') counts.highRiskOnly++
    else if (row.reviewReason === 'LOW_CONFIDENCE') counts.lowConfidenceOnly++
    else counts.legacyPending++
  }
  counts.oldestProcessedAt = rows.map(row => row.processedAt).filter((value): value is string => Boolean(value)).sort()[0] ?? ''
  return counts
}

export function reviewSubmissionError(form: { action: string; category: string; severity: string; comment: string }) {
  if (form.comment.trim().length < 2) return '请填写复核说明'
  if (form.action === 'OVERRULE' && (!form.category || !form.severity)) return '请选择问题分类和严重度'
  return null
}

export function batchReviewPayload(rows: Array<{ sourceId?: unknown }>, commentValue: string) {
  const sourceIds = [...new Set(rows.map(row => Number(row.sourceId)).filter(id => Number.isSafeInteger(id) && id > 0))]
  const comment = commentValue.trim()
  if (!sourceIds.length) return { error: '请选择待复核记录' as const }
  if (comment.length < 2) return { error: '请填写复核说明' as const }
  return { payload: { sourceIds, action: 'CONFIRM', comment } }
}
