import { toValue, type MaybeRefOrGetter } from 'vue'
import { bookListQueryInfinite, booksOnDeckQueryInfinite } from '@/colada/books'
import { valuesToConditions } from '@/functions/filter'
import { ReadStatus } from '@/types/ReadStatus'
import type { SearchConditionBook, SearchConditionSeries } from '@/generated/openapi'
import { seriesListQueryInfinite, seriesUpdatedQueryInfinite } from '@/colada/series'
import type { OverviewSection } from '@/types/OverviewSection'
import type { Sort } from '@/types/PageRequest'
import type { SortBook, SortSeries } from '@/types/sort'
import {
  ForkCapability,
  forkBooksQueryInfinite,
  forkReadListsInProgressQueryInfinite,
} from '@/colada/shabbyfork'
import { useForkCapability } from '@/composables/shabbyfork'
import { OverviewSectionsFork } from '@/types/OverviewSection'

export function useOverviewSection(
  section: MaybeRefOrGetter<OverviewSection>,
  libraryIds: MaybeRefOrGetter<string[] | undefined>,
) {
  // ShabbyFork: on a fork server, Keep Reading and On Deck leave out reading-list reads, and two
  // more rows exist. Rows that depend on it wait for the server's answer, so a stock row is not
  // loaded only to be replaced.
  const { known: forkKnown, available: forkRows } = useForkCapability(ForkCapability.DashboardRows)
  const dependsOnFork = computed(() =>
    ['keep_reading', 'on_deck', ...OverviewSectionsFork].includes(toValue(section)),
  )
  const available = computed(
    () => !OverviewSectionsFork.includes(toValue(section)) || forkRows.value,
  )

  // compute the sort separately, can be used to determine the subtitle of item cards
  const sort = computed<Sort[]>(() => {
    switch (toValue(section)) {
      case 'on_deck':
      case 'new_on_deck':
        return [] satisfies SortBook[]
      case 'readlists_in_progress':
        return []
      case 'keep_reading':
        return [{ key: 'readProgress.readDate', order: 'desc' }] satisfies SortBook[]
      case 'recently_released_books':
        return [{ key: 'metadata.releaseDate', order: 'desc' }] satisfies SortBook[]
      case 'recently_added_books':
        return [{ key: 'createdDate', order: 'desc' }] satisfies SortBook[]
      case 'recently_read_books':
        return [{ key: 'readProgress.readDate', order: 'desc' }] satisfies SortBook[]
      case 'recently_added_series':
        return [{ key: 'createdDate', order: 'desc' }] satisfies SortSeries[]
      case 'recently_updated_series':
        return [{ key: 'lastModifiedDate', order: 'desc' }] satisfies SortSeries[]
    }
  })

  const queryOptions = computed(() => ({
    ...baseQueryOptions.value,
    enabled: available.value && (!dependsOnFork.value || forkKnown.value),
  }))

  const baseQueryOptions = computed(() => {
    const libIds = toValue(libraryIds)

    switch (toValue(section)) {
      case 'keep_reading':
        if (forkRows.value)
          return forkBooksQueryInfinite({ row: 'keep-reading', libraryIds: libIds })
        return bookListQueryInfinite({
          search: {
            condition: {
              allOf: [
                valuesToConditions(libIds, 'libraryId'),
                { readStatus: { operator: 'is', value: ReadStatus.InProgress } },
              ].filter(Boolean) as SearchConditionBook[],
            },
          },
          sort: sort.value,
        })
      case 'on_deck':
        if (forkRows.value) return forkBooksQueryInfinite({ row: 'on-deck', libraryIds: libIds })
        return booksOnDeckQueryInfinite({
          libraryIds: libIds,
        })
      case 'new_on_deck':
        return forkBooksQueryInfinite({ row: 'new-on-deck', libraryIds: libIds })
      case 'readlists_in_progress':
        return forkReadListsInProgressQueryInfinite()
      case 'recently_released_books':
        return bookListQueryInfinite({
          search: {
            condition: {
              allOf: [
                valuesToConditions(libIds, 'libraryId'),
                {
                  releaseDate: {
                    operator: 'after',
                    dateTime: new Date(new Date().setMonth(new Date().getMonth() - 1)),
                  },
                },
              ].filter(Boolean) as SearchConditionBook[],
            },
          },
          sort: sort.value,
        })
      case 'recently_added_books':
        return bookListQueryInfinite({
          search: {
            condition: {
              allOf: [valuesToConditions(libIds, 'libraryId')].filter(
                Boolean,
              ) as SearchConditionBook[],
            },
          },
          sort: sort.value,
        })
      case 'recently_read_books':
        return bookListQueryInfinite({
          search: {
            condition: {
              allOf: [
                valuesToConditions(libIds, 'libraryId'),
                { readStatus: { operator: 'is', value: ReadStatus.Read } },
              ].filter(Boolean) as SearchConditionBook[],
            },
          },
          sort: sort.value,
        })
      case 'recently_added_series':
        return seriesListQueryInfinite({
          search: {
            condition: {
              allOf: [
                valuesToConditions(libIds, 'libraryId'),
                { oneshot: { operator: 'isFalse' } },
              ].filter(Boolean) as SearchConditionSeries[],
            },
          },
          sort: sort.value,
        })
      case 'recently_updated_series':
        return seriesUpdatedQueryInfinite({
          libraryIds: libIds,
        })
    }
  })

  const kind = computed(() => {
    switch (toValue(section)) {
      case 'keep_reading':
      case 'on_deck':
      case 'recently_released_books':
      case 'recently_added_books':
      case 'recently_read_books':
      case 'new_on_deck':
        return 'book'
      case 'readlists_in_progress':
        return 'readlist'
      case 'recently_added_series':
      case 'recently_updated_series':
        return 'series'
    }
  })

  return { queryOptions, sort, kind, available }
}
