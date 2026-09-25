package org.gotson.komga.application.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import io.methvin.watcher.DirectoryWatcher
import jakarta.annotation.PreDestroy
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Library
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isDirectory
import kotlin.io.path.pathString

private val logger = KotlinLogging.logger {}

/**
 * ShabbyFork: watches library root folders and triggers a scan when their contents change.
 *
 * The backing implementation is platform native: FSEvents on macOS, and the JDK's WatchService
 * elsewhere, which is inotify on Linux and ReadDirectoryChangesW on Windows. Subdirectories are
 * registered recursively, so a change anywhere under the root is seen.
 *
 * This mirrors [LibraryScanScheduler]: one entry per library, replaced whenever the library
 * changes, and it is the library's `scanOnFilesystemChange` option that decides whether an
 * entry exists at all.
 */
@Service
class LibraryFileWatcher(
  private val taskEmitter: TaskEmitter,
) {
  /**
   * How long the filesystem has to stay quiet before a change triggers a scan.
   *
   * Copying a large comic into a library produces a long burst of events, and the file is not
   * complete until the burst ends. Every event restarts the countdown, so only the lull at the
   * end of the burst triggers the scan, and a batch of files copied together costs one scan
   * rather than one per file.
   *
   * Tests shorten it so they do not have to wait a minute; nothing else changes it.
   */
  internal var quietPeriod: Duration = Duration.ofSeconds(60)

  // map the libraryId to its running watcher, to the scan it has pending, and to what is watched
  private val watchers = ConcurrentHashMap<String, DirectoryWatcher>()
  private val pendingScans = ConcurrentHashMap<String, ScheduledFuture<*>>()
  private val watched = ConcurrentHashMap<String, WatchSpec>()

  private val watchExecutor = Executors.newCachedThreadPool(daemonThreadFactory("library-watcher"))
  private val quietPeriodExecutor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("library-watcher-quiet"))

  /**
   * Starts watching [library] if it has the option enabled, and stops watching it otherwise.
   * Safe to call repeatedly: any watcher already running for the library is replaced.
   */
  fun watchLibrary(library: Library) {
    val wanted =
      if (library.scanOnFilesystemChange && library.unavailableDate == null)
        WatchSpec(library.root.toString(), library.scanDirectoryExclusions)
      else
        null

    // nothing that affects the watch has changed, so leave any running watcher alone
    if (wanted == watched[library.id]) return

    stopWatching(library.id)
    if (wanted == null) return

    val root = library.path
    if (!root.isDirectory()) {
      logger.warn { "Library root folder is not accessible, not watching filesystem: ${library.name}" }
      return
    }

    try {
      val watcher =
        DirectoryWatcher
          .builder()
          .path(root)
          // hashing every file to de-duplicate events would read the whole library on startup
          .fileHashing(false)
          .listener { event ->
            if (!isExcluded(library, event.path()))
              scanAfterQuietPeriod(library)
          }.build()

      watchers[library.id] = watcher

      watcher.watchAsync(watchExecutor).whenComplete { _, error ->
        // a close() completes this normally, so only a real failure is worth reporting
        if (error != null && watchers[library.id] === watcher)
          logger.error(error) { "Filesystem watch failed for library: ${library.name}" }
      }

      watched[library.id] = wanted
      logger.info { "Watching filesystem for library: ${library.name}" }
    } catch (e: Exception) {
      logger.error(e) { "Could not watch filesystem for library: ${library.name}" }
      watchers.remove(library.id)
    }
  }

  /** Stops watching the library, and drops any scan it still had pending. */
  fun stopWatching(libraryId: String) {
    watched.remove(libraryId)
    pendingScans.remove(libraryId)?.cancel(false)
    watchers.remove(libraryId)?.let { watcher ->
      try {
        watcher.close()
      } catch (e: Exception) {
        logger.warn(e) { "Could not close filesystem watcher for library: $libraryId" }
      }
    }
  }

  private fun scanAfterQuietPeriod(library: Library) {
    pendingScans.compute(library.id) { libraryId, pending ->
      pending?.cancel(false)
      quietPeriodExecutor.schedule(
        {
          pendingScans.remove(libraryId)
          logger.info { "Filesystem changed, scanning library: ${library.name}" }
          taskEmitter.scanLibrary(libraryId)
        },
        quietPeriod.toMillis(),
        TimeUnit.MILLISECONDS,
      )
    }
  }

  /**
   * Mirrors the exclusions the scanner itself applies, so a change the scan would ignore does
   * not wake it: a hidden folder anywhere below the root, or a path matching one of the
   * library's directory exclusions.
   */
  private fun isExcluded(
    library: Library,
    path: Path,
  ): Boolean {
    val belowRoot = runCatching { library.path.relativize(path) }.getOrNull() ?: path
    if (belowRoot.any { it.pathString.startsWith(".") }) return true
    return library.scanDirectoryExclusions.any { path.pathString.contains(it, true) }
  }

  /**
   * Libraries change through domain events, so the watch follows those rather than being wired
   * into every call site. It also means a library whose root folder was missing starts being
   * watched as soon as a scan finds it again, since that publishes an update.
   */
  @EventListener
  fun handleLibraryEvent(event: DomainEvent) {
    when (event) {
      is DomainEvent.LibraryAdded -> watchLibrary(event.library)
      is DomainEvent.LibraryUpdated -> watchLibrary(event.library)
      is DomainEvent.LibraryDeleted -> stopWatching(event.library.id)
      else -> Unit
    }
  }

  @PreDestroy
  fun shutdown() {
    watchers.keys.toList().forEach { stopWatching(it) }
    watchExecutor.shutdownNow()
    quietPeriodExecutor.shutdownNow()
  }
}

/** What a running watcher covers, so an unrelated library update does not rebuild it. */
private data class WatchSpec(
  val root: String,
  val exclusions: Set<String>,
)

private fun daemonThreadFactory(name: String): ThreadFactory {
  val counter = AtomicInteger(0)
  return ThreadFactory { runnable ->
    Thread(runnable, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
  }
}
