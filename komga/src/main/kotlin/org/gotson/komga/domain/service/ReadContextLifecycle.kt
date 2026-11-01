package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.persistence.ReadContextRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.springframework.stereotype.Component

/**
 * ShabbyFork: read progress that knows which reading list it was made through.
 *
 * Progress itself is recorded by [BookLifecycle] exactly as Komga does, then the context is
 * set, or cleared when the read was not made in a reading list. The context is written after
 * the progress because it is stamped with it; if writing it fails, the read simply counts as a
 * series read.
 */
@Component
class ReadContextLifecycle(
  private val bookLifecycle: BookLifecycle,
  private val readContextRepository: ReadContextRepository,
  private val readProgressRepository: ReadProgressRepository,
) {
  fun markReadProgress(
    book: Book,
    user: KomgaUser,
    page: Int?,
    completed: Boolean?,
    readListId: String?,
  ) {
    if (completed == true)
      bookLifecycle.markReadProgressCompleted(book.id, user)
    else
      bookLifecycle.markReadProgress(book, user, requireNotNull(page) { "page must be specified if completed is false or null" })

    if (readListId != null)
      readContextRepository.markCurrentProgress(user.id, book.id, readListId)
    else
      readContextRepository.delete(user.id, book.id)
  }

  /**
   * Records a context a client kept from before the server could, if the user's current
   * progress on the book is still the read it describes: the same page and completion. If the
   * progress has changed since, by any client, the read is left a series read. Returns whether
   * the context was recorded.
   */
  fun importContext(
    bookId: String,
    user: KomgaUser,
    readListId: String,
    page: Int,
    completed: Boolean,
  ): Boolean {
    val progress = readProgressRepository.findByBookIdAndUserIdOrNull(bookId, user.id) ?: return false
    if (progress.page != page || progress.completed != completed) return false
    readContextRepository.markCurrentProgress(user.id, bookId, readListId)
    return true
  }
}
