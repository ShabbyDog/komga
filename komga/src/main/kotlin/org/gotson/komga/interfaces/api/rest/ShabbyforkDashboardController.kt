package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.gotson.komga.application.query.UnpagedSorted
import org.gotson.komga.domain.model.BookSearch
import org.gotson.komga.domain.model.ReadStatus
import org.gotson.komga.domain.model.SearchCondition
import org.gotson.komga.domain.model.SearchContext
import org.gotson.komga.domain.model.SearchOperator
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.infrastructure.openapi.PageableWithoutSortAsQueryParam
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.persistence.BookDtoRepository
import org.gotson.komga.interfaces.api.persistence.ShabbyforkDashboardRepository
import org.gotson.komga.interfaces.api.rest.dto.BookDto
import org.gotson.komga.interfaces.api.rest.dto.ReadListDto
import org.gotson.komga.interfaces.api.rest.dto.restrictUrl
import org.gotson.komga.interfaces.api.rest.dto.toDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val TAG = "ShabbyFork"

/**
 * ShabbyFork: dashboard rows that treat reading-list reads apart from series reads.
 *
 * Each row starts from Komga's own query for it (in-progress books, books on deck), so what
 * counts as in progress or on deck, and what a user may see, stays exactly Komga's. The fork
 * only filters and splits that result, using reading-list contexts. Rows are small, so they are
 * filtered whole and then paged, which keeps paging exact.
 */
@RestController
@RequestMapping("api/v1/fork", produces = [MediaType.APPLICATION_JSON_VALUE])
class ShabbyforkDashboardController(
  private val bookDtoRepository: BookDtoRepository,
  private val readListRepository: ReadListRepository,
  private val dashboardRepository: ShabbyforkDashboardRepository,
) {
  @Operation(
    summary = "List books to keep reading",
    description = "Books in progress, most recently read first, leaving out those being read through a reading list.",
    tags = [TAG],
  )
  @PageableWithoutSortAsQueryParam
  @GetMapping("books/keep-reading")
  fun getKeepReading(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: List<String>? = null,
    @Parameter(hidden = true) page: Pageable,
  ): Page<BookDto> {
    val search =
      BookSearch(
        SearchCondition.AllOfBook(
          buildList {
            if (!libraryIds.isNullOrEmpty()) add(SearchCondition.AnyOfBook(libraryIds.map { SearchCondition.LibraryId(SearchOperator.Is(it)) }))
            add(SearchCondition.ReadStatus(SearchOperator.Is(ReadStatus.IN_PROGRESS)))
          },
        ),
      )
    val inProgress =
      bookDtoRepository
        .findAll(search, SearchContext(principal.user), UnpagedSorted(Sort.by(Sort.Order.desc("readProgress.readDate"))))
        .content
    val readInList = dashboardRepository.findBookIdsReadInReadList(principal.user.id, inProgress.map { it.id })
    return inProgress
      .filterNot { it.id in readInList }
      .toPage(page)
      .map { it.restrictUrl(!principal.user.isAdmin) }
  }

  @Operation(
    summary = "List books on deck",
    description =
      "Komga's books on deck, leaving out series whose latest completed book was read through a reading list, " +
        "and series the user had caught up on before new books arrived (see new-on-deck).",
    tags = [TAG],
  )
  @PageableWithoutSortAsQueryParam
  @GetMapping("books/on-deck")
  fun getOnDeck(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: List<String>? = null,
    @Parameter(hidden = true) page: Pageable,
  ): Page<BookDto> =
    onDeck(principal, libraryIds)
      .regular
      .toPage(page)
      .map { it.restrictUrl(!principal.user.isAdmin) }

  @Operation(
    summary = "List new books on deck",
    description =
      "Komga's books on deck in series the user had caught up on before new books arrived: every book not yet " +
        "completed was added after the user's latest completion in the series.",
    tags = [TAG],
  )
  @PageableWithoutSortAsQueryParam
  @GetMapping("books/new-on-deck")
  fun getNewOnDeck(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: List<String>? = null,
    @Parameter(hidden = true) page: Pageable,
  ): Page<BookDto> =
    onDeck(principal, libraryIds)
      .newBooks
      .toPage(page)
      .map { it.restrictUrl(!principal.user.isAdmin) }

  @Operation(
    summary = "List reading lists in progress",
    description = "Reading lists the user has read at least one book through and not yet completed, most recently read first.",
    tags = [TAG],
  )
  @PageableWithoutSortAsQueryParam
  @GetMapping("readlists/in-progress")
  fun getReadListsInProgress(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @Parameter(hidden = true) page: Pageable,
  ): Page<ReadListDto> =
    dashboardRepository
      .findReadListIdsInProgress(principal.user.id)
      .mapNotNull { readListRepository.findByIdOrNull(it, SearchContext(principal.user)) }
      .filter { it.bookIds.isNotEmpty() }
      .toPage(page)
      .map { it.toDto() }

  private class OnDeck(
    val regular: List<BookDto>,
    val newBooks: List<BookDto>,
  )

  // Each series appears in at most one of the two rows, and in Komga's order.
  private fun onDeck(
    principal: KomgaPrincipal,
    libraryIds: List<String>?,
  ): OnDeck {
    val user = principal.user
    val candidates =
      bookDtoRepository
        .findAllOnDeck(user.id, user.getAuthorizedLibraryIds(libraryIds), Pageable.unpaged(), user.restrictions)
        .content
    val seriesIds = candidates.map { it.seriesId }.distinct()
    val readInList = dashboardRepository.findSeriesLastCompletedInReadList(user.id, seriesIds)
    val newBooks = dashboardRepository.findSeriesCaughtUpBeforeNewBooks(user.id, seriesIds)
    val shown = candidates.filterNot { it.seriesId in readInList }
    return OnDeck(
      regular = shown.filterNot { it.seriesId in newBooks },
      newBooks = shown.filter { it.seriesId in newBooks },
    )
  }

  private fun <T : Any> List<T>.toPage(pageable: Pageable): Page<T> {
    if (pageable.isUnpaged) return PageImpl(this)
    val from = pageable.offset.coerceAtMost(size.toLong()).toInt()
    val to = (from + pageable.pageSize).coerceAtMost(size)
    return PageImpl(subList(from, to), pageable, size.toLong())
  }
}
