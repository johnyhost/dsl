package sk.freelancegroup.dslsk

/** One headline in the news list. */
data class FeedItem(
    val id: Int,
    val title: String,
    val url: String,
    val perex: String?,
    /** Publication time in epoch millis, or null when the page did not show one. */
    val publishedAt: Long?,
    val comments: Int?,
)

/** One downloaded page of the news list plus the link to the next (older) page. */
data class FeedPage(
    val items: List<FeedItem>,
    val nextUrl: String?,
)

/** A parsed article, ready to be rendered by [ArticleHtml]. */
data class Article(
    val id: Int?,
    val url: String,
    val title: String,
    val byline: String?,
    val tags: List<String>,
    val perex: String?,
    /** Sanitised HTML of the article body (no scripts, absolute URLs). */
    val bodyHtml: String,
    /** Plain-text length of [bodyHtml]; used to decide whether parsing worked. */
    val bodyTextLength: Int,
)

/** "1 komentár", "3 komentáre", "7 komentárov". */
fun commentsLabel(n: Int): String = when {
    n == 1 -> "1 komentár"
    n in 2..4 -> "$n komentáre"
    else -> "$n komentárov"
}
