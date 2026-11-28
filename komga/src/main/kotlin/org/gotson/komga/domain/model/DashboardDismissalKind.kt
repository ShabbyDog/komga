package org.gotson.komga.domain.model

/**
 * ShabbyFork: a Longbox dashboard item a user has manually removed from an activity row.
 *
 * A [SERIES] dismissal hides the series from Keep reading, On Deck and New-on-deck; a [READLIST]
 * dismissal hides the list from In-progress reading lists. The dismissal is stamped with the
 * instant it was made and stays active only until a book in the target is opened (read) again
 * after that instant — see DashboardDismissalRepository. Canonical semantics live in the
 * komga-reader repo's dashboard-rows-spec.md ("Row dismissal").
 */
enum class DashboardDismissalKind {
  SERIES,
  READLIST,
}
