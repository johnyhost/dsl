package sk.freelancegroup.dslsk

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.IdentityHashMap

/**
 * Turns dsl.sk HTML into [FeedPage] / [Article].
 *
 * dsl.sk is an old table-based site without a stable API, so every step uses the
 * known class names first (`article_body`, `article_perex`, as the 2012 app did)
 * and falls back to structural heuristics. Nothing here touches Android APIs, so it
 * can be unit-tested on a plain JVM.
 */
object DslParser {

    const val BASE = "https://www.dsl.sk/"
    const val HOME = BASE
    val ZONE: ZoneId = ZoneId.of("Europe/Bratislava")

    private val ARTICLE_ID = Regex("""article\.php\?(?:[^"'#]*?&(?:amp;)?)?article=(\d+)""")
    private val DATE_FULL = Regex("""(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4})(?:,?\s+(\d{1,2}):(\d{2}))?""")
    private val DATE_SHORT = Regex("""(\d{1,2})\.\s?(\d{1,2})\.\s+(\d{1,2}):(\d{2})""")
    private val BYLINE = Regex("""^\s*[^\d\n]{2,60},\s*\d{1,2}\.\s?\d{1,2}\.\s?\d{4}(?:\s+\d{1,2}:\d{2})?\s*$""")
    private val FIRST_INT = Regex("""\d+""")
    private val YOUTUBE = Regex("""(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=|v/|shorts/)|youtu\.be/)([A-Za-z0-9_-]{6,})""")
    private val WS = Regex("""[ \t ]+""")

    fun articleIdOf(href: String): Int? = ARTICLE_ID.find(href)?.groupValues?.get(1)?.toIntOrNull()

    fun articleUrl(id: Int) = "${BASE}article.php?article=$id"

    fun discussionUrl(id: Int) = "${BASE}article.php?article=$id&title=#discussion"

    // ---------------------------------------------------------------- news list

    fun parseFeed(doc: Document, today: LocalDate = LocalDate.now(ZONE)): FeedPage {
        // Known layout (2026): the home page lists news in div#news_box; the older-news
        // pages list them in div#body (left column = "Top" sidebars, div#map = HOT STORY).
        val scopes = listOfNotNull(doc.selectFirst("#news_box"), doc.selectFirst("#body"), doc.body())
        for (scope in scopes) {
            val items = collectItems(scope, today)
            if (items.isNotEmpty() || scope === scopes.last()) return FeedPage(items, findNextUrl(doc))
        }
        return FeedPage(emptyList(), findNextUrl(doc))
    }

    private fun collectItems(scope: Element, today: LocalDate): List<FeedItem> {
        val idCache = IdentityHashMap<Element, Set<Int>>()

        // Every textual link to an article is a candidate; one article can appear
        // several times (main list, "Top" sidebars...). Keep the richest occurrence.
        data class Candidate(val order: Int, val item: FeedItem, val richness: Int)
        val best = LinkedHashMap<Int, Candidate>()
        var order = 0
        for (a in scope.select("a[href*=article.php]")) {
            if (a.closest("#map") != null) continue
            val href = a.attr("href")
            val id = articleIdOf(href) ?: continue
            if (isDiscussionLink(a)) continue
            val title = clean(a.text())
            if (!looksLikeTitle(title)) continue

            val seg = segment(a, id, idCache)
            val meta = readSegment(seg, a)
            val item = FeedItem(
                id = id,
                title = title,
                url = articleUrl(id),
                perex = meta.perex,
                publishedAt = meta.date?.let { parseDate(it, today) },
                comments = meta.comments,
            )
            val richness = (if (item.publishedAt != null) 4 else 0) +
                (if (item.perex != null) 2 else 0) + (if (item.comments != null) 1 else 0)
            val prev = best[id]
            if (prev == null || richness > prev.richness) {
                best[id] = Candidate(prev?.order ?: order, item, richness)
            }
            order++
        }

        var items = best.values.sortedBy { it.order }.map { it.item }
        items = dropSidebarLeftovers(items)
        // Newest first; undated items keep their place next to their neighbours.
        var lastDate = Long.MAX_VALUE
        val keyed = items.map { item -> (item.publishedAt ?: lastDate).also { lastDate = it } to item }
        return keyed.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * Safety net for when the page had to be scanned as a whole: real list entries
     * carry a date, sidebar links don't, and featured blocks can be months old.
     */
    private fun dropSidebarLeftovers(items: List<FeedItem>): List<FeedItem> {
        val dated = items.mapNotNull { it.publishedAt }
        if (dated.size < 5) return items
        val cutoff = dated.max() - 21L * 24 * 3600 * 1000
        return items.filter { it.publishedAt != null && it.publishedAt >= cutoff }
    }

    /**
     * The home page news box has no perexes. The same news, with perexes, sit on
     * index_news.php one page "above" the home page's "Staršie správy" link.
     */
    fun detailedFirstPageUrl(home: FeedPage): String? {
        val next = home.nextUrl ?: return null
        val n = Regex("""page=(\d+)""").find(next)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return next.replace("page=$n", "page=${n + 10}")
    }

    /** Fills in missing perexes on [home] from [detailed], matching by article id. */
    fun mergePerex(home: FeedPage, detailed: FeedPage): FeedPage {
        val byId = detailed.items.associateBy { it.id }
        return home.copy(items = home.items.map { item ->
            val d = byId[item.id]
            if (item.perex == null && d?.perex != null) item.copy(perex = d.perex) else item
        })
    }

    private fun findNextUrl(doc: Document): String? {
        val links = doc.select("a[href*=index_news.php]")
        val older = links.firstOrNull { it.text().contains("Staršie", ignoreCase = true) }
            ?: links.lastOrNull { it.attr("href").contains("page=") }
        return older?.absUrl("href")?.takeIf { it.isNotEmpty() }?.let(::forceHttps)
    }

    private fun isDiscussionLink(a: Element): Boolean {
        val href = a.attr("href")
        if (href.contains("discussion") || href.contains("count=") || href.contains('#')) return true
        val t = a.text().trim()
        return t.contains("koment", ignoreCase = true) || t.matches(Regex("""\(?\d+\)?"""))
    }

    private fun looksLikeTitle(t: String) =
        t.length >= 8 && t.any { it.isLetter() } &&
            !t.contains("koment", ignoreCase = true) &&
            !t.equals("Čítať ďalej", ignoreCase = true)

    private fun idsIn(el: Element, cache: IdentityHashMap<Element, Set<Int>>): Set<Int> =
        cache.getOrPut(el) {
            val own = if (el.tagName() == "a") listOfNotNull(articleIdOf(el.attr("href"))) else emptyList()
            (own + el.select("a[href*=article.php]").mapNotNull { articleIdOf(it.attr("href")) }).toSet()
        }

    /**
     * The nodes that belong to one headline: the largest ancestor that links to this
     * article only, plus following siblings up to the next article (covers both
     * "one table row per item" and flat `<a>…<br>date<br>perex` layouts).
     */
    private fun segment(link: Element, id: Int, cache: IdentityHashMap<Element, Set<Int>>): List<Node> {
        var top: Element = link
        var p = link.parent()
        while (p != null && p !is Document && p.tagName() != "body") {
            val ids = idsIn(p, cache)
            if (ids.size == 1 && ids.first() == id) {
                top = p
                p = p.parent()
            } else break
        }
        val nodes = mutableListOf<Node>(top)
        var sib = top.nextSibling()
        var n = 0
        while (sib != null && n < 40) {
            if (sib is Element && idsIn(sib, cache).any { it != id }) break
            if (sib is Element && sib.tagName() == "hr") break
            nodes += sib
            sib = sib.nextSibling()
            n++
        }
        return nodes
    }

    private class SegmentMeta(val date: MatchResult?, val perex: String?, val comments: Int?)

    private fun readSegment(nodes: List<Node>, titleLink: Element): SegmentMeta {
        val sb = StringBuilder()
        var comments: Int? = null
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    if (node === titleLink) { sb.append('\n'); return }
                    when (node.tagName()) {
                        "script", "style", "noscript" -> return
                        "br" -> { sb.append('\n'); return }
                        "a" -> if (isDiscussionLink(node)) {
                            if (comments == null) comments = FIRST_INT.find(node.text())?.value?.toIntOrNull()
                            sb.append('\n')
                            return
                        }
                    }
                    val block = node.isBlock
                    if (block) sb.append('\n')
                    node.childNodes().forEach(::walk)
                    if (block) sb.append('\n')
                }
            }
        }
        nodes.forEach(::walk)

        val text = sb.toString()
        val full = DATE_FULL.find(text)
        val short = DATE_SHORT.find(text)
        val date = listOfNotNull(full, short).minByOrNull { it.range.first }

        if (comments == null) {
            comments = Regex("""(\d+)\s+koment""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        }

        val perex = text.lines()
            .map { clean(it) }
            .filter { line ->
                line.length >= 40 &&
                    !line.contains("koment", ignoreCase = true) &&
                    !(line.length < 70 && (DATE_FULL.containsMatchIn(line) || DATE_SHORT.containsMatchIn(line)))
            }
            .maxByOrNull { it.length }
            ?.let { if (it.length > 320) it.take(317).trimEnd() + "…" else it }

        return SegmentMeta(date, perex, comments)
    }

    private fun parseDate(m: MatchResult, today: LocalDate): Long? = runCatching {
        val g = m.groupValues
        val dt: LocalDateTime = if (m.value.count { it == '.' } >= 2 && g.size == 6 && g[3].length == 4) {
            // dd.mm.yyyy [hh:mm]
            val d = LocalDate.of(g[3].toInt(), g[2].toInt(), g[1].toInt())
            if (g[4].isNotEmpty()) d.atTime(g[4].toInt(), g[5].toInt()) else d.atTime(12, 0)
        } else {
            // dd.mm. hh:mm – no year, assume the most recent past occurrence
            var d = LocalDate.of(today.year, g[2].toInt(), g[1].toInt())
            if (d.isAfter(today.plusDays(1))) d = d.minusYears(1)
            d.atTime(g[3].toInt(), g[4].toInt())
        }
        dt.atZone(ZONE).toInstant().toEpochMilli()
    }.getOrNull()

    // ---------------------------------------------------------------- article

    fun parseArticle(doc: Document, url: String): Article {
        val id = articleIdOf(url)
        val title = articleTitle(doc)

        val perexEls = doc.select(".article_perex")
        val byline = perexEls.map { clean(it.text()) }
            .firstOrNull { it.length < 100 && DATE_FULL.containsMatchIn(it) }
            ?: findByline(doc)

        var perex = perexEls.map { clean(it.text()) }
            .filter { it.length >= 60 && it != byline }
            .maxByOrNull { it.length }
            ?: meta(doc, "og:description")?.takeIf { it.length >= 40 }
            ?: meta(doc, "description")?.takeIf { it.length >= 40 }

        val tags = findTags(doc)

        var bodyEls: List<Element> = doc.select(".article_body")
        if (bodyEls.isEmpty() || bodyEls.sumOf { it.text().length } < 150) {
            bodyEls = listOfNotNull(guessBody(doc))
        }
        val body = cleanBody(bodyEls, doc.location().ifEmpty { url }, perex)
        val bodyDoc = Jsoup.parseBodyFragment(body)
        val bodyText = bodyDoc.text()

        // dsl.sk articles have no separate perex: the list's perex (and og:description)
        // is the opening of the first paragraph. Show that paragraph as the lead instead.
        var leadInBody = false
        val firstPara = bodyDoc.body().children().firstOrNull()?.takeIf { it.tagName() == "p" }?.let { clean(it.text()) }
        if (perex != null && firstPara != null) {
            val k = minOf(40, perex.length, firstPara.length)
            if (k >= 20 && perex.take(k) == firstPara.take(k)) {
                perex = null
                leadInBody = true
            }
        }

        return Article(
            id = id,
            url = url,
            title = title,
            byline = byline,
            tags = tags,
            perex = perex,
            bodyHtml = body,
            bodyTextLength = bodyText.length,
            leadInBody = leadInBody,
        )
    }

    private fun articleTitle(doc: Document): String {
        val raw = doc.selectFirst(".article_title, .page_title")?.text()
            ?: meta(doc, "og:title")
            ?: doc.title()
        return clean(raw)
            .removePrefix("DSL.sk - ").removePrefix("DSL.sk – ")
            .removeSuffix(" - DSL.sk").removeSuffix(" | DSL.sk")
            .trim()
    }

    private fun meta(doc: Document, name: String): String? {
        val el = doc.selectFirst("meta[property=$name]") ?: doc.selectFirst("meta[name=$name]")
        return el?.attr("content")?.let(::clean)?.takeIf { it.isNotEmpty() }
    }

    private fun findByline(doc: Document): String? {
        for (el in doc.body()?.getAllElements() ?: return null) {
            val own = clean(el.ownText())
            if (own.isNotEmpty() && BYLINE.matches(own)) return own
            if (el.children().size in 1..3) {
                val all = clean(el.text())
                if (all.length < 80 && BYLINE.matches(all)) return all
            }
        }
        return null
    }

    /** Links that follow the "Značky:" label on the same line. */
    private fun findTags(doc: Document): List<String> {
        val holder = doc.body()?.getAllElements()?.firstOrNull { it.ownText().contains("Značky") } ?: return emptyList()
        val labelNode: Node = holder.textNodes().firstOrNull { it.text().contains("Značky") } ?: holder
        val found = mutableListOf<String>()
        // Label element that wraps its own links, e.g. <div>Značky: <a>…</a></div>
        val ownLinks = holder.select("a")
        if (labelNode !== holder && ownLinks.isNotEmpty() && holder.children().all { it.tagName() == "a" || it.tagName() == "br" }) {
            ownLinks.mapTo(found) { clean(it.text()) }
        } else {
            // Otherwise walk forward from the label (or from <b>Značky:</b>) until the line ends.
            val start: Node = if (holder.ownText().trim().trimEnd(':') == "Značky") holder else labelNode
            var sib = start.nextSibling()
            while (sib != null) {
                if (sib is Element) {
                    if (sib.tagName() == "br" || sib.isBlock) break
                    if (sib.tagName() == "a") found += clean(sib.text()) else sib.select("a").mapTo(found) { clean(it.text()) }
                }
                sib = sib.nextSibling()
            }
        }
        return found.filter { it.isNotEmpty() && it.length < 40 }.distinct().take(12)
    }

    /** Readability-style fallback: the element holding the most non-link prose. */
    private fun guessBody(doc: Document): Element? {
        val body = doc.body() ?: return null
        val scores = IdentityHashMap<Element, Double>()
        for (el in body.getAllElements()) {
            if (el.tagName() in setOf("a", "script", "style", "noscript", "option")) continue
            for (tn in el.textNodes()) {
                val len = tn.text().trim().length
                if (len < 25) continue
                scores[el] = (scores[el] ?: 0.0) + len
                el.parent()?.let { scores[it] = (scores[it] ?: 0.0) + len * 0.5 }
            }
        }
        return scores.entries
            .filter { (el, _) ->
                el.select("a").none { it.text().contains("Odpoveda", ignoreCase = true) }
            }
            .maxByOrNull { (el, score) ->
                val textLen = el.text().length.coerceAtLeast(1)
                val linkLen = el.select("a").sumOf { it.text().length }
                score * (1.0 - linkLen.toDouble() / textLen)
            }
            ?.key
            ?.takeIf { it.text().length > 200 }
    }

    // ---------------------------------------------------------------- body cleanup

    private val BLOCK_TAGS = setOf(
        "p", "div", "table", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "figure", "pre", "hr", "center",
    )

    private val SAFE: Safelist = Safelist()
        .addTags(
            "a", "b", "strong", "i", "em", "u", "s", "sub", "sup", "br", "p", "h3", "h4",
            "blockquote", "ul", "ol", "li", "img", "figure", "figcaption", "table", "thead",
            "tbody", "tr", "td", "th", "pre", "code", "hr", "div", "span", "small",
        )
        .addAttributes("a", "href", "class")
        .addAttributes("img", "src", "alt")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan")
        .addAttributes("div", "class")
        .addAttributes("span", "class")
        .addAttributes("figure", "class")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https")
        .preserveRelativeLinks(false)

    fun cleanBody(els: List<Element>, baseUrl: String, perex: String?): String {
        if (els.isEmpty()) return ""
        val shell = Document.createShell(baseUrl)
        val root = shell.body().appendElement("div")
        for (e in els) root.appendChild(e.clone())

        root.select("script, style, noscript, form, input, button, select, textarea, object, embed, link, meta").remove()

        // Embedded players need JavaScript; show a tappable thumbnail/link instead.
        for (f in root.select("iframe")) {
            val src = f.absUrl("src").ifEmpty { f.attr("src") }
            val yt = YOUTUBE.find(src)?.groupValues?.get(1)
            val card = Element("a").addClass("video")
            if (yt != null) {
                card.attr("href", "https://www.youtube.com/watch?v=$yt")
                card.appendElement("img").attr("src", "https://i.ytimg.com/vi/$yt/hqdefault.jpg").attr("alt", "")
                card.appendElement("span").addClass("play").text("▶")
            } else {
                card.attr("href", forceHttps(src)).addClass("embed").text("Otvoriť vložený obsah")
            }
            f.replaceWith(card)
        }

        // Absolute, https-first URLs.
        for (img in root.select("img[src]")) {
            val src = img.absUrl("src")
            if (isDecoration(img, src)) img.remove() else img.attr("src", forceHttps(src))
        }
        for (a in root.select("a[href]")) a.attr("href", forceHttps(a.absUrl("href").ifEmpty { a.attr("href") }))

        // Layout tables that only hold a picture and its caption become <figure>.
        for (t in root.select("table")) {
            if (t.parent() == null) continue
            val imgs = t.select("img")
            if (imgs.isEmpty()) continue
            val caption = clean(t.text())
            if (caption.length > 400) continue
            val fig = Element("figure")
            for (img in imgs) {
                val link = img.parent()?.takeIf { it.tagName() == "a" }
                fig.appendChild(if (link != null) link.clone() else img.clone())
            }
            if (caption.isNotEmpty()) fig.appendElement("figcaption").text(caption)
            t.replaceWith(fig)
        }
        for (t in root.select("table")) {
            if (t.parent()?.hasClass("table-wrap") != true) t.wrap("<div class=\"table-wrap\"></div>")
        }

        // Presentational wrappers carry no meaning in our own layout.
        root.select("font, center, span:not(.play)").forEach { if (it !== root) it.unwrap() }
        root.select("h1, h2").forEach { it.tagName("h3") }
        root.select("h5, h6").forEach { it.tagName("h4") }

        normaliseParagraphs(root)

        // The perex is shown separately; don't repeat it as the first paragraph.
        if (perex != null) {
            root.children().firstOrNull()?.let { first ->
                if (clean(first.text()) == perex) first.remove()
            }
        }
        root.select("p").forEach { p ->
            trimBreaks(p)
            if (!p.hasText() && p.select("img, a.video").isEmpty()) p.remove()
        }

        return Jsoup.clean(root.html(), baseUrl, SAFE)
    }

    private fun isDecoration(img: Element, src: String): Boolean {
        val w = img.attr("width").toIntOrNull()
        val h = img.attr("height").toIntOrNull()
        if ((w != null && w < 24) || (h != null && h < 24)) return true
        return src.isEmpty() || src.contains("/images/design/") || src.endsWith("/x.gif") ||
            src.endsWith("/blt.gif") || src.contains("spacer")
    }

    /** Converts `text<br><br>text` runs into real paragraphs, recursing into plain divs. */
    private fun normaliseParagraphs(root: Element) {
        val out = mutableListOf<Node>()
        var cur = Element("p")
        fun flush() {
            if (cur.hasText() || cur.select("img, a.video").isNotEmpty()) out.add(cur)
            cur = Element("p")
        }
        val nodes = root.childNodes().toList()
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            when {
                n is Element && n.tagName() == "br" -> {
                    var j = i + 1
                    while (j < nodes.size && nodes[j].isBlankText()) j++
                    if (j < nodes.size && nodes[j].isBr()) {
                        flush()
                        while (j < nodes.size && (nodes[j].isBr() || nodes[j].isBlankText())) j++
                        i = j
                        continue
                    }
                    if (cur.childNodeSize() > 0) cur.appendChild(n)
                }
                n is Element && n.tagName() == "div" && !n.hasClass("table-wrap") -> {
                    flush()
                    normaliseParagraphs(n)
                    out.addAll(n.childNodes().toList())
                }
                n is Element && (n.tagName() in BLOCK_TAGS || n.tagName() == "figure") -> {
                    flush()
                    if (n.tagName() == "p" && hasDoubleBreak(n)) normaliseParagraphs(n)
                    out.add(n)
                }
                n is TextNode && n.isBlank && cur.childNodeSize() == 0 -> Unit
                else -> cur.appendChild(n)
            }
            i++
        }
        flush()
        root.empty()
        out.forEach { root.appendChild(it) }
        // A <p> that received nested paragraphs must not contain <p> itself.
        if (root.tagName() == "p" && root.children().any { it.tagName() == "p" }) root.tagName("div")
    }

    private fun hasDoubleBreak(el: Element): Boolean {
        val kids = el.childNodes().filterNot { it.isBlankText() }
        return kids.zipWithNext().any { (a, b) -> a.isBr() && b.isBr() }
    }

    private fun Node.isBr() = this is Element && tagName() == "br"
    private fun Node.isBlankText() = this is TextNode && isBlank

    private fun trimBreaks(p: Element) {
        while (p.childNodeSize() > 0 && (p.childNode(0).isBr() || p.childNode(0).isBlankText())) p.childNode(0).remove()
        while (p.childNodeSize() > 0) {
            val last = p.childNode(p.childNodeSize() - 1)
            if (last.isBr() || last.isBlankText()) last.remove() else break
        }
    }

    // ---------------------------------------------------------------- helpers

    fun forceHttps(url: String): String =
        if (url.startsWith("http://www.dsl.sk") || url.startsWith("http://dsl.sk")) "https://" + url.removePrefix("http://") else url

    private fun clean(s: String) = s.replace(WS, " ").trim()
}
