import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusBadge from './StatusBadge.vue'

describe('StatusBadge', () => {
  it('renders governed severity and workflow labels', () => {
    expect(mount(StatusBadge, { props: { status: 'S0' }, global: { stubs: { ElTag: { template: '<span><slot /></span>' } } } }).text()).toBe('S0-致命')
    expect(mount(StatusBadge, { props: { status: 'S4' }, global: { stubs: { ElTag: { template: '<span><slot /></span>' } } } }).text()).toBe('S4-无故障影响')
    expect(mount(StatusBadge, { props: { status: 'PENDING_REVIEW' }, global: { stubs: { ElTag: { template: '<span><slot /></span>' } } } }).text()).toBe('待审核')
  })
  it('does not hide an unknown backend state', () => {
    expect(mount(StatusBadge, { props: { status: 'NEW_STATE' }, global: { stubs: { ElTag: { template: '<span><slot /></span>' } } } }).text()).toBe('NEW_STATE')
  })
  it.each([undefined, null, ''])('renders a safe fallback for an empty status: %s', (status) => {
    expect(mount(StatusBadge, { props: { status }, global: { stubs: { ElTag: { template: '<span><slot /></span>' } } } }).text()).toBe('未分级')
  })
})
