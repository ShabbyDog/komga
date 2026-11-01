package org.gotson.komga.interfaces.api.rest.dto

import jakarta.validation.constraints.Positive

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

/** ShabbyFork: the body of Komga's read-progress update, plus the reading list the read was made in. */
data class ShabbyforkReadProgressUpdateDto(
  @get:Positive val page: Int?,
  val completed: Boolean?,
  val readListId: String?,
)
