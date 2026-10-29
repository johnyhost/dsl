package sk.freelancegroup.dslsk

import org.jsoup.nodes.Entities

/** Wraps a parsed [Article] in a self-contained, phone-friendly HTML page (pure JVM, no Android). */
object ArticleHtml {

    /** CSS colours, taken from the app's light/dark colour resources. */
    data class Palette(
        val bg: String, val fg: String, val muted: String,
        val accent: String, val rule: String, val chip: String,
    )

    private fun esc(s: String) = Entities.escape(s)

    fun render(article: Article, comments: Int?, palette: Palette): String {
        val (bg, fg, muted, accent, rule, chip) = palette

        val tags = if (article.tags.isEmpty()) "" else
            article.tags.joinToString("", "<div class=\"tags\">", "</div>") { "<span>${esc(it)}</span>" }
        val byline = article.byline?.let { "<div class=\"meta\">${esc(it)}</div>" }.orEmpty()
        val perex = article.perex?.let { "<p class=\"perex\">${esc(it)}</p>" }.orEmpty()
        val discussion = article.id?.let { id ->
            val label = comments?.let { "Diskusia · ${commentsLabel(it)}" } ?: "Diskusia"
            "<a class=\"discuss\" href=\"${esc(DslParser.discussionUrl(id))}\">$label <span>→</span></a>"
        }.orEmpty()

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
</style>
</head><body>
$byline
<h1>${esc(article.title)}</h1>
$tags
$perex
<div class="body">${article.bodyHtml}</div>
$discussion
</body></html>
"""
    }
}
