package org.gotson.komga.infrastructure.jooq.shabbyfork

import org.gotson.komga.interfaces.api.persistence.ShabbyforkDashboardRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

private const val CHUNK = 500

@Component
class ShabbyforkDashboardDao(
  @Qualifier("dslContextRO") private val dslRO: DSLContext,
) : ShabbyforkDashboardRepository {
  private val rc = ShabbyforkTables.READ_CONTEXT
  private val rp = Tables.READ_PROGRESS
  private val b = Tables.BOOK
  private val rlb = Tables.READLIST_BOOK

  /** The context row exists and still belongs to the progress row it is joined to. */
  private fun currentContextOf(
    progressUserId: Field<String>,
    progressBookId: Field<String>,
    progressModified: Field<LocalDateTime>,
  ): Condition =
    rc.USER_ID
      .eq(progressUserId)
      .and(rc.BOOK_ID.eq(progressBookId))
      .and(rc.PROGRESS_MODIFIED.eq(progressModified))

  override fun findBookIdsReadInReadList(
    userId: String,
    bookIds: Collection<String>,
  ): Set<String> =
    bookIds.chunked(CHUNK).flatMapTo(mutableSetOf()) { chunk ->
      dslRO
        .select(rp.BOOK_ID)
        .from(rp)
        .join(rc)
        .on(currentContextOf(rp.USER_ID, rp.BOOK_ID, rp.LAST_MODIFIED_DATE))
        .where(rp.USER_ID.eq(userId))
        .and(rp.BOOK_ID.`in`(chunk))
        .fetch(rp.BOOK_ID)
    }

  override fun findSeriesLastCompletedInReadList(
    userId: String,
    seriesIds: Collection<String>,
  ): Set<String> {
    val rp2 = rp.`as`("rp2")
    val b2 = b.`as`("b2")
    return seriesIds.chunked(CHUNK).flatMapTo(mutableSetOf()) { chunk ->
      val latestCompletion =
        DSL
          .select(DSL.max(rp2.READ_DATE))
          .from(rp2)
          .join(b2)
          .on(b2.ID.eq(rp2.BOOK_ID))
          .where(rp2.USER_ID.eq(userId))
          .and(rp2.COMPLETED.isTrue)
          .and(b2.SERIES_ID.eq(b.SERIES_ID))
      dslRO
        .selectDistinct(b.SERIES_ID)
        .from(rp)
        .join(b)
        .on(b.ID.eq(rp.BOOK_ID))
        .join(rc)
        .on(currentContextOf(rp.USER_ID, rp.BOOK_ID, rp.LAST_MODIFIED_DATE))
        .where(rp.USER_ID.eq(userId))
        .and(rp.COMPLETED.isTrue)
        .and(b.SERIES_ID.`in`(chunk))
        .and(rp.READ_DATE.eq(DSL.field(latestCompletion)))
        .fetch(b.SERIES_ID)
    }
  }

  override fun findSeriesCaughtUpBeforeNewBooks(
    userId: String,
    seriesIds: Collection<String>,
  ): Set<String> {
    val oldestNotCompleted = DSL.min(DSL.`when`(rp.COMPLETED.isNull.or(rp.COMPLETED.isFalse), b.CREATED_DATE))
    val latestCompletion = DSL.max(DSL.`when`(rp.COMPLETED.isTrue, rp.READ_DATE))
    return seriesIds.chunked(CHUNK).flatMapTo(mutableSetOf()) { chunk ->
      dslRO
        .select(b.SERIES_ID, oldestNotCompleted, latestCompletion)
        .from(b)
        .leftJoin(rp)
        .on(rp.BOOK_ID.eq(b.ID))
        .and(rp.USER_ID.eq(userId))
        .where(b.SERIES_ID.`in`(chunk))
        .and(b.DELETED_DATE.isNull)
        .groupBy(b.SERIES_ID)
        .fetch()
        // conservatively not a new drop when either date is unknown
        .filter { (_, oldest, latest) -> oldest != null && latest != null && oldest.isAfter(latest) }
        .map { it.value1() }
    }
  }

  override fun findReadListIdsInProgress(userId: String): List<String> {
    val startedThroughList =
      DSL.exists(
        DSL
          .selectOne()
          .from(rc)
          .join(rp)
          .on(rp.USER_ID.eq(rc.USER_ID))
          .and(rp.BOOK_ID.eq(rc.BOOK_ID))
          .and(rp.LAST_MODIFIED_DATE.eq(rc.PROGRESS_MODIFIED))
          .where(rc.USER_ID.eq(userId))
          .and(rc.READ_LIST_ID.eq(rlb.READLIST_ID)),
      )
    val lastRead = DSL.max(rp.READ_DATE)
    val completed = DSL.sum(DSL.`when`(rp.COMPLETED.isTrue, 1).otherwise(0))
    return dslRO
      .select(rlb.READLIST_ID, lastRead)
      .from(rlb)
      .leftJoin(rp)
      .on(rp.BOOK_ID.eq(rlb.BOOK_ID))
      .and(rp.USER_ID.eq(userId))
      .where(startedThroughList)
      .groupBy(rlb.READLIST_ID)
      .having(completed.lt(DSL.count(rlb.BOOK_ID).cast(BigDecimal::class.java)))
      .orderBy(lastRead.desc())
      .fetch(rlb.READLIST_ID)
  }
}
