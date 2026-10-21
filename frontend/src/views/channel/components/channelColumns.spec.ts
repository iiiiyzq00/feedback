import { describe, expect, it } from 'vitest'
import {
  CHANNEL_COLUMNS,
  learningMachineSourceLabel,
  feedbackColumnsFor,
} from './channelColumns'

const labels = (view: keyof typeof CHANNEL_COLUMNS) =>
  CHANNEL_COLUMNS[view].map((column) => column.label)

describe('feedback channel column contract', () => {
  it('freezes the learning-machine product fields and keeps Android out of the list', () => {
    expect(labels('learning_machine')).toEqual([
      '故障发生时间',
      '问题内容',
      '应用名称',
      '来源平台',
      '关键信息',
      '分析证据',
    ])
    expect(labels('learning_machine')).not.toContain('Android 版本')
  })

  it('preserves the approved aggregate and Jira field order', () => {
    expect(labels('all')).toEqual(['反馈时间', '问题内容', '关键信息', '分析证据'])
    expect(labels('jira')).toEqual([
      '故障发生时间',
      '问题内容',
      'Jira 编号',
      'Jira经办人',
      '优先级',
      'Jira解决结果',
      'Jira 更新时间',
      '关键信息',
      '分析证据',
    ])
  })

  it('renders source traceability from the preserved backend channel code', () => {
    expect(learningMachineSourceLabel('SDK')).toBe('SDK')
    expect(learningMachineSourceLabel('station_feedback')).toBe('旧反馈平台')
    expect(learningMachineSourceLabel('unexpected')).toBe('未知来源')
    expect(learningMachineSourceLabel(null)).toBe('未知来源')
  })

  it('falls back to the aggregate contract for an unknown view without mutating definitions', () => {
    expect(feedbackColumnsFor('unexpected')).toBe(CHANNEL_COLUMNS.all)
    expect(feedbackColumnsFor(undefined)).toBe(CHANNEL_COLUMNS.all)
  })
})
