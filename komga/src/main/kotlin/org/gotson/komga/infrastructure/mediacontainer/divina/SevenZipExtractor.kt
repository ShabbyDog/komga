package org.gotson.komga.infrastructure.mediacontainer.divina

import io.github.oshai.kotlinlogging.KotlinLogging
import net.greypanther.natsort.CaseInsensitiveSimpleNaturalComparator
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.gotson.komga.domain.model.MediaContainerEntry
import org.gotson.komga.domain.model.MediaType
import org.gotson.komga.domain.model.MediaUnsupportedException
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.springframework.stereotype.Service
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

@Service
class SevenZipExtractor(
  private val contentDetector: ContentDetector,
  private val imageAnalyzer: ImageAnalyzer,
) : DivinaExtractor {
  private val natSortComparator: Comparator<String> = CaseInsensitiveSimpleNaturalComparator.getInstance()

  override fun mediaTypes(): List<String> = listOf(MediaType.SEVENZIP.type)

  override fun getEntries(
    path: Path,
    analyzeDimensions: Boolean,
  ): List<MediaContainerEntry> =
    openArchive(path).use { sevenZip ->
      buildList {
        // 7z archives are usually solid: entries have to be read in order, or every read
        // restarts the decompression of the whole block.
        var entry = sevenZip.nextEntry
        while (entry != null) {
          if (!entry.isDirectory) add(entry.toContainerEntry(sevenZip, analyzeDimensions))
          entry = sevenZip.nextEntry
        }
      }.sortedWith(compareBy(natSortComparator) { it.name })
    }

  private fun SevenZArchiveEntry.toContainerEntry(
    sevenZip: SevenZFile,
    analyzeDimensions: Boolean,
  ): MediaContainerEntry =
    try {
      if (isEncrypted()) throw MediaUnsupportedException("Encrypted 7z archives are not supported", "ERR_1040")
      val buffer = sevenZip.getInputStream(this).use { it.readBytes() }
      val mediaType = buffer.inputStream().use { contentDetector.detectMediaType(it) }
      val dimension =
        if (analyzeDimensions && contentDetector.isImage(mediaType))
          buffer.inputStream().use { imageAnalyzer.getDimension(it) }
        else
          null
      val fileSize = if (size == ArchiveEntry.SIZE_UNKNOWN) null else size
      MediaContainerEntry(name = name, mediaType = mediaType, dimension = dimension, fileSize = fileSize)
    } catch (e: MediaUnsupportedException) {
      throw e
    } catch (e: Exception) {
      logger.warn(e) { "Could not analyze entry: $name" }
      MediaContainerEntry(name = name, comment = e.message)
    }

  override fun getEntryStream(
    path: Path,
    entryName: String,
  ): ByteArray =
    openArchive(path).use { sevenZip ->
      var entry = sevenZip.nextEntry
      while (entry != null) {
        if (entry.name == entryName) return sevenZip.getInputStream(entry).use { it.readBytes() }
        entry = sevenZip.nextEntry
      }
      throw MediaUnsupportedException("Entry not found in 7z archive: $entryName", "ERR_1008")
    }

  private fun openArchive(path: Path): SevenZFile =
    try {
      SevenZFile.builder().setPath(path).get()
    } catch (_: PasswordRequiredException) {
      throw MediaUnsupportedException("Encrypted 7z archives are not supported", "ERR_1040")
    }

  /** An entry is encrypted when AES is part of the codec chain used to store it. */
  private fun SevenZArchiveEntry.isEncrypted(): Boolean = contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
}
