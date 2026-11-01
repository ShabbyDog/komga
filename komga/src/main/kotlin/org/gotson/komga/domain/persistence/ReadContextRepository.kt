package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.ReadContext

/**
 * ShabbyFork: stored in shabbyfork.sqlite. A context is stamped with the read progress it
 * describes, and only counts while that progress is unchanged, so progress changed by anything
 * that does not know about contexts (stock Komga, Kobo or KOReader sync, other clients) makes
 * it stale without needing to be told.
 */
interface ReadContextRepository {
  /**
   * Records [readListId] as the context of the user's current progress on [bookId], replacing
   * any previous one. Does nothing if the user has no progress on the book.
   */
  fun markCurrentProgress(
    userId: String,
    bookId: String,
    readListId: String,
  )

  fun delete(
    userId: String,
    bookId: String,
  )

  /** The context of the user's current progress on [bookId], or null when there is none or it is stale. */
  fun findCurrentOrNull(
    userId: String,
    bookId: String,
  ): ReadContext?
}
