<template>
  <v-card class="my-4">
    <template #title>
      <span class="d-inline-flex ga-2 align-center">
        <span class="text-headline-large font-weight-medium">ShabbyFork</span>
        <v-chip
          size="small"
          rounded
          color="info"
        >
          Fork changes
        </v-chip>
      </span>
    </template>

    <template #subtitle> Included in this build, on top of Komga {{ buildVersion }} </template>

    <template #text>
      <!-- eslint-disable vue/no-v-html -->
      <div v-html="marked(changelog)" />
      <!-- eslint-enable vue/no-v-html -->
    </template>
  </v-card>
</template>

<script setup lang="ts">
import { marked } from 'marked'
// Single source of truth for the fork's changelog, at the repository root.
import changelog from '@fork/FORK_CHANGELOG.md?raw'
import { useActuatorInfo } from '@/colada/actuator-info'

const { buildVersion } = useActuatorInfo()
</script>
