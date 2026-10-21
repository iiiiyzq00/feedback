import { describe, expect, it } from 'vitest'
import { feedbackBatchUpdate, positiveIntegerIds, receiptIdsForRetry, retryResultMessage } from './bulkOperations'

describe('bulk operation boundaries', () => {
  it('keeps unique positive safe integer feedback ids', () => {
    expect(positiveIntegerIds([{ id: 3 }, { id: '3' }, { id: 0 }, { id: -1 }, { id: 'bad' }, { id: 4 }])).toEqual([3, 4])
  })

  it('validates feedback selection and target status', () => {
    expect(feedbackBatchUpdate([{ id: 1 }, { id: 1 }], ' resolved ')).toEqual({ payload: { ids: [1], status: 'RESOLVED' } })
    expect(feedbackBatchUpdate([{ id: 'bad' }], 'RESOLVED')).toEqual({ error: '请选择有效的反馈记录' })
    expect(feedbackBatchUpdate([{ id: 1 }], 'DELETED')).toEqual({ error: '请选择有效的目标状态' })
  })

  it('selects unique retryable non-empty receipt ids', () => {
    expect(receiptIdsForRetry([
      { receiptId: ' a ', retryable: true },
      { receiptId: 'a', retryable: 1 },
      { receiptId: 'b', retryable: false },
      { receiptId: '', retryable: true },
    ])).toEqual(['a'])
  })

  it('describes complete, partial, and empty retry results', () => {
    expect(retryResultMessage({ queued: 2 })).toEqual({ type: 'success', text: '已提交 2 条重新处理' })
    expect(retryResultMessage({ queued: 2, skipped: 1 }).text).toBe('已提交 2 条重新处理，跳过 1 条')
    expect(retryResultMessage({ queued: 0, skipped: 2 }).type).toBe('warning')
  })
})
