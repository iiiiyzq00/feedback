import { get, post } from './request'

export function getAiReviews(params: { page: number; size: number; reason?: string }) { return get('/ai-reviews', { params }) }
export function getAiTaxonomy() { return get('/ai-reviews/taxonomy') }
export function submitAiReview(sourceId: number, data: { action: string; category?: string; severity?: string; comment: string }) {
  return post(`/ai-reviews/${sourceId}`, data)
}
export function submitAiBatchReview(data: { sourceIds: number[]; action: string; category?: string; severity?: string; comment: string }) {
  return post('/ai-reviews/batch', data)
}
