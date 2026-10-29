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
    /** True when the first body paragraph doubles as the perex (styled as the lead). */
    val leadInBody: Boolean = false,
    /** The discussion under the article, in page order (replies follow their parent). */
    val comments: List<Comment> = emptyList(),
    /** "Pridať komentár" on the website (posting needs a dsl.sk login). */
    val addCommentUrl: String? = null,
)

/** One discussion entry. */
data class Comment(
    val id: String,
    val title: String,
    val author: String?,
    /** As shown on the site, e.g. "28.10.2026 19:42". */
    val date: String?,
    /** Sanitised HTML of the comment text. */
    val bodyHtml: String,
    /** 0 = top level, 1 = reply, 2 = reply to a reply, … */
    val depth: Int,
    /** The site's "Známka" (rating), if shown. */
    val score: Double?,
)

/** "1 komentár", "3 komentáre", "7 komentárov". */
fun commentsLabel(n: Int): String = when {
    n == 1 -> "1 komentár"
    n in 2..4 -> "$n komentáre"
    else -> "$n komentárov"
}
