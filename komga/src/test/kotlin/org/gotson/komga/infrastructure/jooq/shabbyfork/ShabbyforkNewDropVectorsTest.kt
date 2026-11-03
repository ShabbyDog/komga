package org.gotson.komga.infrastructure.jooq.shabbyfork

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.KomgaUserLifecycle
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.interfaces.api.persistence.ShabbyforkDashboardRepository
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * Drives [ShabbyforkDashboardRepository.findSeriesCaughtUpBeforeNewBooks] with the cross-repo
 * scenario vectors shared with the Longbox app (`shabbyfork/dashboard-rows-vectors.json`, a copy
 * of the canonical file in the komga-reader repo). The app's `NewDropVectorsTest` asserts the same
 * vectors against its own derivation; keeping both green is what stops the stock and fork "New
 * books on deck" logic from drifting. Canonical semantics: komga-reader/dashboard-rows-spec.md.
 *
 * The `version` assertion is the tripwire: a one-sided change to a row definition bumps the vectors
 * version and fails whichever repo was not updated.
 */
@SpringBootTest
class ShabbyforkNewDropVectorsTest(
  @Autowired private val dashboardRepository: ShabbyforkDashboardRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val readProgressRepository: ReadProgressRepository,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val userLifecycle: KomgaUserLifecycle,
  @Autowired private val dsl: DSLContext,
) {
  private val library = makeLibrary(id = "1")
  private val user = KomgaUser("user@example.org", "", id = "1")

  private val mapper = ObjectMapper()

  /** The version this suite was written against; the app's suite pins its own copy of this. */
  private val expectedVersion = "1.0.0"

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    userRepository.insert(user)
  }

  @AfterAll
  fun teardown() {
    userRepository.findAll().forEach { userLifecycle.deleteUser(it) }
    libraryRepository.findAll().forEach { libraryLifecycle.deleteLibrary(it) }
  }

  @AfterEach
  fun clearSeries() {
    seriesLifecycle.deleteMany(seriesRepository.findAll())
  }

  private fun vectorsRoot(): JsonNode =
    mapper.readTree(
      requireNotNull(javaClass.getResourceAsStream("/shabbyfork/dashboard-rows-vectors.json")) {
        "dashboard-rows-vectors.json missing from test resources"
      },
    )

  @Test
  fun `vectors file is the version this suite expects`() {
    assertThat(vectorsRoot().get("version").asText()).isEqualTo(expectedVersion)
  }

  @Test
  fun `findSeriesCaughtUpBeforeNewBooks matches every scenario vector`() {
    val vectors = vectorsRoot().get("vectors")
    assertThat(vectors.toList()).isNotEmpty
    vectors.forEach { vector ->
      val name = vector.get("name").asText()
      val series = buildSeries(name, vector.get("books"))
      val isNewDrop = series.id in dashboardRepository.findSeriesCaughtUpBeforeNewBooks(user.id, listOf(series.id))
      assertThat(isNewDrop)
        .withFailMessage("vector '%s': expected newDrop=%s but was %s", name, vector.get("expectedNewDrop").asBoolean(), isNewDrop)
        .isEqualTo(vector.get("expectedNewDrop").asBoolean())
    }
  }

  /** Create a series whose books carry each vector book's add date and completion state. */
  private fun buildSeries(
    name: String,
    books: JsonNode,
  ): Series {
    val specs = books.toList()
    val series = seriesLifecycle.createSeries(makeSeries(name = name, libraryId = library.id))
    seriesLifecycle.addBooks(
      series,
      specs.mapIndexed { i, _ -> makeBook("$name-$i", seriesId = series.id, libraryId = library.id).copy(number = i + 1) },
    )
    val created = bookRepository.findAllBySeriesId(series.id).sortedBy { it.number }
    specs.zip(created).forEach { (spec, book) ->
      setAdded(book, localDateTime(spec.get("createdDate").asText()))
      val completed = spec.path("completed").asBoolean(false)
      val readIso = spec.get("readDate")?.takeUnless { it.isNull }?.asText()
      if (readIso != null) {
        // A read date means completed (counts toward latest completion) or in progress (a
        // not-completed book, same as unread for this decision). A book with no read date is unread
        // and gets no read-progress row.
        val readAt = localDateTime(readIso)
        readProgressRepository.save(ReadProgress(book.id, user.id, if (completed) 10 else 3, completed, readDate = readAt))
        setReadDate(book, readAt)
      }
    }
    return series
  }

  private fun setAdded(
    book: Book,
    at: LocalDateTime,
  ) {
    dsl
      .update(Tables.BOOK)
      .set(Tables.BOOK.CREATED_DATE, at)
      .where(Tables.BOOK.ID.eq(book.id))
      .execute()
  }

  /**
   * Overwrite READ_DATE with the raw UTC wall-clock from the vector, so it is stored in the same
   * convention as CREATED_DATE above. (ReadProgressDao.save re-applies a system-local to UTC shift
   * to the read date, which on a non-UTC machine would skew it against CREATED_DATE and break the
   * exact-tie case. In production both columns are UTC, so the comparison is sound.)
   */
  private fun setReadDate(
    book: Book,
    at: LocalDateTime,
  ) {
    dsl
      .update(Tables.READ_PROGRESS)
      .set(Tables.READ_PROGRESS.READ_DATE, at)
      .where(
        Tables.READ_PROGRESS.BOOK_ID
          .eq(book.id)
          .and(Tables.READ_PROGRESS.USER_ID.eq(user.id)),
      ).execute()
  }

  private fun localDateTime(iso: String): LocalDateTime = OffsetDateTime.parse(iso).toLocalDateTime()
}
