<template>
  <div>
    <v-row justify="space-between" align="center">
      <v-col cols="auto">
        <div>
          <span class="text-h4 font-weight-medium me-2">ShabbyFork</span>
          <v-chip class="mx-2 mt-n3" small label color="info">Fork changes</v-chip>
        </div>
        <div class="mt-2 subtitle-1">Included in this build, on top of Komga {{ upstreamVersion }}</div>
      </v-col>
    </v-row>

    <v-row>
      <v-col cols="12">
        <div v-html="rendered"></div>
      </v-col>
    </v-row>

    <v-divider class="my-8"/>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {marked} from 'marked'
import changelog from '@/../../FORK_CHANGELOG.md'

export default Vue.extend({
  name: 'ForkChangelog',
  computed: {
    rendered(): string {
      return marked(changelog) as string
    },
    upstreamVersion(): string {
      return this.$store.state.actuatorInfo?.build?.version
    },
  },
})
</script>
