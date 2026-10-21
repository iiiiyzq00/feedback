import { describe, expect, it } from 'vitest'
import { queryKeys } from './client'

describe('queryKeys', () => {
  it('分页与筛选参数会生成独立缓存键', () => {
    expect(queryKeys.imports.batches(1, 20)).not.toEqual(queryKeys.imports.batches(2, 20))
    expect(queryKeys.clusters.reviews('P1', 1, 20)).not.toEqual(queryKeys.clusters.reviews('P2', 1, 20))
  })

  it('模块根键可用于统一失效', () => {
    expect(queryKeys.imports.batches(1, 20).slice(0, 1)).toEqual(queryKeys.imports.all)
    expect(queryKeys.clusters.reviews('', 1, 20).slice(0, 1)).toEqual(queryKeys.clusters.all)
  })
})
