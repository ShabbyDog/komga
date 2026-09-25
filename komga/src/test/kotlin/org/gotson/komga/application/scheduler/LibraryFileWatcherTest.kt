package org.gotson.komga.application.scheduler

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Library
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * ShabbyFork: covers the filesystem watching behind the library's `scanOnFilesystemChange` option.
 *
 * These tests drive the real platform watcher rather than a fake, since the point of the feature
 * is that the platform reports the change. The quiet period is shortened so they do not wait a
 * minute, and every wait has a generous ceiling so a slow machine does not fail the build.
 *
 * Note this project runs tests with `per_class` instance lifecycle, so the watcher and its
 * temporary folder are rebuilt per test rather than held as shared fields.
 */
class LibraryFileWatcherTest {
  @TempDir
  lateinit var tempDir: Path

  private lateinit var root: Path
  private lateinit var watcher: LibraryFileWatcher
  private lateinit var latch: CountDownLatch
  private val scans = AtomicInteger(0)

  @BeforeEach
  fun setup(testInfo: TestInfo) {
    // a folder of its own per test, so files left by one test cannot disturb the next
    root =
      tempDir
        .resolve(
          testInfo.testMethod
            .get()
            .name
            .hashCode()
            .toString(),
        ).createDirectories()

    scans.set(0)
    latch = CountDownLatch(1)

    val taskEmitter =
      mockk<TaskEmitter>(relaxed = true).also {
        every { it.scanLibrary(any(), any(), any()) } answers {
          scans.incrementAndGet()
          latch.countDown()
        }
      }

    watcher =
      LibraryFileWatcher(taskEmitter).apply {
        quietPeriod = Duration.ofMillis(300)
      }
  }

  @AfterEach
  fun cleanup() {
    watcher.shutdown()
  }

  private fun library(
    watch: Boolean = true,
    exclusions: Set<String> = emptySet(),
  ) = Library(
    name = "test",
    root = root.toUri().toURL(),
    scanOnFilesystemChange = watch,
    scanDirectoryExclusions = exclusions,
  )

  /** Gives the watcher a moment to settle before the filesystem is touched. */
  private fun startWatching(library: Library) {
    watcher.watchLibrary(library)
    Thread.sleep(SETTLE_MILLIS)
  }

  @Test
  fun `given library watching the filesystem when a file is added then a scan is triggered`() {
    startWatching(library())

    root.resolve("book.cbz").writeText("a book")

    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
    assertThat(scans.get()).isEqualTo(1)
  }

  @Test
  fun `given several changes in quick succession when the filesystem goes quiet then a single scan is triggered`() {
    startWatching(library())

    // stands in for a batch of comics being copied in: the burst must cost one scan, not ten
    repeat(10) { i ->
      root.resolve("book-$i.cbz").writeText("a book")
      Thread.sleep(20)
    }

    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
    // let the quiet period elapse again, to catch a second scan being scheduled behind the first
    Thread.sleep(SETTLE_MILLIS)
    assertThat(scans.get()).isEqualTo(1)
  }

  @Test
  fun `given library not watching the filesystem when a file is added then no scan is triggered`() {
    startWatching(library(watch = false))

    root.resolve("book.cbz").writeText("a book")

    assertThat(latch.await(2, TimeUnit.SECONDS)).isFalse()
    assertThat(scans.get()).isZero()
  }

  @Test
  fun `given an excluded directory when a file is added inside it then no scan is triggered`() {
    startWatching(library(exclusions = setOf("#recycle")))

    val excluded = root.resolve("#recycle").createDirectories()
    excluded.resolve("book.cbz").writeText("a book")

    assertThat(latch.await(2, TimeUnit.SECONDS)).isFalse()
    assertThat(scans.get()).isZero()
  }

  @Test
  fun `given a hidden directory when a file is added inside it then no scan is triggered`() {
    startWatching(library())

    val hidden = root.resolve(".thumbnails").createDirectories()
    hidden.resolve("book.cbz").writeText("a book")

    assertThat(latch.await(2, TimeUnit.SECONDS)).isFalse()
    assertThat(scans.get()).isZero()
  }

  @Test
  fun `given watching was stopped when a file is added then no scan is triggered`() {
    val library = library()
    startWatching(library)
    watcher.stopWatching(library.id)

    root.resolve("book.cbz").writeText("a book")

    assertThat(latch.await(2, TimeUnit.SECONDS)).isFalse()
    assertThat(scans.get()).isZero()
  }
}

private const val SETTLE_MILLIS = 1000L
