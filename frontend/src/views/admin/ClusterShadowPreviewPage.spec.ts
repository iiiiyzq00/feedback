import { describe, expect, it } from 'vitest'
import pageSource from './ClusterShadowPreviewPage.vue?raw'

describe('ClusterShadowPreviewPage analysis presentation', () => {
  it('uses the problem analysis presentation with independent paging', () => {
    expect(pageSource).toContain('按工程对象与故障表现归簇')
    expect(pageSource).toContain('activeSection')
    expect(pageSource).toContain("analysisSection: activeSection.value === 'PENDING_APPLICATION'")
    expect(pageSource).toContain('只看跨渠道问题')
  })

  it('shows only facts available in the safe shadow projection', () => {
    ;['具体问题', '反馈数量', '反馈渠道', '问题状态', '工程线索', '操作']
      .forEach((label) => expect(pageSource).toContain(`label="${label}"`))
    expect(pageSource).toContain('row.problem')
    expect(pageSource).not.toContain('label="影响应用"')
    expect(pageSource).toContain('analysisSections')
    expect(pageSource).toContain('这些内容尚未伪造成具体问题簇')
    expect(pageSource).not.toContain('五项硬边界覆盖')
    expect(pageSource).not.toContain('Provisional 成员')
    expect(pageSource).not.toContain("name: 'ClusterDetail'")
  })

  it('keeps stale-response and fail-closed behavior', () => {
    expect(pageSource).toContain('const sequence = ++searchSequence')
    expect(pageSource).toContain('if (sequence !== searchSequence) return')
    expect(pageSource).toContain('不会回退到旧数据')
  })

  it('distinguishes the promoted analysis surface from the standalone preview', () => {
    expect(pageSource).toContain("mode?: 'preview' | 'primary'")
    expect(pageSource).toContain('VERIFIED 新版分析')
    expect(pageSource).toContain('Shadow 只读预览')
  })
})
