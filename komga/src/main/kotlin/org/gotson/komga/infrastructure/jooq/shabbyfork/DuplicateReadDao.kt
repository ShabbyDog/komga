package org.gotson.komga.infrastructure.jooq.shabbyfork

import org.gotson.komga.domain.model.ComicVineLink
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.persistence.DuplicateReadRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

private const val CHUNK = 500

/**
 * ShabbyFork: reaches shabbyfork.sqlite through the main connections, where it is attached, so
 * the checked books can be joined to Komga's links.
 */
@Component
class DuplicateReadDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  DuplicateReadRepository {
  private val drc = ShabbyforkTables.DUPLICATE_READ_CHECK
  private val l = Tables.BOOK_METADATA_LINK
  private val b = Tables.BOOK
  private val m = Tables.MEDIA

  private fun links(condition: Condition): Collection<ComicVineLink> =
    dslRO
      .select(l.BOOK_ID, b.SERIES_ID, l.URL)
      .from(l)
      .join(b)
      .on(b.ID.eq(l.BOOK_ID))
      .where(DSL.lower(l.URL).contains("comicvine"))
      .and(condition)
      .fetch { ComicVineLink(it.value1(), it.value2(), it.value3()) }

  override fun findUncheckedLinks(bookIds: Collection<String>?): Collection<ComicVineLink> {
    val unchecked =
      b.DELETED_DATE.isNull
        .and(
          DSL.exists(
            DSL
              .selectOne()
              .from(m)
              .where(m.BOOK_ID.eq(l.BOOK_ID))
              .and(m.STATUS.eq(Media.Status.READY.name)),
          ),
        ).and(DSL.notExists(DSL.selectOne().from(drc).where(drc.BOOK_ID.eq(l.BOOK_ID))))
    return if (bookIds == null)
      links(unchecked)
    else
      bookIds.chunked(CHUNK).flatMap { links(unchecked.and(l.BOOK_ID.`in`(it))) }
  }

  override fun findLinksInSeries(seriesIds: Collection<String>): Collection<ComicVineLink> = seriesIds.chunked(CHUNK).flatMap { links(b.SERIES_ID.`in`(it)) }

  @Transactional
  override fun markChecked(bookIds: Collection<String>) {
    val now = LocalDateTime.now()
    bookIds.chunked(CHUNK).forEach { chunk ->
      dslRW
        .batch(
          dslRW
            .insertInto(drc, drc.BOOK_ID, drc.CHECKED_DATE)
            .values(null as String?, null)
            .onConflictDoNothing(),
        ).also { step -> chunk.forEach { step.bind(it, now) } }
        .execute()
    }
  }

  override fun deleteOrphans(): Int =
    dslRW
      .deleteFrom(drc)
      .where(drc.BOOK_ID.notIn(DSL.select(b.ID).from(b)))
      .execute()
}
