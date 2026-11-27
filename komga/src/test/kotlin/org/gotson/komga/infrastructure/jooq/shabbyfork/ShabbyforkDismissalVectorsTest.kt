package org.gotson.komga.infrastructure.jooq.shabbyfork

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DashboardDismissalRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.KomgaUserLifecycle
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.ReadListLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import org.gotson.komga.jooq.main.Tables as MainTables
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * Drives [DashboardDismissalRepository.findActiveSeriesDismissals] /
 * [DashboardDismissalRepository.findActiveReadListDismissals] with the cross-repo scenario vectors
 * shared with the Longbox app (`shabbyfork/dashboard-dismissal-vectors.json`, a copy of the
 * canonical file in the komga-reader repo). The app's `DismissalVectorsTest` asserts the same
 * vectors against its own derivation; keeping both green is what stops the stock and fork dismissal
 * logic from drifting. Canonical semantics: komga-reader/dashboard-rows-spec.md ("Row dismissal").
 *
 * The `version` assertion is the tripwire: a one-sided change bumps the vectors version and fails
 * whichever repo was not updated.
 */
@SpringBootTest
class ShabbyforkDismissalVectorsTest(
  @Autowired private val dismissalRepository: DashboardDismissalRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val readListRepository: ReadListRepository,
  @Autowired private val readListLifecycle: ReadListLifecycle,
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
  fun clear() {
    readListRepository.deleteAll()
    seriesLifecycle.deleteMany(seriesRepository.findAll())
    dsl.deleteFrom(ShabbyforkTables.DASHBOARD_DISMISSAL).execute()
  }

  private fun vectorsRoot(): JsonNode =
    mapper.readTree(
      requireNotNull(javaClass.getResourceAsStream("/shabbyfork/dashboard-dismissal-vectors.json")) {
        "dashboard-dismissal-vectors.json missing from test resources"
      },
    )

  @Test
  fun `vectors file is the version this suite expects`() {
    assertThat(vectorsRoot().get("version").asText()).isEqualTo(expectedVersion)
  }

  @Test
  fun `active dismissals match every scenario vector`() {
    val vectors = vectorsRoot().get("vectors")
    assertThat(vectors.toList()).isNotEmpty
    vectors.forEach { vector ->
      val name = vector.get("name").asText()
      val kind = vector.get("kind").asText()
      val books = makeBooksWithReads(name, vector.get("books"))

      val dismissedAt = vector.get("dismissedAt").takeUnless { it.isNull }?.asText()
      val targetId: String
      val active: Set<String>
      when (kind) {
        "SERIES" -> {
          targetId = books.first().seriesId
          dismissedAt?.let { storeDismissal("SERIES", targetId, it) }
          active = dismissalRepository.findActiveSeriesDismissals(user.id)
        }
        "READLIST" -> {
          targetId = makeList(name, books).id
          dismissedAt?.let { storeDismissal("READLIST", targetId, it) }
          active = dismissalRepository.findActiveReadListDismissals(user.id)
        }
        else -> error("vector '$name': unknown kind '$kind'")
      }

      val dismissed = targetId in active
      assertThat(dismissed)
        .withFailMessage("vector '%s': expected dismissed=%s but was %s", name, vector.get("expectedDismissed").asBoolean(), dismissed)
        .isEqualTo(vector.get("expectedDismissed").asBoolean())
    }
  }

  /** Create a one-off series holding the vector's books, each with its read date (if any). */
  private fun makeBooksWithReads(
    name: String,
    booksNode: JsonNode,
  ): List<Book> {
    val specs = booksNode.toList()
    val series = seriesLifecycle.createSeries(makeSeries(name = name, libraryId = library.id))
    seriesLifecycle.addBooks(
      series,
      specs.mapIndexed { i, _ -> makeBook("$name-$i", seriesId = series.id, libraryId = library.id).copy(number = i + 1) },
    )
    val created = bookRepository.findAllBySeriesId(series.id).sortedBy { it.number }
    specs.zip(created).forEach { (spec, book) ->
      spec.get("readDate").takeUnless { it.isNull }?.asText()?.let { iso ->
        val readAt = localDateTime(iso)
        readProgressRepository.save(ReadProgress(book.id, user.id, 3, false, readDate = readAt))
        setReadDate(book, readAt)
      }
    }
    return created
  }

  private fun makeList(
    name: String,
    books: List<Book>,
  ): ReadList {
    val bookIds = books.withIndex().associate { (i, book) -> i to book.id }.toSortedMap()
    return readListLifecycle.addReadList(ReadList(name = "list-$name", bookIds = bookIds))
  }

  private fun storeDismissal(
    kind: String,
    targetId: String,
    iso: String,
  ) {
    // Insert the raw UTC wall-clock, same convention as READ_DATE below, so the strict comparison
    // READ_DATE > DISMISSED_AT is exact (the DAO's dismiss() stamps now(); the decision is what we test).
    dsl
      .insertInto(
        ShabbyforkTables.DASHBOARD_DISMISSAL,
        ShabbyforkTables.DASHBOARD_DISMISSAL.USER_ID,
        ShabbyforkTables.DASHBOARD_DISMISSAL.KIND,
        ShabbyforkTables.DASHBOARD_DISMISSAL.TARGET_ID,
        ShabbyforkTables.DASHBOARD_DISMISSAL.DISMISSED_AT,
      ).values(user.id, kind, targetId, localDateTime(iso))
      .execute()
  }

  /**
   * Overwrite READ_DATE with the raw UTC wall-clock from the vector (ReadProgressDao.save re-applies
   * a system-local to UTC shift that would skew the exact-tie case on a non-UTC machine; in
   * production both columns are UTC). See ShabbyforkNewDropVectorsTest for the same note.
   */
  private fun setReadDate(
    book: Book,
    at: LocalDateTime,
  ) {
    dsl
      .update(MainTables.READ_PROGRESS)
      .set(MainTables.READ_PROGRESS.READ_DATE, at)
      .where(
        MainTables.READ_PROGRESS.BOOK_ID
          .eq(book.id)
          .and(MainTables.READ_PROGRESS.USER_ID.eq(user.id)),
      ).execute()
  }

  private fun localDateTime(iso: String): LocalDateTime = OffsetDateTime.parse(iso).toLocalDateTime()
}
