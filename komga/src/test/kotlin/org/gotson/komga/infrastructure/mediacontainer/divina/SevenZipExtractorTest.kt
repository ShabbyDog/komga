package org.gotson.komga.infrastructure.mediacontainer.divina

import org.apache.tika.config.TikaConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.MediaUnsupportedException
import org.gotson.komga.infrastructure.image.ImageAnalyzer
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import java.nio.file.Path
import kotlin.io.path.copyTo

class SevenZipExtractorTest {
  private val contentDetector = ContentDetector(TikaConfig())
  private val imageAnalyzer = ImageAnalyzer()
  private val sevenZipExtractor = SevenZipExtractor(contentDetector, imageAnalyzer)

  @Test
  fun `given 7z file when parsing for entries then returns all images`() {
    val fileResource = ClassPathResource("archives/7zip.7z")

    val entries = sevenZipExtractor.getEntries(fileResource.file.toPath(), true)

    assertThat(entries).hasSize(1)
    with(entries.first()) {
      assertThat(name).isEqualTo("komga.png")
      assertThat(mediaType).isEqualTo("image/png")
      assertThat(dimension).isEqualTo(Dimension(48, 48))
      assertThat(fileSize).isEqualTo(3108)
    }
  }

  @Test
  fun `given 7z file when parsing for entries without analyzing dimensions then returns images without dimensions`() {
    val fileResource = ClassPathResource("archives/7zip.7z")

    val entries = sevenZipExtractor.getEntries(fileResource.file.toPath(), false)

    assertThat(entries).hasSize(1)
    assertThat(entries.first().dimension).isNull()
  }

  @Test
  fun `given solid 7z with multiple entries when parsing then entries are returned in natural order`() {
    val fileResource = ClassPathResource("archives/7zip-solid-multi.7z")

    val entries = sevenZipExtractor.getEntries(fileResource.file.toPath(), true)

    // stored as 1, 10, 2 - natural sort has to put 10 last
    assertThat(entries.map { it.name }).containsExactly("1.png", "2.png", "10.png")
    assertThat(entries).allSatisfy { assertThat(it.mediaType).isEqualTo("image/png") }
  }

  @Test
  fun `given solid 7z when getting the last stored entry then its content is returned`() {
    val fileResource = ClassPathResource("archives/7zip-solid-multi.7z")

    val bytes = sevenZipExtractor.getEntryStream(fileResource.file.toPath(), "2.png")

    assertThat(bytes).hasSize(3108)
    assertThat(contentDetector.detectMediaType(bytes.inputStream())).isEqualTo("image/png")
  }

  @Test
  fun `given 7z archive named cb7 or cbz when detecting media type then it is detected from its content`(
    @TempDir tempDir: Path,
  ) {
    val source = ClassPathResource("archives/7zip.7z").file.toPath()

    listOf("comic.cb7", "comic.cbz", "comic.zip").forEach { name ->
      val renamed = source.copyTo(tempDir.resolve(name), overwrite = true)

      assertThat(contentDetector.detectMediaType(renamed))
        .describedAs("media type of %s", name)
        .isEqualTo("application/x-7z-compressed")
    }
  }

  @Test
  fun `given encrypted 7z when parsing for entries then throws`() {
    val fileResource = ClassPathResource("archives/7zip-encrypted.7z")

    val thrown = catchThrowable { sevenZipExtractor.getEntries(fileResource.file.toPath(), true) }

    assertThat(thrown).isInstanceOf(MediaUnsupportedException::class.java)
  }
}
