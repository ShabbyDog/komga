package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadContextRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.KomgaUserLifecycle
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.ReadListLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.jooq.main.Tables
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.empty
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * ShabbyFork: the dashboard rows keep reading-list reads apart from series reads, and split
 * Komga's on deck into series you are partway through and series with new books.
 */
@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class ShabbyforkDashboardControllerTest(
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val readListRepository: ReadListRepository,
  @Autowired private val readListLifecycle: ReadListLifecycle,
  @Autowired private val readProgressRepository: ReadProgressRepository,
  @Autowired private val readContextRepository: ReadContextRepository,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val userLifecycle: KomgaUserLifecycle,
  @Autowired private val dsl: DSLContext,
  @Autowired private val mockMvc: MockMvc,
) {
  private val library = makeLibrary(id = "1")
  private val user = KomgaUser("user@example.org", "", id = "1")

  // all books were added long ago unless a test says otherwise; reads happened yesterday
  private val addedLongAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(10)
  private val readYesterday = LocalDateTime.now().minusDays(1)

  @BeforeAll
  fun `setup library`() {
    libraryRepository.insert(library)
    userRepository.insert(user)
  }

  @AfterAll
  fun teardown() {
    userRepository.findAll().forEach { userLifecycle.deleteUser(it) }
    libraryRepository.findAll().forEach { libraryLifecycle.deleteLibrary(it) }
  }

  @AfterEach
  fun `clear repository`() {
    readListRepository.deleteAll()
    seriesLifecycle.deleteMany(seriesRepository.findAll())
    dsl.deleteFrom(ShabbyforkTables.READ_CONTEXT).execute()
  }

  /** A series of three books, numbered 1 to 3, named `<name>1` to `<name>3`. */
  private fun makeSeriesWithBooks(name: String): Pair<Series, List<Book>> {
    val series = seriesLifecycle.createSeries(makeSeries(name = name, libraryId = library.id))
    seriesLifecycle.addBooks(series, (1..3).map { makeBook("$name$it", seriesId = series.id, libraryId = library.id).copy(number = it) })
    val books = bookRepository.findAllBySeriesId(series.id).sortedBy { it.number }
    books.forEach { setAdded(it, addedLongAgo) }
    return series to books
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

  private fun read(
    book: Book,
    completed: Boolean = true,
    throughList: ReadList? = null,
  ) {
    readProgressRepository.save(ReadProgress(book.id, user.id, if (completed) 10 else 3, completed, readDate = readYesterday))
    throughList?.let { readContextRepository.markCurrentProgress(user.id, book.id, it.id) }
  }

  private fun list(vararg books: Book): ReadList {
    val bookIds = books.withIndex().associate { (i, book) -> i to book.id }.toSortedMap()
    return readListLifecycle.addReadList(ReadList(name = books.joinToString { it.name }, bookIds = bookIds))
  }

  @Nested
  inner class KeepReading {
    @Test
    @WithMockCustomUser(id = "1")
    fun `given a book read in its series and one read through a list when listing keep reading then only the series read is shown`() {
      val (_, a) = makeSeriesWithBooks("a")
      val (_, b) = makeSeriesWithBooks("b")
      read(a[0], completed = false)
      read(b[0], completed = false, throughList = list(b[0]))

      mockMvc.get("/api/v1/fork/books/keep-reading").andExpect {
        status { isOk() }
        jsonPath("$.content[*].name") { value(contains("a1")) }
        jsonPath("$.totalElements") { value(1) }
      }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a list read whose progress then changed elsewhere when listing keep reading then it is shown again`() {
      val (_, b) = makeSeriesWithBooks("b")
      read(b[0], completed = false, throughList = list(b[0]))
      readProgressRepository.save(ReadProgress(b[0].id, user.id, 4, false, readDate = readYesterday))

      mockMvc.get("/api/v1/fork/books/keep-reading").andExpect {
        jsonPath("$.content[*].name") { value(contains("b1")) }
      }
    }
  }

  @Nested
  inner class OnDeck {
    @Test
    @WithMockCustomUser(id = "1")
    fun `given a series whose latest completion was a list read when listing on deck then the series is left out`() {
      val (_, a) = makeSeriesWithBooks("a")
      val (_, b) = makeSeriesWithBooks("b")
      read(a[0])
      read(b[0], throughList = list(b[0]))

      mockMvc.get("/api/v1/fork/books/on-deck").andExpect {
        jsonPath("$.content[*].name") { value(contains("a2")) }
      }
      mockMvc.get("/api/v1/fork/books/new-on-deck").andExpect {
        jsonPath("$.content") { value(empty<Any>()) }
      }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a caught-up series with a book added since when listing on deck then it is in new on deck only`() {
      val (_, a) = makeSeriesWithBooks("a")
      val (_, c) = makeSeriesWithBooks("c")
      read(a[0])
      read(c[0])
      // c2 and c3 arrived after c1 was read, so the series was caught up when they did
      setAdded(c[1], LocalDateTime.now(ZoneOffset.UTC))
      setAdded(c[2], LocalDateTime.now(ZoneOffset.UTC))

      mockMvc.get("/api/v1/fork/books/on-deck").andExpect {
        jsonPath("$.content[*].name") { value(contains("a2")) }
      }
      mockMvc.get("/api/v1/fork/books/new-on-deck").andExpect {
        jsonPath("$.content[*].name") { value(contains("c2")) }
      }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given an older book left unread behind the new one when listing on deck then the series is not a new drop`() {
      val (_, c) = makeSeriesWithBooks("c")
      read(c[0])
      // only c3 is new; c2 was already there, unread, before c1 was read
      setAdded(c[2], LocalDateTime.now(ZoneOffset.UTC))

      mockMvc.get("/api/v1/fork/books/on-deck").andExpect {
        jsonPath("$.content[*].name") { value(contains("c2")) }
      }
      mockMvc.get("/api/v1/fork/books/new-on-deck").andExpect {
        jsonPath("$.content") { value(empty<Any>()) }
      }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given more books on deck than a page when paging then pages are exact`() {
      ('a'..'e').forEach { name -> read(makeSeriesWithBooks("$name").second[0]) }

      mockMvc.get("/api/v1/fork/books/on-deck?page=1&size=2").andExpect {
        jsonPath("$.content.length()") { value(2) }
        jsonPath("$.totalElements") { value(5) }
        jsonPath("$.totalPages") { value(3) }
      }
    }
  }

  @Nested
  inner class ReadListsInProgress {
    @Test
    @WithMockCustomUser(id = "1")
    fun `given lists read through, not read through, and finished when listing in progress then only the unfinished one read through is shown`() {
      val (_, a) = makeSeriesWithBooks("a")
      // a[2] is still unread, so this list is not finished even once a[1] is read through the other one
      val started = list(a[0], a[1], a[2])
      list(a[2])
      val finished = list(a[1])
      read(a[0], throughList = started)
      read(a[1], throughList = finished)

      mockMvc.get("/api/v1/fork/readlists/in-progress").andExpect {
        status { isOk() }
        jsonPath("$.content[*].id") { value(contains(started.id)) }
      }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a book shared by two lists read through one when listing in progress then only that list is shown`() {
      val (_, a) = makeSeriesWithBooks("a")
      val readThrough = list(a[0], a[1])
      list(a[0], a[2])
      read(a[0], throughList = readThrough)

      mockMvc.get("/api/v1/fork/readlists/in-progress").andExpect {
        jsonPath("$.content[*].id") { value(contains(readThrough.id)) }
      }
    }
  }
}
