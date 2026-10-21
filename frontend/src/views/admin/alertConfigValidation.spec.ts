import { describe, expect, it } from 'vitest'
import { acceptanceValidationError, contractValidationError, isRuleProductionReady, isSha256, providerValidationError, ruleDraftError } from './alertConfigValidation'

const sha = 'a'.repeat(64)

describe('alert configuration validation', () => {
  it.each(['', 'abc', 'g'.repeat(64), 'a'.repeat(63), 'a'.repeat(65)])('rejects invalid SHA-256 evidence %j', value => {
    expect(isSha256(value)).toBe(false)
  })
  it('accepts uppercase and lowercase SHA-256 evidence', () => {
    expect(isSha256(sha)).toBe(true)
    expect(isSha256('ABCDEF'.repeat(10) + 'abcd')).toBe(true)
  })

  it('requires complete contract evidence and a meaningful summary', () => {
    expect(contractValidationError({ evidenceSha256: sha, summary: '不足', expiresAt: new Date() })).toContain('至少6字')
    expect(contractValidationError({ evidenceSha256: sha, summary: '已完成回放验证', expiresAt: new Date() })).toBeNull()
  })

  it('requires provider identity, evidence, reference, and expiry', () => {
    const valid = { channel: 'EMAIL', providerCode: 'smtp-prod', evidenceSha256: sha, evidenceReference: '工单-123456', expiresAt: new Date() }
    expect(providerValidationError({ ...valid, providerCode: ' ' })).toContain('请完整填写')
    expect(providerValidationError(valid)).toBeNull()
  })

  it('requires replay, drill, evidence, and non-empty acceptance samples', () => {
    const empty = { replayRunId: 1, drillId: 2, evidenceSha256: sha, truePositive: 0, falsePositive: 0, falseNegative: 0, trueNegative: 0 }
    expect(acceptanceValidationError(empty)).toContain('不能全部为0')
    expect(acceptanceValidationError({ ...empty, truePositive: 10 })).toBeNull()
    expect(acceptanceValidationError({ ...empty, replayRunId: null })).toContain('请选择回放')
  })

  it('only treats an explicit readiness fact as production-ready', () => {
    expect(isRuleProductionReady(7, {})).toBe(false)
    expect(isRuleProductionReady(7, { 7: { readyToPublish: false } })).toBe(false)
    expect(isRuleProductionReady(7, { 7: { readyToPublish: true } })).toBe(true)
  })

  it('rejects blank rule names after trimming', () => {
    expect(ruleDraftError({ name: '   ' })).toBe('请输入规则名称')
    expect(ruleDraftError({ name: '设备离线告警' })).toBeNull()
  })
})
