<template>
  <div>
    <v-menu offset-y v-model="menuState">
      <template v-slot:activator="{ on }">
        <v-btn icon v-on="on" @click.prevent="">
          <v-icon>mdi-dots-vertical</v-icon>
        </v-btn>
      </template>
      <v-list dense>
        <v-list-item @click="analyze" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.analyze') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="refreshMetadata" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.refresh_metadata') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="addToReadList" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.add_to_readlist') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="markRead" v-if="!isRead">
          <v-list-item-title>{{ $t('menu.mark_read') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="markUnread" v-if="!isUnread">
          <v-list-item-title>{{ $t('menu.mark_unread') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="hideFromDashboard" v-if="dashboardHide">
          <v-list-item-title>{{ $t('menu.hide_from_dashboard') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="promptDeleteBook" class="list-danger" v-if="isAdmin">
          <v-list-item-title>{{ $t('menu.delete') }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>
  </div>
</template>
<script lang="ts">
import {getReadProgress} from '@/functions/book-progress'
import {ReadStatus} from '@/types/enum-books'
import Vue from 'vue'
import {BookDto, ReadProgressUpdateDto} from '@/types/komga-books'
import {DASHBOARD_DISMISSAL_CHANGED} from '@/types/events'

export default Vue.extend({
  name: 'BookActionsMenu',
  data: () => {
    return {
      menuState: false,
    }
  },
  props: {
    book: {
      type: Object as () => BookDto,
      required: true,
    },
    menu: {
      type: Boolean,
      default: false,
    },
    // ShabbyFork: when true, the menu offers hiding this book's series from the dashboard.
    dashboardHide: {
      type: Boolean,
      default: false,
    },
  },
  watch: {
    menuState (val) {
      this.$emit('update:menu', val)
    },
  },
  computed: {
    isAdmin (): boolean {
      return this.$store.getters.meAdmin
    },
    isRead (): boolean {
      return getReadProgress(this.book) === ReadStatus.READ
    },
    isUnread (): boolean {
      return getReadProgress(this.book) === ReadStatus.UNREAD
    },
  },
  methods: {
    analyze () {
      this.$komgaBooks.analyzeBook(this.book)
    },
    refreshMetadata () {
      this.$komgaBooks.refreshMetadata(this.book)
    },
    addToReadList () {
      this.$store.dispatch('dialogAddBooksToReadList', [this.book.id])
    },
    async markRead () {
      const readProgress = { completed: true } as ReadProgressUpdateDto
      await this.$komgaBooks.updateReadProgress(this.book.id, readProgress)
    },
    async markUnread () {
      await this.$komgaBooks.deleteReadProgress(this.book.id)
    },
    promptDeleteBook () {
      this.$store.dispatch('dialogDeleteBook', this.book)
    },
    async hideFromDashboard () {
      // Hiding a book from an activity row hides its whole series (dashboard-rows-spec.md).
      await this.$shabbyfork.dismiss('SERIES', this.book.seriesId)
      this.$eventHub.$emit(DASHBOARD_DISMISSAL_CHANGED)
    },
  },
})
</script>
