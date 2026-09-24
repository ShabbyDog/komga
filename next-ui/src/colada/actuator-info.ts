import { defineQuery, useQuery } from '@pinia/colada'
import type { ActuatorInfo } from '@/types/actuator'
import { komgaGetActuatorInfo } from '@/generated/openapi'
import { STALE_TIME } from '@/types/time'

export const useActuatorInfo = defineQuery(() => {
  const { data, ...rest } = useQuery({
    key: () => ['actuator-info'],
    query: () => komgaGetActuatorInfo().then((data) => data as ActuatorInfo),
    staleTime: STALE_TIME.LONG,
    gcTime: false,
  })

  const buildVersion = computed(() => data.value?.build?.version)
  const commitId = computed(() => data.value?.git?.commit?.id)

  // ShabbyFork: the version as shown to the user, e.g. `1.27.1-ShabbyFork-build3`. Kept apart
  // from buildVersion, which is compared against upstream's release tags and must stay bare.
  const displayVersion = computed(() => {
    const version = buildVersion.value
    if (!version) return version

    const forkBuild = data.value?.build?.forkBuild
    if (!forkBuild) return version

    return `${version}-${data.value?.git?.branch}-build${forkBuild}`
  })

  return {
    data,
    ...rest,
    buildVersion,
    commitId,
    displayVersion,
  }
})
