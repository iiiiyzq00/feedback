import { beforeEach, describe, expect, it, vi } from 'vitest'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('./request', () => ({ get }))

import { getShadowClusterDetail, getShadowPreview } from './clusterShadowPreview'

describe('cluster shadow preview api', () => {
  beforeEach(() => get.mockReset())

  it('uses only the admin read endpoints', () => {
    get.mockResolvedValue({})
    getShadowPreview({ page: 1, size: 20, largeOnly: true })
    getShadowClusterDetail('SC-0001', 2, 50)

    expect(get).toHaveBeenNthCalledWith(1, '/admin/cluster-shadow-preview', {
      params: { page: 1, size: 20, largeOnly: true },
    })
    expect(get).toHaveBeenNthCalledWith(2, '/admin/cluster-shadow-preview/clusters/SC-0001', {
      params: { page: 2, size: 50 },
    })
  })
})
