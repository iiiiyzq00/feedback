import { beforeEach, describe, expect, it, vi } from 'vitest'

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('./request', () => http)

import { enqueueJiraSync, getJiraSyncStatus } from './import'

describe('Jira import API', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
  })

  it('loads sync status from the guarded Jira endpoint', async () => {
    const status = { enabled: true, ready: true, status: 'IDLE', readinessErrors: [], failureCount: 0 }
    http.get.mockResolvedValue(status)

    await expect(getJiraSyncStatus()).resolves.toBe(status)
    expect(http.get).toHaveBeenCalledWith('/import/jira/sync/status')
  })

  it('queues manual and bounded backfill sync requests', async () => {
    http.post.mockResolvedValue({ accepted: true })

    await enqueueJiraSync()
    expect(http.post).toHaveBeenCalledWith('/import/jira/sync', {})

    const range = { from: '2026-08-01T00:00:00', to: '2026-08-02T00:00:00' }
    await enqueueJiraSync(range)
    expect(http.post).toHaveBeenLastCalledWith('/import/jira/sync', range)
  })
})
