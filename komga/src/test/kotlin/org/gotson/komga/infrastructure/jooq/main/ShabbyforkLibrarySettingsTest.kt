package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Library
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.URL
import org.gotson.komga.jooq.shabbyfork.Tables as ShabbyforkTables

/**
 * ShabbyFork: fork-only library options are stored in shabbyfork.sqlite, never in database.sqlite.
 */
@SpringBootTest
class ShabbyforkLibrarySettingsTest(
  @Autowired private val libraryDao: LibraryDao,
  @Autowired private val dsl: DSLContext,
) {
  private val ls = ShabbyforkTables.LIBRARY_SETTINGS

  @AfterEach
  fun deleteLibraries() {
    libraryDao.deleteAll()
  }

  @Test
  fun `given a library watching its files when inserting and updating then the option round-trips`() {
    val library = Library(name = "Library", root = URL("file://library"), scanOnFilesystemChange = true)

    libraryDao.insert(library)
    assertThat(libraryDao.findById(library.id).scanOnFilesystemChange).isTrue

    libraryDao.update(libraryDao.findById(library.id).copy(scanOnFilesystemChange = false))
    assertThat(libraryDao.findById(library.id).scanOnFilesystemChange).isFalse
  }

  @Test
  fun `given several libraries when finding all then each keeps its own option`() {
    val watched = Library(name = "Watched", root = URL("file://watched"), scanOnFilesystemChange = true)
    val unwatched = Library(name = "Unwatched", root = URL("file://unwatched"))
    libraryDao.insert(watched)
    libraryDao.insert(unwatched)

    val found = libraryDao.findAll().associate { it.id to it.scanOnFilesystemChange }

    assertThat(found).containsExactlyInAnyOrderEntriesOf(mapOf(watched.id to true, unwatched.id to false))
  }

  @Test
  fun `given a library created by stock Komga when finding it then the option defaults to off`() {
    val library = Library(name = "Library", root = URL("file://library"), scanOnFilesystemChange = true)
    libraryDao.insert(library)
    // stock Komga knows nothing of the fork table, so a library it created has no row there
    dsl.deleteFrom(ls).execute()

    assertThat(libraryDao.findById(library.id).scanOnFilesystemChange).isFalse
  }

  @Test
  fun `given a library when deleting it then its fork row goes too`() {
    val library = Library(name = "Library", root = URL("file://library"), scanOnFilesystemChange = true)
    libraryDao.insert(library)

    libraryDao.delete(library.id)

    assertThat(dsl.fetchCount(ls)).isZero
  }

  @Test
  fun `given the main database then it carries no fork column`() {
    assertThat(Tables.LIBRARY.fields().map { it.name }).doesNotContain("SCAN_FS_CHANGE")
  }
}
