package org.gotson.komga.interfaces.api.rest

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadContextRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * ShabbyFork: the fork-only endpoints, and how a read's reading-list context is kept and lost.
 */
@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class ShabbyforkControllerTest(
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val mediaRepository: MediaRepository,
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

  private lateinit var book: Book
  private lateinit var otherBook: Book
  private lateinit var readList: ReadList

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

  @BeforeEach
  fun `setup books and read list`() {
    seriesLifecycle.createSeries(makeSeries(name = "series", libraryId = library.id)).also { series ->
      seriesLifecycle.addBooks(series, listOf(makeBook("1.cbz", libraryId = library.id), makeBook("2.cbz", libraryId = library.id)))
    }
    bookRepository.findAll().forEach { b ->
      mediaRepository.findById(b.id).let { media ->
        mediaRepository.update(media.copy(status = Media.Status.READY, pages = (1..10).map { BookPage("$it", "image/jpeg") }, pageCount = 10))
      }
    }
    book = bookRepository.findAll().first { it.name == "1.cbz" }
    otherBook = bookRepository.findAll().first { it.name == "2.cbz" }
    readList = readListLifecycle.addReadList(ReadList(name = "list", bookIds = sortedMapOf(1 to book.id)))
  }

  @AfterEach
  fun `clear repository`() {
    readListRepository.deleteAll()
    seriesLifecycle.deleteMany(seriesRepository.findAll())
    dsl.deleteFrom(ShabbyforkTables.READ_CONTEXT).execute()
  }

  private fun forkProgress(
    bookId: String,
    json: String,
  ) = mockMvc.patch("/api/v1/fork/books/$bookId/read-progress") {
    contentType = MediaType.APPLICATION_JSON
    content = json
  }

  @Nested
  inner class Capabilities {
    @Test
    @WithMockCustomUser
    fun `given a user when listing capabilities then read-context is offered`() {
      mockMvc
        .get("/api/v1/fork/capabilities")
        .andExpect {
          status { isOk() }
          jsonPath("$.fork") { value("ShabbyFork") }
          jsonPath("$.capabilities") { value(org.hamcrest.Matchers.hasItem("read-context")) }
        }
    }

    @Test
    fun `given no user when listing capabilities then it is refused`() {
      mockMvc
        .get("/api/v1/fork/capabilities")
        .andExpect { status { isUnauthorized() } }
    }
  }

  @Nested
  inner class ReadProgressWithContext {
    @Test
    @WithMockCustomUser(id = "1")
    fun `given a read in a reading list when marking progress then progress and context are recorded`() {
      forkProgress(book.id, """{"page": 5, "readListId": "${readList.id}"}""")
        .andExpect { status { isNoContent() } }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)?.page).isEqualTo(5)
      assertThat(readContextRepository.findCurrentOrNull(user.id, book.id)?.readListId).isEqualTo(readList.id)
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a completed read in a reading list when marking progress then the context is kept`() {
      forkProgress(book.id, """{"completed": true, "readListId": "${readList.id}"}""")
        .andExpect { status { isNoContent() } }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)?.completed).isTrue
      assertThat(readContextRepository.findCurrentOrNull(user.id, book.id)?.readListId).isEqualTo(readList.id)
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a list read followed by a series read when marking progress then the context is cleared`() {
      forkProgress(book.id, """{"page": 5, "readListId": "${readList.id}"}""")
      forkProgress(book.id, """{"page": 6}""")
        .andExpect { status { isNoContent() } }

      assertThat(readContextRepository.findCurrentOrNull(user.id, book.id)).isNull()
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a list read when progress then changes through stock Komga then the context is stale`() {
      forkProgress(book.id, """{"page": 5, "readListId": "${readList.id}"}""")
      mockMvc
        .patch("/api/v1/books/${book.id}/read-progress") {
          contentType = MediaType.APPLICATION_JSON
          content = """{"page": 7}"""
        }.andExpect { status { isNoContent() } }

      assertThat(readContextRepository.findCurrentOrNull(user.id, book.id)).isNull()
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a list read when the book is marked unread then the context is gone`() {
      forkProgress(book.id, """{"page": 5, "readListId": "${readList.id}"}""")
      mockMvc
        .delete("/api/v1/books/${book.id}/read-progress")
        .andExpect { status { isNoContent() } }

      assertThat(readContextRepository.findCurrentOrNull(user.id, book.id)).isNull()
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given a book outside the reading list when marking progress then it is refused and nothing is recorded`() {
      forkProgress(otherBook.id, """{"page": 5, "readListId": "${readList.id}"}""")
        .andExpect { status { isBadRequest() } }

      assertThat(readProgressRepository.findByBookIdAndUserIdOrNull(otherBook.id, user.id)).isNull()
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given an unknown reading list when marking progress then it is refused`() {
      forkProgress(book.id, """{"page": 5, "readListId": "nope"}""")
        .andExpect { status { isBadRequest() } }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given neither page nor completion when marking progress then it is refused`() {
      forkProgress(book.id, """{"readListId": "${readList.id}"}""")
        .andExpect { status { isBadRequest() } }
    }

    @Test
    @WithMockCustomUser(id = "1")
    fun `given an unknown book when marking progress then it is not found`() {
      forkProgress("nope", """{"page": 5}""")
        .andExpect { status { isNotFound() } }
    }
  }
}
