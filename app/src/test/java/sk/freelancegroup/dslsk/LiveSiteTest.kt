package sk.freelancegroup.dslsk

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the parser against the real dsl.sk. Skipped unless DSL_LIVE=1 (see the
 * "Site check" GitHub workflow), so normal builds never depend on the network.
 *
 * Writes report.txt plus *.skeleton.txt files: the pages' tag/class structure with
 * long text removed, handy for adapting the parser when the site changes.
 */
class LiveSiteTest {

    @Test
    fun parserStillUnderstandsDslSk() {
        assumeTrue(System.getenv("DSL_LIVE") == "1")
        val out = File(System.getenv("DSL_SNAPSHOT_DIR") ?: "build/site-snapshot").apply { mkdirs() }
        val report = StringBuilder()
        fun line(s: String) { report.append(s).append('\n'); println(s) }

        val conn = URL(DslParser.HOME).openConnection() as HttpURLConnection
        line("GET ${DslParser.HOME} -> ${conn.responseCode} Content-Type: ${conn.contentType} (final ${conn.url})")
        conn.disconnect()

        val home = Http.getDocument(DslParser.HOME)
        File(out, "home.skeleton.txt").writeText(skeleton(home))
        val homePage = DslParser.parseFeed(home)
        line("\n== Home page: ${homePage.items.size} items, next=${homePage.nextUrl}")
        homePage.items.forEach { line(describe(it)) }

        // Same as FeedRepository.fetchPage: borrow perexes from the detailed listing.
        val detailedUrl = DslParser.detailedFirstPageUrl(homePage)
        val detailed = detailedUrl?.let { DslParser.parseFeed(Http.getDocument(it)) }
        val page1 = if (detailed != null) DslParser.mergePerex(homePage, detailed) else homePage
        line("\n== Detailed first page $detailedUrl: ${detailed?.items?.size} items " +
            "(${detailed?.items?.map { it.id }}); home items with perex after merge: " +
            "${page1.items.count { it.perex != null }}/${page1.items.size}")

        var page2: FeedPage? = null
        page1.nextUrl?.let { next ->
            val doc = Http.getDocument(next)
            File(out, "news.skeleton.txt").writeText(skeleton(doc))
            page2 = DslParser.parseFeed(doc).also { p ->
                line("\n== $next: ${p.items.size} items, next=${p.nextUrl}")
                p.items.forEach { line(describe(it)) }
            }
        }

        val articles = page1.items.take(3).map { item ->
            val doc = Http.getDocument(item.url)
            File(out, "article-${item.id}.skeleton.txt").writeText(skeleton(doc))
            DslParser.parseArticle(doc, item.url).also { a ->
                line("\n== Article ${item.url}")
                line("title:  ${a.title}")
                line("byline: ${a.byline}")
                line("tags:   ${a.tags}")
                line("perex:  ${a.perex?.take(60)}… (${a.perex?.length ?: 0} chars), leadInBody=${a.leadInBody}")
                line("body:   ${a.bodyTextLength} chars, <p>=${a.bodyHtml.split("<p>").size - 1}, " +
                    "figures=${a.bodyHtml.split("<figure").size - 1}, videos=${a.bodyHtml.split("class=\"video\"").size - 1}")
                line("comments: ${a.comments.size} (listed ${item.comments ?: 0}), depths=${a.comments.map { it.depth }}, " +
                    "with author=${a.comments.count { it.author != null }}, date=${a.comments.count { it.date != null }}, " +
                    "score=${a.comments.count { it.score != null }}, empty text=${a.comments.count { it.bodyHtml.isBlank() }}, " +
                    "add link=${a.addCommentUrl != null}")
            }
        }
        File(out, "report.txt").writeText(report.toString())

        assertTrue("home page items", page1.items.size >= 5)
        assertTrue("dated items", page1.items.count { it.publishedAt != null } >= 5)
        assertTrue("older page", (page2?.items?.size ?: 0) >= 5)
        assertTrue("article bodies", articles.all { it.bodyTextLength > 300 })
    }

    private fun describe(i: FeedItem) =
        "  [${i.id}] ${i.publishedAt?.let { java.time.Instant.ofEpochMilli(it) } ?: "-"} c=${i.comments ?: "-"} " +
            "| ${i.title} | perex=${i.perex?.length ?: 0} chars"

    /**
     * Indented tag tree. Text up to 30 characters is kept; up to 80 characters it keeps
     * its shape (digits, punctuation) with letters masked; longer text becomes its length.
     */
    private fun skeleton(doc: Document): String {
        val sb = StringBuilder()
        fun walk(n: Node, depth: Int) {
            val pad = "  ".repeat(depth.coerceAtMost(40))
            when (n) {
                is TextNode -> {
                    val t = n.text().trim()
                    val shown = when {
                        t.length <= 30 -> "\"$t\""
                        t.length <= 80 -> "~\"" + t.map { c -> if (c.isLetter()) 'a' else c }.joinToString("") + "\""
                        else -> "[${t.length} chars]"
                    }
                    if (t.isNotEmpty()) sb.append(pad).append(shown).append('\n')
                }
                is Element -> {
                    val attrs = n.attributes().filter { it.key in setOf("class", "id", "name", "href", "src", "property", "content", "charset", "http-equiv", "colspan", "rowspan", "width", "style", "align", "valign") }
                        .joinToString("") { a ->
                            val v = if (a.key == "content" && a.value.length > 60) "[${a.value.length} chars]" else a.value.take(120)
                            " ${a.key}=\"$v\""
                        }
                    sb.append(pad).append('<').append(n.tagName()).append(attrs).append(">\n")
                    if (n.tagName() !in setOf("script", "style", "noscript", "svg")) n.childNodes().forEach { walk(it, depth + 1) }
                }
            }
        }
        walk(doc, 0)
        return sb.toString()
    }
}
