package sk.freelancegroup.dslsk

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Parser tests against saved HTML. The fixtures imitate dsl.sk's markup; when the
 * site changes, save a real page into src/test/resources/fixtures and add a case.
 */
class DslParserTest {

    private val today = LocalDate.of(2026, 10, 29)

    private fun load(name: String, base: String): Document {
        val bytes = javaClass.getResource("/fixtures/$name")!!.readBytes()
        return Jsoup.parse(String(bytes, Http.charsetOf(null, bytes)), base)
    }

    private fun fmt(ms: Long?) = ms?.let {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(Instant.ofEpochMilli(it).atZone(DslParser.ZONE))
    }

    @Test
    fun homePage_readsNewsBoxOnly_andDecodesWindows1250() {
        val page = DslParser.parseFeed(load("home.html", DslParser.HOME), today)
        // Sidebars ("Top články/správy"), the pinned old article and HOT STORY are ignored.
        assertEquals(listOf(34002, 34000, 33998, 33996, 33994, 33980), page.items.map { it.id })
        val first = page.items[0]
        assertEquals("28.10.2026 19:35", fmt(first.publishedAt))
        assertEquals(10, first.comments)
        assertTrue(first.title.contains("dotykový"))
        assertEquals(null, page.items.last().comments)
        assertEquals("https://www.dsl.sk/index_news.php?page=31092", page.nextUrl)
        assertEquals("https://www.dsl.sk/index_news.php?page=31102", DslParser.detailedFirstPageUrl(page))
    }

    @Test
    fun newsPage_usesPublicationDateNotLastCommentDate() {
        val page = DslParser.parseFeed(load("news.html", "https://www.dsl.sk/index_news.php?page=31092"), today)
        assertEquals(listOf(33982, 33978), page.items.map { it.id })
        assertEquals("26.10.2026 09:43", fmt(page.items[0].publishedAt))
        assertEquals(5, page.items[0].comments)
        assertTrue(page.items[0].perex!!.startsWith("Sprístupňovanie"))
        assertTrue(page.items[1].perex!!.startsWith("Nový kompresný"))
        assertEquals("https://www.dsl.sk/index_news.php?page=31082", page.nextUrl)
    }

    @Test
    fun mergePerex_fillsHomeItemsFromDetailedPage() {
        val home = FeedPage(listOf(FeedItem(1, "Titulok jeden", "u1", null, 1L, 3)), null)
        val detailed = FeedPage(listOf(FeedItem(1, "Titulok jeden", "u1", "Perex jeden", 1L, 3)), null)
        assertEquals("Perex jeden", DslParser.mergePerex(home, detailed).items[0].perex)
    }

    @Test
    fun article_withKnownClasses() {
        val url = "https://www.dsl.sk/article.php?article=33996"
        val a = DslParser.parseArticle(load("article.html", url), url)
        assertEquals("Budovanie AI zvýšilo záujem aj o magnetické pásky, významný výrobca späťnásobí výrobu", a.title)
        assertEquals("DSL.sk, 28.10.2026", a.byline)
        assertEquals(listOf("AI", "úložiská"), a.tags)
        // og:description repeats the first paragraph, so it becomes the lead instead.
        assertEquals(null, a.perex)
        assertTrue(a.leadInBody)
        assertTrue(a.bodyHtml.startsWith("<p>Dopyt po magnetických"))
        assertFalse(a.bodyHtml.contains("<script"))
        assertTrue(a.bodyHtml.contains("<figure>"))
        assertTrue(a.bodyHtml.contains("https://www.dsl.sk/images/clanky/fuji.jpg"))
        assertTrue(a.bodyHtml.contains("i.ytimg.com/vi/AbCdEf12345"))
        assertTrue(a.bodyHtml.contains("<small>Predstavenie"))
        assertFalse(a.bodyHtml.contains("Najnovšie"))
        assertFalse(a.bodyHtml.contains("Odpoveda"))
        assertTrue(a.bodyHtml.split("<p>").size >= 6)
    }

    @Test
    fun article_comments() {
        val url = "https://www.dsl.sk/article.php?article=33996"
        val a = DslParser.parseArticle(load("article.html", url), url)
        assertEquals(listOf(0, 1, 2), a.comments.map { it.depth })
        val first = a.comments[0]
        assertEquals("Pásky sú stále najlacnejšie", first.title)
        assertEquals("technik", first.author)
        assertEquals("28.10.2026 19:42", first.date)
        assertEquals(7.5, first.score!!, 0.001)
        assertTrue(first.bodyHtml.contains("Hlavne pri desiatkach TB."))
        assertEquals(2, first.bodyHtml.split("<p>").size - 1)
        assertFalse(first.bodyHtml.contains("Odpoveda"))
        assertEquals(-10.0, a.comments[1].score!!, 0.001)
        assertEquals("29.10.2026 6:15", a.comments[2].date)
        assertEquals("technik_", a.comments[2].author) // registered users: "Od reg.:"
        assertEquals(null, a.comments[2].score)
        assertTrue(a.comments[2].bodyHtml.contains("https://www.dsl.sk/article.php?article=33900"))
        assertTrue(a.addCommentUrl!!.startsWith("https://www.dsl.sk/article_forum.php?action=reply"))
        assertFalse(a.addCommentUrl!!.contains("entry_id"))
    }

    @Test
    fun article_withoutClasses_fallsBackToHeuristics() {
        val url = "https://www.dsl.sk/article.php?article=34002"
        val a = DslParser.parseArticle(load("article_noclass.html", url), url)
        assertTrue(a.title.startsWith("Apple uviedla"))
        assertEquals("DSL.sk, 28.10.2026", a.byline)
        assertTrue(a.bodyTextLength > 200)
        assertTrue(a.bodyHtml.contains("Cena 14"))
        assertFalse(a.bodyHtml.contains("Najnovší"))
    }
}
