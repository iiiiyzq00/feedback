import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StationFeedbackPage from './StationFeedbackPage.vue'

describe('StationFeedbackPage', () => {
  it('combines SDK and station records while preserving their backend channels', () => {
    const wrapper = mount(StationFeedbackPage, {
      global: {
        stubs: {
          ChannelFeedbackList: {
            props: ['view', 'queryChannels', 'extraFilters'],
            template: '<div data-test="station-list">{{ view }}:{{ queryChannels.join(",") }}:{{ extraFilters.length }}</div>',
          },
        },
      },
    })
    expect(wrapper.text()).toContain('学习机反馈')
    expect(wrapper.get('[data-test="station-list"]').text()).toBe('learning_machine:SDK,STATION_FEEDBACK:0')
    expect(wrapper.text()).toContain('统一展示、来源可追溯')
  })
})
