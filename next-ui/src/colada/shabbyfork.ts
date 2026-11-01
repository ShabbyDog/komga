import { defineInfiniteQueryOptions, defineQueryOptions } from '@pinia/colada'
import { client } from '@/generated/openapi/client.gen'
import type { PageBookDto, PageReadListDto } from '@/generated/openapi'
import { isApiErrorWithCause } from '@/api/komga-client'
import { QUERY_KEYS_BOOKS } from '@/colada/books'
import { QUERY_KEYS_READLIST } from '@/colada/readlists'
import { PageRequest } from '@/types/PageRequest'
import { STALE_TIME } from '@/types/time'

/*
 * ShabbyFork: the fork's own endpoints. They are not in the generated client, so that this fork
 * never has to carry a regenerated openapi.json; stock Komga has none of them, which callers
 * find out from the capabilities.
 */

export const ForkCapability = {
  ReadContext: 'read-context',
  DashboardRows: 'dashboard-rows',
} as const

export type ForkCapability = (typeof ForkCapability)[keyof typeof ForkCapability]

export type ForkBooksRow = 'keep-reading' | 'on-deck' | 'new-on-deck'

const security = [
  { scheme: 'basic', type: 'http' },
  { name: 'X-API-Key', type: 'apiKey' },
] as const

const API_FORK = '/api/v1/fork'

async function forkGet<T>(path: string, query?: Record<string, unknown>): Promise<T> {
  return (await client.get({
    responseStyle: 'data',
    security: [...security],
    url: `${API_FORK}/${path}`,
    query: query,
  })) as T
}

export const forkCapabilitiesQuery = defineQueryOptions(() => ({
  key: ['shabbyfork', 'capabilities'],
  query: async (): Promise<string[]> => {
    try {
      return (await forkGet<{ capabilities?: string[] }>('capabilities')).capabilities ?? []
    } catch (error) {
      // stock Komga has no such endpoint
      if (isApiErrorWithCause(error) && error.cause.status === 404) return []
      throw error
    }
  },
  staleTime: STALE_TIME.LONG,
}))

// kept under the books root, so they are refreshed whenever Komga's own book rows are
export const forkBooksQueryInfinite = defineInfiniteQueryOptions(
  ({ row, libraryIds }: { row: ForkBooksRow; libraryIds?: string[] }) => ({
    key: [...QUERY_KEYS_BOOKS.root, 'shabbyfork', row, JSON.stringify({ libraryIds: libraryIds })],
    initialPageParam: new PageRequest(0, 50),
    query: ({ pageParam }) =>
      forkGet<PageBookDto>(`books/${row}`, {
        library_id: libraryIds,
        page: pageParam.page,
        size: pageParam.size,
      }),
    getNextPageParam: (lastPage, _, lastPageParam) =>
      !lastPage?.last ? lastPageParam.next() : null,
  }),
)

export const forkReadListsInProgressQueryInfinite = defineInfiniteQueryOptions(() => ({
  key: [...QUERY_KEYS_READLIST.root, 'shabbyfork', 'in-progress'],
  initialPageParam: new PageRequest(0, 50),
  query: ({ pageParam }) =>
    forkGet<PageReadListDto>('readlists/in-progress', {
      page: pageParam.page,
      size: pageParam.size,
    }),
  getNextPageParam: (lastPage, _, lastPageParam) => (!lastPage?.last ? lastPageParam.next() : null),
}))
