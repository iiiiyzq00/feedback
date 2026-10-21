const FEEDBACK_STATUSES = new Set(['PENDING', 'PROCESSING', 'RESOLVED', 'CLOSED'])

export function positiveIntegerIds(rows: Array<{ id?: unknown }>) {
  return [...new Set(rows.map(row => Number(row.id)).filter(id => Number.isSafeInteger(id) && id > 0))]
}

export function receiptIdsForRetry(rows: Array<{ receiptId?: unknown; retryable?: unknown }>) {
  return [...new Set(rows
    .filter(row => row.retryable === true || Number(row.retryable) === 1)
    .map(row => String(row.receiptId ?? '').trim())
    .filter(Boolean))]
}

export function feedbackBatchUpdate(rows: Array<{ id?: unknown }>, statusValue: string) {
  const ids = positiveIntegerIds(rows)
  if (!ids.length) return { error: '请选择有效的反馈记录' as const }
  const status = statusValue.trim().toUpperCase()
  if (!FEEDBACK_STATUSES.has(status)) return { error: '请选择有效的目标状态' as const }
  return { payload: { ids, status } }
}

export function retryResultMessage(result: { queued?: unknown; skipped?: unknown } | null | undefined) {
  const queued = Math.max(0, Number(result?.queued) || 0)
  const skipped = Math.max(0, Number(result?.skipped) || 0)
  if (!queued) return { type: 'warning' as const, text: '所选记录当前不可重新处理，请按处理建议处置' }
  return { type: 'success' as const, text: `已提交 ${queued} 条重新处理${skipped ? `，跳过 ${skipped} 条` : ''}` }
}
