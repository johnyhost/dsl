package sk.freelancegroup.dslsk

import org.jsoup.nodes.Entities
import java.time.LocalDate

/** Wraps a parsed [Article] in a self-contained, phone-friendly HTML page (pure JVM, no Android). */
object ArticleHtml {

    /** CSS colours, taken from the app's light/dark colour resources. */
    data class Palette(
        val bg: String, val fg: String, val muted: String,
        val accent: String, val rule: String, val chip: String,
    )

    private fun esc(s: String) = Entities.escape(s)

    /**
     * The discussion as a collapsed <details> block (expands without JavaScript).
     * [listedComments] is the count from the news list, used only if parsing found none.
     */
    private fun discussion(article: Article, listedComments: Int?): String {
        val add = article.addCommentUrl ?: article.id?.let(DslParser::discussionUrl)
        val addLink = add?.let { "<a class=\"discuss\" href=\"${esc(it)}\">Pridať komentár na webe <span>→</span></a>" }.orEmpty()
        if (article.comments.isEmpty()) {
            // Either no comments yet, or a layout we couldn't read: then link to the web.
            val n = listedComments ?: 0
            val id = article.id ?: return addLink
            return if (n > 0) "<a class=\"discuss\" href=\"${esc(DslParser.discussionUrl(id))}\">Diskusia · ${commentsLabel(n)} <span>→</span></a>"
            else addLink
        }
        val year = ".${LocalDate.now(DslParser.ZONE).year}"
        val entries = article.comments.joinToString("\n") { c ->
            val indent = minOf(c.depth, 5) * 14
            val classes = buildString {
                append("c")
                if (c.depth > 0) append(" reply")
                if ((c.score ?: 0.0) <= -5.0) append(" low")
            }
            val title = c.title.takeIf { it.isNotEmpty() && !it.startsWith("Re:", ignoreCase = true) }
                ?.let { "<div class=\"c-title\">${esc(it)}</div>" }.orEmpty()
            val meta = listOfNotNull(
                c.author?.let { "<b>${esc(it)}</b>" },
                c.date?.replace(year, ".")?.let(::esc),
                c.score?.takeIf { it != 0.0 }?.let { sc ->
                    val txt = if (sc % 1.0 == 0.0) "%+d".format(sc.toInt()) else "%+.1f".format(sc)
                    "<span class=\"score ${if (sc > 0) "pos" else "neg"}\">$txt</span>"
                },
            ).joinToString(" · ")
            "<div class=\"$classes\" style=\"margin-left:${indent}px\">$title<div class=\"c-meta\">$meta</div>" +
                "<div class=\"c-body\">${c.bodyHtml}</div></div>"
        }
        return "<details class=\"disc\"><summary>Diskusia · ${commentsLabel(article.comments.size)}<span class=\"chev\"></span></summary>" +
            "<div class=\"thread\">$entries</div>$addLink</details>"
    }

    fun render(article: Article, comments: Int?, palette: Palette): String {
        val (bg, fg, muted, accent, rule, chip) = palette

        val tags = if (article.tags.isEmpty()) "" else
            article.tags.joinToString("", "<div class=\"tags\">", "</div>") { "<span>${esc(it)}</span>" }
        val byline = article.byline?.let { "<div class=\"meta\">${esc(it)}</div>" }.orEmpty()
        val perex = article.perex?.let { "<p class=\"perex\">${esc(it)}</p>" }.orEmpty()
        val discussion = discussion(article, comments)

        return """
<!doctype html>
<html lang="sk"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  html { -webkit-text-size-adjust: none; }
  body { margin: 0; padding: 20px 20px 36px; background: $bg; color: $fg;
         font-family: sans-serif; font-size: 17px; line-height: 1.62;
         overflow-wrap: break-word; }
  .meta { color: $muted; font-size: 13px; letter-spacing: .02em; }
  h1 { font-size: 25px; line-height: 1.25; margin: 6px 0 12px; font-weight: 700; }
  .tags { margin: 0 0 14px; }
  .tags span { display: inline-block; font-size: 12px; padding: 3px 10px; margin: 0 6px 6px 0;
               border-radius: 12px; background: $chip; color: $muted; }
  .perex { font-weight: 600; font-size: 18px; line-height: 1.55; margin: 0 0 18px; }
  .body p { margin: 0 0 1em; }
  .lead > p:first-child { font-weight: 600; font-size: 18px; line-height: 1.55; }
  small { display: block; color: $muted; font-size: 13px; line-height: 1.45; text-align: center; margin-top: 6px; }
  .body p > b:only-child, .body h3, .body h4 { display: block; font-size: 19px; line-height: 1.3; margin-top: 1.4em; }
  a { color: $accent; }
  img { max-width: 100%; height: auto; display: block; margin: 0 auto; border-radius: 8px; }
  figure { margin: 22px 0; }
  figcaption { color: $muted; font-size: 13px; line-height: 1.45; margin-top: 8px; text-align: center; }
  blockquote { margin: 1em 0; padding: 2px 0 2px 14px; border-left: 3px solid $accent; color: $muted; }
  .table-wrap { overflow-x: auto; margin: 1em 0; }
  table { border-collapse: collapse; font-size: 15px; }
  td, th { border: 1px solid $rule; padding: 5px 8px; vertical-align: top; }
  ul, ol { padding-left: 1.3em; }
  pre, code { font-size: 14px; white-space: pre-wrap; }
  hr { border: 0; border-top: 1px solid $rule; margin: 24px 0; }
  a.video { position: relative; display: block; margin: 18px 0 6px; }
  a.video img { width: 100%; }
  a.video + br { display: none; }
  a.video .play { position: absolute; left: 50%; top: 50%; width: 64px; height: 64px; margin: -32px 0 0 -32px;
                  border-radius: 50%; background: rgba(0,0,0,.65); color: #fff; font-size: 26px;
                  line-height: 64px; text-align: center; }
  a.embed { display: block; padding: 14px 16px; border-radius: 12px; background: $chip; text-decoration: none; }
  a.discuss { display: block; margin-top: 30px; padding: 15px 18px; border-radius: 14px; background: $chip;
              color: $fg; font-weight: 600; text-decoration: none; }
  a.discuss span { float: right; color: $accent; }
  details.disc { margin-top: 30px; }
  details.disc > summary { list-style: none; display: block; padding: 15px 18px; border-radius: 14px;
                           background: $chip; font-weight: 600; }
  details.disc > summary::-webkit-details-marker { display: none; }
  details.disc > summary .chev::after { content: "▾"; float: right; color: $accent; }
  details.disc[open] > summary .chev::after { content: "▴"; }
  details.disc a.discuss { margin-top: 18px; }
  .thread { margin-top: 8px; }
  .c { padding: 12px 0 10px; border-bottom: 1px solid $rule; }
  .c.reply { padding-left: 12px; border-left: 2px solid $rule; }
  .c.low { opacity: .55; }
  .c-title { font-weight: 600; font-size: 15px; line-height: 1.35; }
  .c-meta { color: $muted; font-size: 12.5px; margin: 2px 0 6px; }
  .c-meta b { color: $fg; font-weight: 600; }
  .score.pos { color: $accent; font-weight: 600; }
  .c-body { font-size: 15.5px; line-height: 1.5; }
  .c-body p { margin: 0 0 .6em; }
  .c-body p:last-child { margin-bottom: 0; }
  .c-body img { display: inline; margin: 0; border-radius: 0; }
</style>
</head><body>
$byline
<h1>${esc(article.title)}</h1>
$tags
$perex
<div class="body${if (article.leadInBody) " lead" else ""}">${article.bodyHtml}</div>
$discussion
</body></html>
"""
    }
}
