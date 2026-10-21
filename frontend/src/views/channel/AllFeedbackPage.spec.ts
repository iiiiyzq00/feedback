import { describe, expect, it } from 'vitest'
import pageSource from './AllFeedbackPage.vue?raw'
import sidebarSource from '@/components/layout/Sidebar.vue?raw'

describe('AllFeedbackPage feedback shell', () => {
  it('keeps the aggregate channel entry available', () => {
    expect(sidebarSource).toContain('index="/channel/all"')
    expect(sidebarSource).toContain('<span>全部渠道</span>')
  })

  it('uses the same hero form as the channel pages without metric cards', () => {
    expect(pageSource).toContain('ChannelPageHero')
    expect(pageSource).toContain('title="全部渠道反馈"')
    expect(pageSource).toContain("value: '四个反馈渠道'")
    expect(pageSource).toContain("value: '统一公共字段'")
    expect(pageSource).toContain('<ChannelFeedbackList :extra-filters="[]" />')
    expect(pageSource).not.toContain('packageName')
    expect(pageSource).not.toContain('reproducibility')
  })
})
