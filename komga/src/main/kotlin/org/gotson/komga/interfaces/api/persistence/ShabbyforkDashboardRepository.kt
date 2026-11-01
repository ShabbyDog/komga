package org.gotson.komga.interfaces.api.persistence

/**
 * ShabbyFork: the facts the dashboard rows are split and filtered on, all for one user and all
 * using only reading-list contexts that are still current (see ReadContextRepository).
 */
interface ShabbyforkDashboardRepository {
  /** Of [bookIds], those whose current progress was made through a reading list. */
  fun findBookIdsReadInReadList(
    userId: String,
    bookIds: Collection<String>,
  ): Set<String>

  /** Of [seriesIds], those whose most recently completed book was read through a reading list. */
  fun findSeriesLastCompletedInReadList(
    userId: String,
    seriesIds: Collection<String>,
  ): Set<String>

  /**
   * Of [seriesIds], those the user had caught up on before new books arrived: every book not yet
   * completed was added after the user's most recent completion in the series.
   */
  fun findSeriesCaughtUpBeforeNewBooks(
    userId: String,
    seriesIds: Collection<String>,
  ): Set<String>

  /**
   * Reading lists the user has read at least one book through and has not completed, most
   * recently read first.
   */
  fun findReadListIdsInProgress(userId: String): List<String>
}
