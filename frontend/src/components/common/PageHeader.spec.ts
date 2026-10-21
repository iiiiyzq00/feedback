import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PageHeader from './PageHeader.vue'

describe('PageHeader', () => {
  it('展示标题、说明和操作区', () => {
    const wrapper = mount(PageHeader, {
      props: { title: '运营总览', description: '查看业务情况' },
      slots: { actions: '<button>刷新</button>' },
    })
    expect(wrapper.get('h2').text()).toBe('运营总览')
    expect(wrapper.text()).toContain('查看业务情况')
    expect(wrapper.text()).toContain('刷新')
  })
})

