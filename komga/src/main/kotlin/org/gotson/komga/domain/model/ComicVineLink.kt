package org.gotson.komga.domain.model

/**
 * ShabbyFork: a book's web link that points at Comic Vine, with the book's series, as read
 * straight from the database (deleted books included).
 */
data class ComicVineLink(
  val bookId: String,
  val seriesId: String,
  val url: String,
) {
  /** The Comic Vine issue id (the digits after `4000-`), or null when the link is not to an issue. */
  val issueId: String? = comicVineIssueId(url)
}

private val comicVineIssue = Regex("""comicvine\.gamespot\.com/.*\b4000-(\d+)""", RegexOption.IGNORE_CASE)

/** `https://comicvine.gamespot.com/<slug>/4000-694146/` gives `694146`. Volume (`4050-`) and other links give null. */
fun comicVineIssueId(url: String): String? = comicVineIssue.find(url)?.groupValues?.get(1)
