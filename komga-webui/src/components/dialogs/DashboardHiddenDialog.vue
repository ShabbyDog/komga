<template>
  <v-dialog v-model="modal" max-width="500">
    <v-card>
      <v-card-title>{{ $t('dashboard.hidden_items') }}</v-card-title>
      <v-card-text>
        <div v-if="!loading && items.length === 0" class="text-body-2 text--secondary">
          {{ $t('dashboard.hidden_items_empty') }}
        </div>
        <v-list dense v-else>
          <v-list-item v-for="item in items" :key="item.kind + item.targetId">
            <v-list-item-content>
              <v-list-item-title>{{ item.title }}</v-list-item-title>
              <v-list-item-subtitle>
                {{ item.kind === 'SERIES' ? $t('dashboard.hidden_series') : $t('dashboard.hidden_readlist') }}
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action>
              <v-btn text color="primary" @click="restore(item)">{{ $t('common.restore') }}</v-btn>
            </v-list-item-action>
          </v-list-item>
        </v-list>
      </v-card-text>
      <v-card-actions>
        <v-spacer/>
        <v-btn text @click="modal = false">{{ $t('common.close') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import {DashboardDismissalDto} from '@/services/shabbyfork.service'
import {DASHBOARD_DISMISSAL_CHANGED} from '@/types/events'

interface HiddenItem extends DashboardDismissalDto {
  title: string
}

export default Vue.extend({
  name: 'DashboardHiddenDialog',
  props: {
    value: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    items: [] as HiddenItem[],
    loading: false,
  }),
  computed: {
    modal: {
      get(): boolean {
        return this.value
      },
      set(value: boolean) {
        this.$emit('input', value)
      },
    },
  },
  watch: {
    value(val: boolean) {
      if (val) this.load()
    },
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const dismissals = await this.$shabbyfork.getDismissals()
        this.items = await Promise.all(dismissals.map(async (d) => ({...d, title: await this.resolveTitle(d)})))
      } finally {
        this.loading = false
      }
    },
    async resolveTitle(d: DashboardDismissalDto): Promise<string> {
      try {
        if (d.kind === 'SERIES') return (await this.$komgaSeries.getOneSeries(d.targetId)).metadata.title
        return (await this.$komgaReadLists.getOneReadList(d.targetId)).name
      } catch (_) {
        return d.targetId
      }
    },
    async restore(item: HiddenItem) {
      await this.$shabbyfork.restore(item.kind, item.targetId)
      this.items = this.items.filter(i => !(i.kind === item.kind && i.targetId === item.targetId))
      this.$eventHub.$emit(DASHBOARD_DISMISSAL_CHANGED)
    },
  },
})
</script>
