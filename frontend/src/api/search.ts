import { get } from './request'
import type { FeedbackSourceChannel } from './feedback'

/** 服务端提供的安全自动补全项；path 是已治理的站内详情路径。 */
export interface SearchSuggestionItem {
  value: string
  title: string
  description?: string | null
  typeLabel: string
  typeColor: 'primary'
  path: string
  id: number
}

/** 首批最多 100 条安全候选中的渠道计数，不是全量分渠道统计。 */
export interface SearchSuggestionChannelCount {
  channel: FeedbackSourceChannel
  count: number
}

/** total 是完整列表匹配数，可能大于 channelCounts 的合计。 */
export interface SearchSuggestionResponse {
  items: SearchSuggestionItem[]
  channelCounts: SearchSuggestionChannelCount[]
  total: number
  query: string
}

export function getSearchSuggestions(q: string, limit = 10, signal?: AbortSignal): Promise<SearchSuggestionResponse> {
  return get('/search/suggest', { params: { q, limit }, signal })
}
