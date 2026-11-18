package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.ComicVineLink

/**
 * ShabbyFork: stored in shabbyfork.sqlite. Remembers which books have been checked for a read
 * copy of the same issue, so each book is only ever checked once.
 */
interface DuplicateReadRepository {
  /** Comic Vine links of books that are not deleted and not yet checked, only among [bookIds] when given. */
  fun findUncheckedLinks(bookIds: Collection<String>? = null): Collection<ComicVineLink>

  /** Comic Vine links of every book in [seriesIds], deleted books included. */
  fun findLinksInSeries(seriesIds: Collection<String>): Collection<ComicVineLink>

  fun markChecked(bookId: String)

  /** Forgets books that no longer exist. Returns how many were forgotten. */
  fun deleteOrphans(): Int
}
