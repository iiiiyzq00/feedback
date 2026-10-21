import { describe, expect, it } from 'vitest'
import pageSource from './ProblemAnalysis.vue?raw'
import apiSource from '@/api/dashboard.ts?raw'

const compact = (source: string) => source.replace(/\s+/g, '')

describe('ProblemAnalysis feedback contract', () => {
  it('rolls the verified shadow analysis out only to admins behind a build switch', () => {
    expect(pageSource).toContain('VITE_VERIFIED_SHADOW_ANALYSIS_ENABLED')
    expect(pageSource).toContain('auth.isAdmin')
    expect(pageSource).toContain("VITE_VERIFIED_SHADOW_ANALYSIS_ENABLED !== 'false'")
    expect(pageSource).toContain('v-if="useVerifiedShadowAnalysis"')
    expect(pageSource).toContain('mode="primary"')
    expect(pageSource).toContain('if (useVerifiedShadowAnalysis.value) return')
  })

  it('keeps only the time range and the analyze and reset actions', () => {
    expect(pageSource).toContain('type="daterange"')
    expect(compact(pageSource)).toContain(compact('>分析</el-button>'))
    expect(compact(pageSource)).toContain(compact('>重置</el-button>'))
    ;['channels', 'severities', 'reproducibilities', 'aiReviewStatus'].forEach((field) => {
      expect(pageSource).not.toContain(`filters.${field}`)
    })
  })

  it('defaults to all historical data and only sends time filters after an explicit selection', () => {
    expect(pageSource).toContain('不选择时分析全部历史数据')
    expect(pageSource).toContain('if (!filters.dateRange) return null')
    expect(pageSource).toContain('filters.dateRange = null')
    expect(pageSource).not.toContain('filters.dateRange = defaultRange()')
    expect(apiSource).toContain('startTime?: string')
    expect(apiSource).toContain('endTime?: string')
  })

  it('renders all clusters and cross-channel clusters as independent paged sections', () => {
    expect(pageSource).toContain('全部去重问题簇明细')
    expect(pageSource).toContain('跨渠道问题簇明细')
    expect(pageSource).toContain('allClusters.page')
    expect(pageSource).toContain('crossChannelClusters.page')
    expect(apiSource).toContain('allPage: number')
    expect(apiSource).toContain('crossChannelPage: number')
  })

  it('shows every required feedback cluster fact without channel or recent-volume columns', () => {
    ;[
      '问题簇现象', '反馈记录数', '用户数量', '设备数量', '影响应用',
      '首次发生时间', '最近发生时间', '典型证据', '涉及区域', '操作',
    ].forEach((label) => expect(pageSource).toContain(`label="${label}"`))
    expect(pageSource).not.toContain('label="影响版本"')
    expect(pageSource).not.toContain('label="渠道数"')
    expect(pageSource).not.toContain('label="渠道"')
    expect(pageSource).not.toContain('近期反馈量')
  })

  it('does not restore removed analysis panels', () => {
    ;['所有分析统一时间对比', '严重度趋势', '应用版本问题量对比', '省份热度排名', '可重现性分布']
      .forEach((label) => expect(pageSource).not.toContain(label))
  })

  it('ignores stale responses when users analyze or page quickly', () => {
    expect(pageSource).toContain('const sequence = ++searchSequence')
    expect(pageSource).toContain('if (sequence !== searchSequence) return')
    expect(pageSource).toContain('if (sequence === searchSequence) loading.value = false')
  })

  it('normalizes invalid URL pages and exposes an in-page retry state', () => {
    expect(pageSource).toContain('Number.isSafeInteger(page) && page > 0')
    expect(pageSource).toContain('v-if="loadError"')
    expect(pageSource).toContain('@click="doSearch()"')
    expect(pageSource).not.toContain('v-if="section.page.total > section.page.size"')
  })

  it('keeps the successful analysis range when opening cluster details', () => {
    expect(pageSource).toContain('query: detailQuery')
    expect(pageSource).toContain('startTime: response.startTime')
    expect(pageSource).toContain('endTime: response.endTime')
  })

  it('does not expose the internal metric version as product copy', () => {
    expect(pageSource).toContain('数据更新于')
    expect(pageSource).not.toContain('{{ resultMeta.metricVersion }}')
  })

  it('explains missing source regions instead of showing an ambiguous dash', () => {
    expect(pageSource).toContain('暂无区域数据')
    expect(pageSource).toContain('regionName(item.region')
  })

  it('renders an absent device SN count as zero instead of unknown', () => {
    expect(pageSource).toContain('deviceMetric(row.deviceCount)')
    expect(compact(pageSource)).toContain(compact("return value == null ? '0' : String(value)"))
  })
})
