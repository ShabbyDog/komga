package org.gotson.komga.interfaces.api.rest.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/**
 * ShabbyFork: what this server offers beyond stock Komga. Clients enable each feature by its
 * name in [capabilities], so an older fork build simply lacks the newer names.
 */
data class ShabbyforkCapabilitiesDto(
  val fork: String,
  val version: String?,
  val forkBuild: String?,
  val capabilities: List<String>,
)

/**
 * ShabbyFork: reading-list contexts a client recorded before the server could. [page] and
 * [completed] are the progress the client recorded for that read, and must still be the user's
 * current progress for the context to be taken.
 */
data class ShabbyforkReadContextImportDto(
  @get:Size(max = 10_000) @get:Valid val contexts: List<ShabbyforkReadContextDto>,
)

data class ShabbyforkReadContextDto(
  @get:NotBlank val bookId: String,
  @get:NotBlank val readListId: String,
  @get:Positive val page: Int,
  val completed: Boolean,
)

data class ShabbyforkReadContextImportResultDto(
  val imported: Int,
  val skipped: Int,
)

/** ShabbyFork: the body of Komga's read-progress update, plus the reading list the read was made in. */
data class ShabbyforkReadProgressUpdateDto(
  @get:Positive val page: Int?,
  val completed: Boolean?,
  val readListId: String?,
)
