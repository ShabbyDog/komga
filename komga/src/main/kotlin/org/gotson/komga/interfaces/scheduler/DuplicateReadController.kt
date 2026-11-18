package org.gotson.komga.interfaces.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.service.DuplicateReadLifecycle
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

/**
 * ShabbyFork: runs [DuplicateReadLifecycle]. A book is checked whenever it is updated, which is
 * how its Comic Vine link arrives after a metadata refresh, and every book still unchecked is
 * checked once at startup, which is also what covers books that were in the library before.
 */
@Component
class DuplicateReadController(
  private val duplicateReadLifecycle: DuplicateReadLifecycle,
) {
  @EventListener(ApplicationReadyEvent::class)
  fun checkAllOnStartup() {
    thread(name = "shabbyfork-duplicate-read", isDaemon = true) {
      try {
        val forgotten = duplicateReadLifecycle.deleteOrphans()
        val copied = duplicateReadLifecycle.check()
        logger.info { "Duplicate read check on startup: $copied read(s) copied to other copies of the same issue, $forgotten deleted book(s) forgotten" }
      } catch (e: Exception) {
        logger.error(e) { "Duplicate read check on startup failed" }
      }
    }
  }

  // After commit when published inside a transaction, so the refreshed link is visible.
  @TransactionalEventListener(fallbackExecution = true)
  fun checkUpdatedBook(event: DomainEvent.BookUpdated) {
    try {
      duplicateReadLifecycle.check(listOf(event.book.id))
    } catch (e: Exception) {
      logger.error(e) { "Duplicate read check failed for book ${event.book.id}" }
    }
  }
}
