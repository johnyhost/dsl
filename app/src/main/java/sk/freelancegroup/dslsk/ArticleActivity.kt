package sk.freelancegroup.dslsk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import java.net.UnknownHostException

/** Shows one article in a clean, readable layout. */
class ArticleActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var bookmarks: Bookmarks
    private lateinit var toolbar: Toolbar
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private lateinit var url: String
    private var title: String = ""
    private var comments: Int? = null
    private var listPerex: String? = null
    private var listPublished: Long? = null
    private var article: Article? = null
    private var showingOriginal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        comments = intent.getIntExtra(EXTRA_COMMENTS, -1).takeIf { it >= 0 }
        listPerex = intent.getStringExtra(EXTRA_PEREX)
        listPublished = intent.getLongExtra(EXTRA_PUBLISHED, -1L).takeIf { it >= 0 }
        prefs = Prefs(this)
        bookmarks = Bookmarks(this)
        DslParser.articleIdOf(url)?.let(prefs::markRead)

        setContentView(R.layout.activity_article)
        web = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        val topBar = findViewById<View>(R.id.topBar)
        val content = findViewById<View>(R.id.content)

        findViewById<TextView>(R.id.brand).typeface = Fonts.brand(this)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.article)
        updateStar()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_bookmark -> { toggleBookmark(); true }
                R.id.action_smaller -> { zoom(-Prefs.ZOOM_STEP); true }
                R.id.action_larger -> { zoom(+Prefs.ZOOM_STEP); true }
                R.id.action_share -> { shareLink(title.ifEmpty { "DSL.sk" }, url); true }
                R.id.action_browser -> { openExternal(url); true }
                else -> false
            }
        }

        edgeToEdge { top, bottom ->
            topBar.setPadding(0, top, 0, 0)
            content.setPadding(0, 0, 0, bottom)
        }

        web.setBackgroundColor(getColor(R.color.bg))
        web.settings.apply {
            javaScriptEnabled = false
            textZoom = prefs.textZoom
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadsImagesAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val link = request.url.toString()
                // In fallback mode let the original page navigate within itself.
                if (showingOriginal && link.startsWith(url.substringBefore('#'))) return false
                onLinkClicked(link)
                return true
            }
        }

        findViewById<Button>(R.id.retry).setOnClickListener { load() }
        findViewById<Button>(R.id.openWeb).setOnClickListener { openExternal(url) }

        load()
    }

    private fun load() {
        errorBox.visibility = View.GONE
        progress.visibility = View.VISIBLE
        Async.run({ DslParser.parseArticle(Http.getDocument(url), url) }) { result ->
            if (isDestroyed) return@run
            progress.visibility = View.GONE
            result.onSuccess(::show).onFailure { e ->
                errorText.text = getString(if (e is UnknownHostException) R.string.error_offline else R.string.error_article)
                errorBox.visibility = View.VISIBLE
            }
        }
    }

    private fun show(article: Article) {
        this.article = article
        if (article.title.isNotEmpty()) title = article.title
        if (article.bodyTextLength < MIN_BODY_CHARS) {
            // The site layout changed in a way the parser doesn't understand:
            // still let the user read the article, just not in the clean layout.
            showingOriginal = true
            web.settings.apply {
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
            }
            Toast.makeText(this, R.string.fallback_notice, Toast.LENGTH_LONG).show()
            web.loadUrl(url)
            return
        }
        val html = ArticleHtml.render(article, comments, articlePalette())
        web.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
    }

    private fun articleId(): Int? = DslParser.articleIdOf(url)

    private fun updateStar() {
        val item = toolbar.menu.findItem(R.id.action_bookmark) ?: return
        val id = articleId()
        item.isVisible = id != null
        val starred = id != null && bookmarks.contains(id)
        item.setIcon(if (starred) R.drawable.ic_star else R.drawable.ic_star_border)
        item.title = getString(if (starred) R.string.bookmark_remove else R.string.bookmark_add)
    }

    private fun toggleBookmark() {
        val id = articleId() ?: return
        val a = article
        val item = FeedItem(
            id = id,
            title = title.ifEmpty { a?.title ?: url },
            url = DslParser.articleUrl(id),
            perex = listPerex ?: a?.summary,
            publishedAt = listPublished ?: a?.publishedAt,
            comments = a?.comments?.size?.takeIf { it > 0 } ?: comments,
        )
        val starred = bookmarks.toggle(item)
        Toast.makeText(this, if (starred) R.string.bookmark_added else R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
        updateStar()
    }

    private fun zoom(delta: Int) {
        prefs.textZoom = prefs.textZoom + delta
        web.settings.textZoom = prefs.textZoom
    }

    private fun onLinkClicked(link: String) {
        val id = DslParser.articleIdOf(link)
        val isArticle = id != null && link.contains("dsl.sk") && !link.contains("discussion") && !link.contains('#')
        if (isArticle) {
            startActivity(Intent(this, ArticleActivity::class.java).putExtra(EXTRA_URL, DslParser.articleUrl(id!!)))
        } else {
            openExternal(link)
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COMMENTS = "comments"
        const val EXTRA_PEREX = "perex"
        const val EXTRA_PUBLISHED = "published"
        private const val MIN_BODY_CHARS = 120
    }
}
