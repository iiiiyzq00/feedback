import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GovernedLogPreview from './GovernedLogPreview.vue'

describe('GovernedLogPreview', () => {
  it('renders metadata and only the visible line window', async () => {
    const lines = Array.from({ length: 1_000 }, (_, index) => `line-${index + 1}`)
    const wrapper = mount(GovernedLogPreview, {
      props: {
        lines,
        receivedBytes: 5 * 1024 * 1024,
        truncated: true,
        visibility: 'PARTIALLY_HIDDEN',
      },
      global: {
        stubs: {
          'el-tag': { template: '<span><slot /></span>' },
          'el-empty': { template: '<div><slot /></div>' },
        },
      },
    })

    expect(wrapper.text()).toContain('日志内容（敏感片段已隐藏）')
    expect(wrapper.text()).toContain('已截断')
    expect(wrapper.text()).toContain('共 1000 行')
    expect(wrapper.findAll('.governed-log__line').length).toBeLessThan(100)

    const viewport = wrapper.get('.governed-log__viewport')
    Object.defineProperty(viewport.element, 'clientHeight', { value: 440, configurable: true })
    ;(viewport.element as HTMLElement).scrollTop = 22 * 500
    await viewport.trigger('scroll')
    expect(wrapper.text()).toContain('line-501')
    expect(wrapper.text()).not.toContain('line-1\n')
  })
})
