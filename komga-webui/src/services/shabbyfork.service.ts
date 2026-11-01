import {AxiosInstance} from 'axios'
import {BookDto, ReadProgressUpdateDto} from '@/types/komga-books'
import {ReadListDto} from '@/types/komga-readlists'

const qs = require('qs')

const API_FORK = '/api/v1/fork'

export enum ForkCapability {
  READ_CONTEXT = 'read-context',
  DASHBOARD_ROWS = 'dashboard-rows',
}

/**
 * ShabbyFork: the fork's own endpoints. Stock Komga has none of them, so every caller first
 * asks for the capability it needs; on a stock server the list is simply empty.
 */
export default class ShabbyforkService {
  private http: AxiosInstance
  private capabilities: Promise<string[]> | undefined

  constructor(http: AxiosInstance) {
    this.http = http
  }

  /** The server's fork capabilities, fetched once. Empty on stock Komga or on any error. */
  getCapabilities(): Promise<string[]> {
    if (!this.capabilities) {
      this.capabilities = this.http.get(`${API_FORK}/capabilities`)
        .then(response => (response.data?.capabilities ?? []) as string[])
        .catch(e => {
          // a 404 means stock Komga, which stays true; anything else is retried on the next call
          if (e?.response?.status !== 404) this.capabilities = undefined
          return [] as string[]
        })
    }
    return this.capabilities
  }

  async hasCapability(capability: ForkCapability): Promise<boolean> {
    return (await this.getCapabilities()).includes(capability)
  }

  /**
   * Komga's read-progress update, telling a fork server which reading list the read was made in,
   * or, without one, that it was a series read. On stock Komga it is Komga's own update.
   */
  async updateReadProgress(bookId: string, readProgress: ReadProgressUpdateDto, readListId?: string) {
    const fork = await this.hasCapability(ForkCapability.READ_CONTEXT)
    const url = fork ? `${API_FORK}/books/${bookId}/read-progress` : `/api/v1/books/${bookId}/read-progress`
    const body = fork ? {...readProgress, readListId: readListId} : readProgress
    try {
      await this.http.patch(url, body)
    } catch (e) {
      let msg = `An error occurred while trying to update read progress for book '${bookId}'`
      if (e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }

  async getKeepReading(libraryIds?: string[], pageRequest?: PageRequest): Promise<Page<BookDto>> {
    return this.getPage<BookDto>('books/keep-reading', 'books to keep reading', libraryIds, pageRequest)
  }

  async getOnDeck(libraryIds?: string[], pageRequest?: PageRequest): Promise<Page<BookDto>> {
    return this.getPage<BookDto>('books/on-deck', 'books on deck', libraryIds, pageRequest)
  }

  async getNewOnDeck(libraryIds?: string[], pageRequest?: PageRequest): Promise<Page<BookDto>> {
    return this.getPage<BookDto>('books/new-on-deck', 'new books on deck', libraryIds, pageRequest)
  }

  async getReadListsInProgress(pageRequest?: PageRequest): Promise<Page<ReadListDto>> {
    return this.getPage<ReadListDto>('readlists/in-progress', 'reading lists in progress', undefined, pageRequest)
  }

  private async getPage<T>(path: string, what: string, libraryIds?: string[], pageRequest?: PageRequest): Promise<Page<T>> {
    try {
      const params = {...pageRequest} as any
      if (libraryIds) params.library_id = libraryIds
      return (await this.http.get(`${API_FORK}/${path}`, {
        params: params,
        paramsSerializer: params => qs.stringify(params, {indices: false}),
      })).data
    } catch (e) {
      let msg = `An error occurred while trying to retrieve ${what}`
      if (e.response.data.message) {
        msg += `: ${e.response.data.message}`
      }
      throw new Error(msg)
    }
  }
}
