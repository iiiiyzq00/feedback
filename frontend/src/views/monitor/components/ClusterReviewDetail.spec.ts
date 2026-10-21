import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ClusterReviewDetail from './ClusterReviewDetail.vue'
import type { ClusterReviewItem } from '@/api/cluster'

const item: ClusterReviewItem = {
  id: 1, sourceRecordId: 'SDK-001', title: '课程无法播放', priority: 'P1', reviewVersion: 2,
  similarity: 0.93, candidates: [{ clusterId: 8, clusterName: '视频播放失败', similarity: 0.93, rank: 1, recommended: true, feedbackCount: 12 }],
}
const passthrough = { template: '<div><slot name="header"/><slot/></div>' }

describe('ClusterReviewDetail', () => {
  it('展示推荐候选并触发确认归入事件', async () => {
    const wrapper = mount(ClusterReviewDetail, { props: { item }, global: { stubs: {
      ElCard: passthrough, ElDescriptions: passthrough, ElDescriptionsItem: passthrough,
      ElAlert: true, ElTag: passthrough, ElInput: true,
      ElButton: { template: '<button @click="$emit(\'click\')"><slot/></button>' },
    } } })
    expect(wrapper.text()).toContain('视频播放失败')
    expect(wrapper.text()).toContain('相似度 93%')
    const merge = wrapper.findAll('button').find(button => button.text() === '确认归入')
    expect(merge).toBeTruthy()
    await merge!.trigger('click')
    expect(wrapper.emitted('merge')?.[0]?.[0]).toMatchObject({ clusterId: 8 })
  })
})
