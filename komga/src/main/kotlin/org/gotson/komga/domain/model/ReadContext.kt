package org.gotson.komga.domain.model

/**
 * ShabbyFork: the reading list a user's current progress on a book was made through.
 *
 * Komga records only page and completion, so the same read looks identical whether it happened
 * in a series or in a reading list. A book without a context, or whose context has gone stale,
 * was read as part of its series.
 */
data class ReadContext(
  val userId: String,
  val bookId: String,
  val readListId: String,
)
