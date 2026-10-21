import { QueryClient } from '@tanstack/vue-query'

export const queryKeys = {
  imports: {
    all: ['imports'] as const,
    batches: (page: number, size: number, status = '', includeGoverned = false) => ['imports', 'batches', { page, size, status, includeGoverned }] as const,
    detail: (batchId: string) => ['imports', 'detail', batchId] as const,
  },
  clusters: {
    all: ['cluster-reviews'] as const,
    reviews: (priority: string, page: number, size: number) => ['cluster-reviews', { priority, page, size }] as const,
  },
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      retry: (failureCount, error: any) => failureCount < 2 && error?.response?.status !== 401 && error?.response?.status !== 403,
      refetchOnWindowFocus: false,
      refetchOnReconnect: true,
    },
    mutations: { retry: false },
  },
})
