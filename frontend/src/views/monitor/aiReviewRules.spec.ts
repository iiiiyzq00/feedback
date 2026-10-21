import { describe, expect, it } from 'vitest'
import { batchReviewPayload, deriveReviewReason, matchesReviewReason, reviewSubmissionError, summarizeReviewQueue } from './aiReviewRules'

describe('AI review governance rules', () => {
  it.each([
    [{ aiSeverity: 'S0', confidence: 0.99 }, 'HIGH_RISK'],
    [{ aiSeverity: 's1', confidence: 0.2 }, 'BOTH'],
    [{ aiSeverity: 'S2', confidence: 0.84 }, 'LOW_CONFIDENCE'],
    [{ aiSeverity: 'S3', confidence: 0.9 }, 'LEGACY_PENDING'],
  ])('derives the review reason for %j', (row, expected) => expect(deriveReviewReason(row, 0.85)).toBe(expected))

  it('includes BOTH records in both risk and confidence filters', () => {
    expect(matchesReviewReason('BOTH', 'HIGH_RISK')).toBe(true)
    expect(matchesReviewReason('BOTH', 'LOW_CONFIDENCE')).toBe(true)
    expect(matchesReviewReason('HIGH_RISK', 'LOW_CONFIDENCE')).toBe(false)
  })

  it('summarizes mutually exclusive reasons and the oldest queue item', () => {
    expect(summarizeReviewQueue([
      { reviewReason: 'BOTH', processedAt: '2026-08-11T02:00:00Z' },
      { reviewReason: 'HIGH_RISK', processedAt: '2026-08-10T02:00:00Z' },
      { reviewReason: 'LOW_CONFIDENCE' }, { reviewReason: 'LEGACY_PENDING' },
    ], 10)).toEqual({ total: 10, both: 1, highRiskOnly: 1, lowConfidenceOnly: 1, legacyPending: 1, oldestProcessedAt: '2026-08-10T02:00:00Z' })
  })

  it('requires an explanation and complete override classification', () => {
    expect(reviewSubmissionError({ action: 'CONFIRM', category: '', severity: '', comment: ' ' })).toBe('请填写复核说明')
    expect(reviewSubmissionError({ action: 'OVERRULE', category: 'BUG', severity: '', comment: '证据充分' })).toContain('分类和严重度')
    expect(reviewSubmissionError({ action: 'OVERRULE', category: 'BUG', severity: 'S1', comment: '证据充分' })).toBeNull()
  })

  it('builds a sanitized batch payload and rejects invalid selections', () => {
    expect(batchReviewPayload([{ sourceId: '12' }, { sourceId: 12 }, { sourceId: -1 }, { sourceId: 'bad' }], ' 批量确认 ')).toEqual({ payload: { sourceIds: [12], action: 'CONFIRM', comment: '批量确认' } })
    expect(batchReviewPayload([{ sourceId: 'bad' }], '说明')).toEqual({ error: '请选择待复核记录' })
    expect(batchReviewPayload([{ sourceId: 1 }], ' ')).toEqual({ error: '请填写复核说明' })
  })
})
