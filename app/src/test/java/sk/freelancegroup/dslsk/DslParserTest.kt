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
    fun homePage_keepsNewsDropsOldFeatured_andDecodesWindows1250() {
        val page = DslParser.parseFeed(load("home.html", DslParser.HOME), today)
        assertEquals(listOf(34002, 34000, 33998, 33996, 33994), page.items.map { it.id })
        val first = page.items[0]
        assertEquals("28.10.2026 19:35", fmt(first.publishedAt))
        assertEquals(6, first.comments)
        assertTrue(first.perex!!.startsWith("Spoločnosť Apple"))
        assertTrue(first.title.contains("dotykový"))
        assertEquals("https://www.dsl.sk/index_news.php?page=31092", page.nextUrl)
    }

    @Test
    fun newsPage_usesPublicationDateNotLastCommentDate() {
        val page = DslParser.parseFeed(load("news.html", "https://www.dsl.sk/index_news.php"), today)
        assertEquals(listOf(33982, 33978), page.items.map { it.id })
        assertEquals("26.10.2026 09:43", fmt(page.items[0].publishedAt))
        assertEquals(5, page.items[0].comments)
        assertTrue(page.items[1].perex!!.startsWith("Nový kompresný"))
        assertEquals("https://dsl.sk/index_news.php?page=31082", page.nextUrl)
    }

    @Test
    fun divLayout_isAlsoUnderstood() {
        val page = DslParser.parseFeed(load("news_divs.html", "https://www.dsl.sk/index_news.php?page=31082"), today)
        assertEquals(2, page.items.size)
        assertEquals(27, page.items[0].comments)
        assertEquals("25.10.2026 12:01", fmt(page.items[0].publishedAt))
        assertEquals("https://www.dsl.sk/index_news.php?page=31070", page.nextUrl)
    }

    @Test
    fun article_withKnownClasses() {
        val url = "https://www.dsl.sk/article.php?article=33996"
        val a = DslParser.parseArticle(load("article.html", url), url)
        assertEquals("DSL.sk, 28.10.2026", a.byline)
        assertEquals(listOf("AI", "úložiská"), a.tags)
        assertTrue(a.perex!!.startsWith("Dopyt po magnetických"))
        assertFalse(a.bodyHtml.contains("<script"))
        assertTrue(a.bodyHtml.contains("<figure>"))
        assertTrue(a.bodyHtml.contains("https://www.dsl.sk/images/clanky/fuji.jpg"))
        assertTrue(a.bodyHtml.contains("i.ytimg.com/vi/AbCdEf12345"))
        assertFalse(a.bodyHtml.contains("Odpoveda"))
        assertTrue(a.bodyHtml.split("<p>").size >= 5)
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
