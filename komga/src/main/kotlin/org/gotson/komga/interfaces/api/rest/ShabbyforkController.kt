package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.service.ReadContextLifecycle
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.rest.dto.ShabbyforkCapabilitiesDto
import org.gotson.komga.interfaces.api.rest.dto.ShabbyforkReadProgressUpdateDto
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

private const val TAG = "ShabbyFork"

/**
 * ShabbyFork: endpoints that exist only in this fork, all under `api/v1/fork`, so stock
 * Komga's endpoints keep behaving exactly as stock for every client.
 */
@RestController
@RequestMapping("api/v1/fork", produces = [MediaType.APPLICATION_JSON_VALUE])
class ShabbyforkController(
  private val bookRepository: BookRepository,
  private val readListRepository: ReadListRepository,
  private val readContextLifecycle: ReadContextLifecycle,
  private val contentRestrictionChecker: ContentRestrictionChecker,
  private val buildProperties: ObjectProvider<BuildProperties>,
) {
  @Operation(summary = "List fork capabilities", description = "What this ShabbyFork build offers beyond stock Komga. Stock Komga has no such endpoint.", tags = [TAG])
  @GetMapping("capabilities")
  fun getCapabilities(): ShabbyforkCapabilitiesDto {
    val build = buildProperties.ifAvailable
    return ShabbyforkCapabilitiesDto(
      fork = "ShabbyFork",
      version = build?.version,
      forkBuild = build?.get("forkBuild"),
      capabilities = CAPABILITIES,
    )
  }

  @Operation(
    summary = "Mark book's read progress, with its reading list",
    description = "Same as Komga's read-progress update, and also records the reading list the read was made in, or that it was made outside any when readListId is omitted.",
    tags = [TAG],
  )
  @PatchMapping("books/{bookId}/read-progress")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun markBookReadProgress(
    @PathVariable bookId: String,
    @Parameter(description = "page can be omitted if completed is set to true. readListId must be a reading list containing the book.")
    @Valid
    @RequestBody
    readProgress: ShabbyforkReadProgressUpdateDto,
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ) {
    if (readProgress.page == null && readProgress.completed != true) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be specified if completed is false or null")
    val book = bookRepository.findByIdOrNull(bookId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestrictionBook(principal.user, book)

    readProgress.readListId?.let { readListId ->
      val readList =
        readListRepository.findByIdOrNull(readListId, SearchContext(principal.user))
          ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Read list not found")
      if (!readList.bookIds.containsValue(book.id)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Book is not in the read list")
    }

    try {
      readContextLifecycle.markReadProgress(book, principal.user, readProgress.page, readProgress.completed, readProgress.readListId)
    } catch (e: IllegalArgumentException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }
  }

  companion object {
    /** Capability names. Only ever add to this list; a client that sees a name relies on it. */
    val CAPABILITIES = listOf("read-context", "dashboard-rows")
  }
}
