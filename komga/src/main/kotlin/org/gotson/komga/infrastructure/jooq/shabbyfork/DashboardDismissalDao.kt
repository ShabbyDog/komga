package org.gotson.komga.infrastructure.jooq.shabbyfork

import org.gotson.komga.domain.model.DashboardDismissalKind
import org.gotson.komga.domain.persistence.DashboardDismissalRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * ShabbyFork: reaches shabbyfork.sqlite through the main connections, where it is attached, so a
 * dismissal's staleness can be checked against Komga's READ_PROGRESS in the same query.
 */
@Component
class DashboardDismissalDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  DashboardDismissalRepository {
  private val dd = ShabbyforkTables.DASHBOARD_DISMISSAL
  private val rp = Tables.READ_PROGRESS
  private val b = Tables.BOOK
  private val rlb = Tables.READLIST_BOOK

  @Transactional
  override fun dismiss(
    userId: String,
    kind: DashboardDismissalKind,
    targetId: String,
  ) {
    restore(userId, kind, targetId)
    dslRW
      .insertInto(dd, dd.USER_ID, dd.KIND, dd.TARGET_ID, dd.DISMISSED_AT)
      .values(userId, kind.name, targetId, LocalDateTime.now(ZoneOffset.UTC))
      .execute()
  }

  override fun restore(
    userId: String,
    kind: DashboardDismissalKind,
    targetId: String,
  ) {
    dslRW
      .deleteFrom(dd)
      .where(dd.USER_ID.eq(userId))
      .and(dd.KIND.eq(kind.name))
      .and(dd.TARGET_ID.eq(targetId))
      .execute()
  }

  override fun findActiveSeriesDismissals(userId: String): Set<String> {
    // Active = not yet un-hidden: no read on any book in the series strictly after the dismissal
    // (a later read by any client clears it).
    val notReopened =
      DSL.notExists(
        DSL
          .selectOne()
          .from(rp)
          .join(b)
          .on(b.ID.eq(rp.BOOK_ID))
          .where(rp.USER_ID.eq(dd.USER_ID))
          .and(b.SERIES_ID.eq(dd.TARGET_ID))
          .and(rp.READ_DATE.gt(dd.DISMISSED_AT)),
      )
    return dslRO
      .select(dd.TARGET_ID)
      .from(dd)
      .where(dd.USER_ID.eq(userId))
      .and(dd.KIND.eq(DashboardDismissalKind.SERIES.name))
      .and(notReopened)
      .fetchSet(dd.TARGET_ID)
  }

  override fun findActiveReadListDismissals(userId: String): Set<String> {
    val notReopened =
      DSL.notExists(
        DSL
          .selectOne()
          .from(rp)
          .join(rlb)
          .on(rlb.BOOK_ID.eq(rp.BOOK_ID))
          .where(rp.USER_ID.eq(dd.USER_ID))
          .and(rlb.READLIST_ID.eq(dd.TARGET_ID))
          .and(rp.READ_DATE.gt(dd.DISMISSED_AT)),
      )
    return dslRO
      .select(dd.TARGET_ID)
      .from(dd)
      .where(dd.USER_ID.eq(userId))
      .and(dd.KIND.eq(DashboardDismissalKind.READLIST.name))
      .and(notReopened)
      .fetchSet(dd.TARGET_ID)
  }
}
