package org.gotson.komga.infrastructure.jooq.shabbyfork

import org.gotson.komga.domain.model.ReadContext
import org.gotson.komga.domain.persistence.ReadContextRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * ShabbyFork: reaches shabbyfork.sqlite through the main connections, where it is attached, so
 * a context can be checked against Komga's READ_PROGRESS in the same query.
 */
@Component
class ReadContextDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ReadContextRepository {
  private val rc = ShabbyforkTables.READ_CONTEXT
  private val rp = Tables.READ_PROGRESS

  // The stamp is copied in SQL rather than read back and written, so it is the stored value
  // exactly and the staleness check can compare the two columns for equality.
  @Transactional
  override fun markCurrentProgress(
    userId: String,
    bookId: String,
    readListId: String,
  ) {
    delete(userId, bookId)
    dslRW
      .insertInto(rc, rc.USER_ID, rc.BOOK_ID, rc.READ_LIST_ID, rc.PROGRESS_MODIFIED)
      .select(
        dslRW
          .select(rp.USER_ID, rp.BOOK_ID, DSL.`val`(readListId), rp.LAST_MODIFIED_DATE)
          .from(rp)
          .where(rp.USER_ID.eq(userId))
          .and(rp.BOOK_ID.eq(bookId)),
      ).execute()
  }

  override fun delete(
    userId: String,
    bookId: String,
  ) {
    dslRW
      .deleteFrom(rc)
      .where(rc.USER_ID.eq(userId))
      .and(rc.BOOK_ID.eq(bookId))
      .execute()
  }

  override fun findCurrentOrNull(
    userId: String,
    bookId: String,
  ): ReadContext? =
    dslRO
      .select(rc.USER_ID, rc.BOOK_ID, rc.READ_LIST_ID)
      .from(rc)
      .join(rp)
      .on(rp.USER_ID.eq(rc.USER_ID))
      .and(rp.BOOK_ID.eq(rc.BOOK_ID))
      .and(rp.LAST_MODIFIED_DATE.eq(rc.PROGRESS_MODIFIED))
      .where(rc.USER_ID.eq(userId))
      .and(rc.BOOK_ID.eq(bookId))
      .fetchOne()
      ?.let { ReadContext(it.value1(), it.value2(), it.value3()) }
}
