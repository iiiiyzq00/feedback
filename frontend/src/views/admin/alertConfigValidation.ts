export const isSha256 = (value: string) => /^[a-fA-F0-9]{64}$/.test(value)

export function contractValidationError(form: { evidenceSha256: string; summary: string; expiresAt: Date | null }) {
  if (!isSha256(form.evidenceSha256) || form.summary.trim().length < 6 || !form.expiresAt) {
    return '请填写64位证据哈希、至少6字摘要和未来有效期'
  }
  return null
}

export function providerValidationError(form: { channel: string; providerCode: string; evidenceSha256: string; evidenceReference: string; expiresAt: Date | null }) {
  if (!form.channel || !form.providerCode.trim() || !isSha256(form.evidenceSha256) || form.evidenceReference.trim().length < 6 || !form.expiresAt) {
    return '请完整填写供应商、64位证据哈希、证据引用和有效期'
  }
  return null
}

export function acceptanceValidationError(form: { replayRunId: number | null; drillId: number | null; evidenceSha256: string; truePositive: number; falsePositive: number; falseNegative: number; trueNegative: number }) {
  if (!form.replayRunId || !form.drillId || !isSha256(form.evidenceSha256)) return '请选择回放与演练并填写64位证据哈希'
  if (form.truePositive + form.falsePositive + form.falseNegative + form.trueNegative === 0) return '验收样本统计不能全部为0'
  return null
}

export const isRuleProductionReady = (ruleId: number, readinessByRule: Record<number, { readyToPublish?: boolean } | undefined>) =>
  readinessByRule[ruleId]?.readyToPublish === true

export function ruleDraftError(form: { name: string }) {
  return form.name.trim() ? null : '请输入规则名称'
}
