package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.persistence.DuplicateReadRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadContextRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * ShabbyFork: a second copy of an issue you have already read starts out read.
 *
 * A book is checked once, the first time it is seen with a Comic Vine issue link and its media
 * is ready. For each user with no progress on it at all, if another book in the same series
 * (deleted ones included) links to the same Comic Vine issue and that user has completed it, the
 * book is marked completed too, through [BookLifecycle] as any read is. If that read was made
 * in a reading list the book also belongs to, the context comes along.
 *
 * Checking only once is what keeps a deliberate "mark as unread" from being undone, and it only
 * ever marks books read, never unread.
 */
@Component
class DuplicateReadLifecycle(
  private val duplicateReadRepository: DuplicateReadRepository,
  private val bookLifecycle: BookLifecycle,
  private val mediaRepository: MediaRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val readContextRepository: ReadContextRepository,
  private val readListRepository: ReadListRepository,
  private val userRepository: KomgaUserRepository,
) {
  /** Checks the unchecked books among [bookIds], or every unchecked book when null. Returns the number of reads copied. */
  @Synchronized
  fun check(bookIds: Collection<String>? = null): Int {
    val unchecked =
      duplicateReadRepository
        .findUncheckedLinks(bookIds)
        .filter { it.issueId != null }
        .groupBy { it.bookId }
    if (unchecked.isEmpty()) return 0

    val booksByIssue =
      duplicateReadRepository
        .findLinksInSeries(
          unchecked.values
            .flatten()
            .map { it.seriesId }
            .toSet(),
        ).filter { it.issueId != null }
        .groupBy({ it.seriesId to it.issueId!! }, { it.bookId })
    val users by lazy { userRepository.findAll() }

    var copied = 0
    unchecked.forEach { (bookId, links) ->
      if (mediaRepository.findByIdOrNull(bookId)?.status != Media.Status.READY) return@forEach

      val copies =
        links
          .flatMap { booksByIssue[it.seriesId to it.issueId!!].orEmpty() }
          .filter { it != bookId }
          .toSet()
      if (copies.isNotEmpty()) {
        val completedByUser =
          copies
            .flatMap { readProgressRepository.findAllByBookId(it) }
            .filter { it.completed }
            .groupBy { it.userId }
            .mapValues { (_, progresses) -> progresses.maxBy { it.lastModifiedDate } }

        users
          .filter { it.id in completedByUser.keys }
          .filter { readProgressRepository.findByBookIdAndUserIdOrNull(bookId, it.id) == null }
          .forEach { user ->
            val source = completedByUser.getValue(user.id)
            bookLifecycle.markReadProgressCompleted(bookId, user)
            readContextRepository.findCurrentOrNull(user.id, source.bookId)?.let { context ->
              if (readListRepository.findAllContainingBookId(bookId, SearchContext.empty()).any { it.id == context.readListId })
                readContextRepository.markCurrentProgress(user.id, bookId, context.readListId)
            }
            copied++
            logger.info { "Book $bookId is another copy of book ${source.bookId} (same Comic Vine issue), marked read for user ${user.email}" }
          }
      }
      duplicateReadRepository.markChecked(bookId)
    }
    return copied
  }

  fun deleteOrphans(): Int = duplicateReadRepository.deleteOrphans()
}
