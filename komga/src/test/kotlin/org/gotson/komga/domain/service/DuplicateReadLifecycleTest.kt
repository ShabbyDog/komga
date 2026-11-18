package org.gotson.komga.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.WebLink
import org.gotson.komga.domain.model.comicVineIssueId
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadContextRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import java.net.URI
import java.time.LocalDateTime
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * ShabbyFork: a second copy of an issue already read starts out read.
 */
@SpringBootTest
class DuplicateReadLifecycleTest(
  @Autowired private val duplicateReadLifecycle: DuplicateReadLifecycle,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val bookMetadataRepository: BookMetadataRepository,
  @Autowired private val mediaRepository: MediaRepository,
  @Autowired private val readProgressRepository: ReadProgressRepository,
  @Autowired private val readContextRepository: ReadContextRepository,
  @Autowired private val readListRepository: ReadListRepository,
  @Autowired private val readListLifecycle: ReadListLifecycle,
  @Autowired private val bookLifecycle: BookLifecycle,
  @Autowired private val userRepository: KomgaUserRepository,
  @Autowired private val userLifecycle: KomgaUserLifecycle,
  @Autowired private val eventPublisher: ApplicationEventPublisher,
  @Autowired private val dsl: DSLContext,
) {
  private val library = makeLibrary(id = "1")
  private val user = KomgaUser("user@example.org", "", id = "1")
  private val otherUser = KomgaUser("other@example.org", "", id = "2")

  private val quiGon = "https://comicvine.gamespot.com/star-wars-age-of-the-republic-qui-gon-jin-1-balanc/4000-694146/"

  private lateinit var series: Series

  @BeforeAll
  fun setup() {
    libraryRepository.insert(library)
    userRepository.insert(user)
    userRepository.insert(otherUser)
  }

  @AfterAll
  fun teardown() {
    userRepository.findAll().forEach { userLifecycle.deleteUser(it) }
    libraryRepository.findAll().forEach { libraryLifecycle.deleteLibrary(it) }
  }

  @BeforeEach
  fun `setup series`() {
    series = seriesLifecycle.createSeries(makeSeries(name = "Qui-Gon Jinn", libraryId = library.id))
  }

  @AfterEach
  fun `clear repository`() {
    readListRepository.deleteAll()
    seriesLifecycle.deleteMany(seriesRepository.findAll())
    dsl.deleteFrom(ShabbyforkTables.DUPLICATE_READ_CHECK).execute()
    dsl.deleteFrom(ShabbyforkTables.READ_CONTEXT).execute()
  }

  private fun addBook(
    name: String,
    link: String?,
    toSeries: Series = series,
    ready: Boolean = true,
  ): Book {
    seriesLifecycle.addBooks(toSeries, listOf(makeBook(name, libraryId = library.id)))
    val book = bookRepository.findAll().first { it.name == name }
    if (ready) makeReady(book)
    if (link != null)
      bookMetadataRepository.update(bookMetadataRepository.findById(book.id).copy(links = listOf(WebLink("Comic Vine", URI(link)))))
    return book
  }

  private fun makeReady(book: Book) {
    mediaRepository.update(
      mediaRepository.findById(book.id).copy(status = Media.Status.READY, pages = (1..24).map { BookPage("$it", "image/jpeg") }, pageCount = 24),
    )
  }

  private fun read(
    book: Book,
    by: KomgaUser = user,
  ) = bookLifecycle.markReadProgressCompleted(book.id, by)

  private fun progress(
    book: Book,
    of: KomgaUser = user,
  ) = readProgressRepository.findByBookIdAndUserIdOrNull(book.id, of.id)

  @Test
  fun `given comic vine links when parsing then only issue links give an id`() {
    assertThat(comicVineIssueId(quiGon)).isEqualTo("694146")
    assertThat(comicVineIssueId("http://ComicVine.gamespot.com/x/4000-12/")).isEqualTo("12")
    assertThat(comicVineIssueId("https://comicvine.gamespot.com/star-wars/4050-112233/")).isNull()
    assertThat(comicVineIssueId("https://example.org/4000-694146/")).isNull()
  }

  @Test
  fun `given a read copy of the same issue when a new copy is checked then it is marked read`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    val new = addBook("V2019 001.cbz", quiGon)

    val copied = duplicateReadLifecycle.check(listOf(new.id))

    assertThat(copied).isEqualTo(1)
    assertThat(progress(new)?.completed).isTrue
    assertThat(progress(new)?.page).isEqualTo(24)
    assertThat(progress(new, otherUser)).isNull()
  }

  @Test
  fun `given a new copy marked unread after its check when checking again then it stays unread`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    val new = addBook("V2019 001.cbz", quiGon)
    duplicateReadLifecycle.check(listOf(new.id))

    bookLifecycle.deleteReadProgress(new, user)
    val copied = duplicateReadLifecycle.check()

    assertThat(copied).isEqualTo(0)
    assertThat(progress(new)).isNull()
  }

  @Test
  fun `given a copy the user has started when checking then its progress is left alone`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    val new = addBook("V2019 001.cbz", quiGon)
    bookLifecycle.markReadProgress(new, user, 3)

    duplicateReadLifecycle.check(listOf(new.id))

    assertThat(progress(new)?.completed).isFalse
    assertThat(progress(new)?.page).isEqualTo(3)
  }

  @Test
  fun `given a copy only started elsewhere when checking then nothing is marked`() {
    val old = addBook("V2018 001.cbz", quiGon)
    bookLifecycle.markReadProgress(old, user, 10)
    val new = addBook("V2019 001.cbz", quiGon)

    assertThat(duplicateReadLifecycle.check(listOf(new.id))).isEqualTo(0)
    assertThat(progress(new)).isNull()
  }

  @Test
  fun `given a different issue or another series when checking then nothing is marked`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    val otherIssue = addBook("V2018 002.cbz", "https://comicvine.gamespot.com/x/4000-694147/")
    val otherSeries = seriesLifecycle.createSeries(makeSeries(name = "Elsewhere", libraryId = library.id))
    val elsewhere = addBook("Elsewhere 001.cbz", quiGon, toSeries = otherSeries)
    val untagged = addBook("Untagged 001.cbz", null)

    assertThat(duplicateReadLifecycle.check()).isEqualTo(0)
    assertThat(progress(otherIssue)).isNull()
    assertThat(progress(elsewhere)).isNull()
    assertThat(progress(untagged)).isNull()
  }

  @Test
  fun `given a deleted read copy when the replacement is checked then it is marked read`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    bookRepository.update(old.copy(deletedDate = LocalDateTime.now()))
    val new = addBook("V2019 001.cbz", quiGon)

    assertThat(duplicateReadLifecycle.check()).isEqualTo(1)
    assertThat(progress(new)?.completed).isTrue
  }

  @Test
  fun `given a copy not analyzed yet when checking then it is checked once it is ready`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    val new = addBook("V2019 001.cbz", quiGon, ready = false)

    assertThat(duplicateReadLifecycle.check()).isEqualTo(0)
    assertThat(progress(new)).isNull()

    makeReady(new)
    assertThat(duplicateReadLifecycle.check()).isEqualTo(1)
    assertThat(progress(new)?.completed).isTrue
  }

  @Test
  fun `given a library already holding duplicates when checking everything then only the unread copies change`() {
    val old = addBook("V2018 001.cbz", quiGon)
    val new = addBook("V2019 001.cbz", quiGon)
    read(old, by = user)
    read(new, by = otherUser)

    assertThat(duplicateReadLifecycle.check()).isEqualTo(2)
    assertThat(progress(new, user)?.completed).isTrue
    assertThat(progress(old, otherUser)?.completed).isTrue
  }

  @Test
  fun `given a read made in a reading list holding both copies when copied then the context comes along`() {
    val old = addBook("V2018 001.cbz", quiGon)
    val new = addBook("V2019 001.cbz", quiGon)
    val list = readListLifecycle.addReadList(ReadList(name = "Age of Republic", bookIds = sortedMapOf(1 to old.id, 2 to new.id)))
    read(old)
    readContextRepository.markCurrentProgress(user.id, old.id, list.id)

    duplicateReadLifecycle.check(listOf(new.id))

    assertThat(readContextRepository.findCurrentOrNull(user.id, new.id)?.readListId).isEqualTo(list.id)
  }

  @Test
  fun `given a book update event when the new copy has its link then it is marked read`() {
    val old = addBook("V2018 001.cbz", quiGon)
    read(old)
    val new = addBook("V2019 001.cbz", quiGon)

    eventPublisher.publishEvent(DomainEvent.BookUpdated(new))

    assertThat(progress(new)?.completed).isTrue
  }

  @Test
  fun `given checked books that were deleted when cleaning up then they are forgotten`() {
    val old = addBook("V2018 001.cbz", quiGon)
    duplicateReadLifecycle.check()
    seriesLifecycle.deleteMany(seriesRepository.findAll())

    assertThat(duplicateReadLifecycle.deleteOrphans()).isEqualTo(1)
    assertThat(dsl.fetchCount(ShabbyforkTables.DUPLICATE_READ_CHECK, ShabbyforkTables.DUPLICATE_READ_CHECK.BOOK_ID.eq(old.id))).isEqualTo(0)
  }
}
