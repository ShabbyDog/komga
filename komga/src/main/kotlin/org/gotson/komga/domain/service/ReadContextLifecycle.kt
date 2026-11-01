package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.persistence.ReadContextRepository
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
}
