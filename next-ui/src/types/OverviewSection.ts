import { defineMessages, type MessageDescriptor } from 'vue-intl'
import type { ClientSettingUserOverviewSection } from '@/types/ClientSettingsUser'

export const OverviewSectionValues = [
  'keep_reading',
  'on_deck',
  'recently_released_books',
  'recently_added_books',
  'recently_added_series',
  'recently_updated_series',
  'recently_read_books',
  // ShabbyFork: only shown when the server offers the fork's dashboard rows
  'new_on_deck',
  'readlists_in_progress',
] as const

// ShabbyFork: sections that exist only on a fork server
export const OverviewSectionsFork: readonly OverviewSection[] = [
  'new_on_deck',
  'readlists_in_progress',
]

// the order a new layout starts with: the fork's sections follow the rows they belong with
const defaultOrder: readonly OverviewSection[] = [
  'keep_reading',
  'on_deck',
  'new_on_deck',
  'readlists_in_progress',
  'recently_released_books',
  'recently_added_books',
  'recently_added_series',
  'recently_updated_series',
  'recently_read_books',
]

export const OverviewSectionsDefault: ClientSettingUserOverviewSection[] = defaultOrder.map(
  (section) => ({
    section: section,
  }),
)

export type OverviewSection = (typeof OverviewSectionValues)[number]

export const overviewSectionMessages: Record<OverviewSection, MessageDescriptor> = defineMessages({
  keep_reading: {
    description: 'Overview section: keep reading',
    defaultMessage: 'Keep Reading',
    id: 'enum.OverviewSection.keep_reading',
  },
  on_deck: {
    description: 'Overview section: on deck',
    defaultMessage: 'On Deck',
    id: 'enum.OverviewSection.on_deck',
  },
  recently_released_books: {
    description: 'Overview section: recently released books',
    defaultMessage: 'Recently Released Books',
    id: 'enum.OverviewSection.recently_released_books',
  },
  recently_added_books: {
    description: 'Overview section: recently added books',
    defaultMessage: 'Recently Added Books',
    id: 'enum.OverviewSection.recently_added_books',
  },
  recently_added_series: {
    description: 'Overview section: recently added series',
    defaultMessage: 'Recently Added Series',
    id: 'enum.OverviewSection.recently_added_series',
  },
  recently_updated_series: {
    description: 'Overview section: recently updated series',
    defaultMessage: 'Recently Updated Series',
    id: 'enum.OverviewSection.recently_updated_series',
  },
  recently_read_books: {
    description: 'Overview section: recently read books',
    defaultMessage: 'Recently Read Books',
    id: 'enum.OverviewSection.recently_read_books',
  },
  new_on_deck: {
    description:
      'Overview section: new books on deck, in series that were caught up before they arrived',
    defaultMessage: 'New Books On Deck',
    id: 'enum.OverviewSection.new_on_deck',
  },
  readlists_in_progress: {
    description: 'Overview section: reading lists in progress',
    defaultMessage: 'Reading Lists In Progress',
    id: 'enum.OverviewSection.readlists_in_progress',
  },
})
