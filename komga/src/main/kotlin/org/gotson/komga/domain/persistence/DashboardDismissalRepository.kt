package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DashboardDismissalKind

/**
 * ShabbyFork: stored in shabbyfork.sqlite. A dismissal hides a Longbox dashboard item (a series
 * or a reading list) for one user, and counts only until a book in the item is read again after
 * the dismissal — so opening the item from anywhere clears it, with no separate write. The
 * resurrection is evaluated against Komga's READ_PROGRESS, so a read made by any client (stock
 * Komga, another device, another app) un-hides it just the same.
 */
interface DashboardDismissalRepository {
  /** Records [targetId] of [kind] as dismissed for the user as of now, replacing any previous dismissal. */
  fun dismiss(
    userId: String,
    kind: DashboardDismissalKind,
    targetId: String,
  )

  /** Removes the dismissal, if any, so the item shows normally again. */
  fun restore(
    userId: String,
    kind: DashboardDismissalKind,
    targetId: String,
  )

  /**
   * Series ids the user has dismissed and not opened since — i.e. no book in the series has a
   * read-progress read date strictly after the dismissal. These are hidden from the series rows.
   */
  fun findActiveSeriesDismissals(userId: String): Set<String>

  /**
   * Read list ids the user has dismissed and not opened since — i.e. no book in the list has a
   * read-progress read date strictly after the dismissal. These are hidden from in-progress lists.
   */
  fun findActiveReadListDismissals(userId: String): Set<String>
}
