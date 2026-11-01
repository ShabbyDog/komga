import { useQuery } from '@pinia/colada'
import { type ForkCapability, forkCapabilitiesQuery } from '@/colada/shabbyfork'

/**
 * ShabbyFork: whether the server offers a fork capability. `known` stays false until the server
 * has answered, or failed, so callers can wait rather than load a stock row and then replace it.
 */
export function useForkCapability(capability: ForkCapability) {
  const { data, status } = useQuery(forkCapabilitiesQuery)
  // an error counts as known, so a failed check falls back to stock rather than showing nothing
  const known = computed(() => status.value !== 'pending')
  const available = computed(() => data.value?.includes(capability) ?? false)
  return { known, available }
}
