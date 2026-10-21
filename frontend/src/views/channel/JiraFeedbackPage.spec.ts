import { shallowMount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JiraFeedbackPage from './JiraFeedbackPage.vue'
import ChannelFeedbackList from './components/ChannelFeedbackList.vue'
import ChannelPageHero from './components/ChannelPageHero.vue'

describe('JiraFeedbackPage', () => {
  it('shows the complete safe Jira dataset without an extra view filter', () => {
    const wrapper=shallowMount(JiraFeedbackPage)
    const list=wrapper.findComponent(ChannelFeedbackList)
    const hero=wrapper.findComponent(ChannelPageHero)
    expect(hero.props('facts')).toContainEqual({label:'查看口径',value:'全部安全数据'})
    expect(list.props('channel')).toBe('jira')
    expect(list.props('defaultExtras')).toBeUndefined()
    expect(wrapper.find('el-tabs-stub').exists()).toBe(false)
  })
})
