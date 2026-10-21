import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MetricCard from './MetricCard.vue'

describe('MetricCard', () => {
  it('按中文格式展示数字和单位', () => {
    const wrapper = mount(MetricCard, {
      props: { title: '反馈记录', value: 12580, suffix: '条' },
      global: { stubs: { ElTooltip: true, ElIcon: true } },
    })
    expect(wrapper.text()).toContain('12,580')
    expect(wrapper.text()).toContain('条')
  })
})
