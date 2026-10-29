package sk.freelancegroup.dslsk

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AbsListView
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.IOException
import java.net.UnknownHostException

/**
 * The news list: newest first, older pages load automatically while scrolling.
 * The star in the top bar switches to the bookmarked articles only.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var bookmarks: Bookmarks
    private lateinit var toolbar: Toolbar
    private lateinit var tagline: TextView
    private lateinit var bookmarksEmpty: View
    private var showingBookmarks = false
    private var backCallback: Any? = null // OnBackInvokedCallback on Android 13+
    private lateinit var adapter: FeedAdapter
    private lateinit var list: ListView
    private lateinit var topBar: View
    private lateinit var refreshing: ProgressBar
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private lateinit var footer: View
    private lateinit var footerProgress: ProgressBar
    private lateinit var footerText: TextView
    private var footerFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        bookmarks = Bookmarks(this)

        topBar = findViewById(R.id.topBar)
        list = findViewById(R.id.list)
        refreshing = findViewById(R.id.refreshing)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        bookmarksEmpty = findViewById(R.id.bookmarksEmpty)
        tagline = findViewById(R.id.tagline)

        findViewById<TextView>(R.id.brand).typeface = Fonts.brand(this)
        tagline.typeface = Fonts.tagline(this)

        toolbar = findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_bookmarks -> { setBookmarkMode(!showingBookmarks); true }
                R.id.action_refresh -> { refresh(); true }
                R.id.action_open_web -> { openExternal(DslParser.HOME); true }
                else -> false
            }
        }
        // Tapping the header jumps back to the top, like most news apps.
        toolbar.setOnClickListener { list.setSelection(0) }

        footer = layoutInflater.inflate(R.layout.list_footer, list, false)
        footerProgress = footer.findViewById(R.id.footerProgress)
        footerText = footer.findViewById(R.id.footerText)
        footer.setOnClickListener { if (footerFailed) loadMore() }
        list.addFooterView(footer, null, true)

        adapter = FeedAdapter(this, FeedRepository.items, prefs, bookmarks)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (position < adapter.count) openArticle(adapter.getItem(position))
        }
        // Long press = quick bookmark / un-bookmark without opening the article.
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position >= adapter.count) return@setOnItemLongClickListener false
            val starred = bookmarks.toggle(adapter.getItem(position))
            Toast.makeText(this, if (starred) R.string.bookmark_added else R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
            if (showingBookmarks) showBookmarks() else adapter.refreshMarks()
            true
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (!showingBookmarks && total > 1 && first + visible >= total - 4 && !footerFailed) loadMore()
            }
        })

        findViewById<Button>(R.id.retry).setOnClickListener { refresh() }
        findViewById<Button>(R.id.openWeb).setOnClickListener { openExternal(DslParser.HOME) }

        edgeToEdge { top, bottom ->
            topBar.setPadding(0, top, 0, 0)
            list.setPadding(0, 0, 0, bottom)
            errorBox.setPadding(errorBox.paddingLeft, errorBox.paddingTop, errorBox.paddingRight, bottom)
        }

        if (savedInstanceState?.getBoolean(STATE_BOOKMARKS) == true) setBookmarkMode(true)
        if (FeedRepository.items.isEmpty()) refresh() else render()
    }

    override fun onResume() {
        super.onResume()
        // An article may have been (un)starred meanwhile.
        if (showingBookmarks) showBookmarks() else adapter.refreshMarks()
        val stale = System.currentTimeMillis() - FeedRepository.loadedAt > STALE_AFTER_MS
        // Auto-refresh an old list, but never yank the reader back up from deep in the list.
        if (!showingBookmarks && FeedRepository.items.isNotEmpty() && stale && !FeedRepository.loading &&
            list.firstVisiblePosition < 3
        ) refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BOOKMARKS, showingBookmarks)
    }

    private fun setBookmarkMode(on: Boolean) {
        showingBookmarks = on
        toolbar.menu.findItem(R.id.action_bookmarks)?.apply {
            setIcon(if (on) R.drawable.ic_star else R.drawable.ic_star_border)
            title = getString(if (on) R.string.show_all else R.string.show_bookmarks)
        }
        toolbar.menu.findItem(R.id.action_refresh)?.isVisible = !on
        updateBackHandling()
        if (on) {
            errorBox.visibility = View.GONE
            progress.visibility = View.GONE
            showBookmarks()
        } else {
            tagline.setText(R.string.tagline)
            bookmarksEmpty.visibility = View.GONE
            adapter.items = FeedRepository.items
            adapter.refreshMarks()
            render()
            if (FeedRepository.items.isEmpty() && !FeedRepository.loading) refresh()
        }
        list.setSelection(0)
    }

    private fun showBookmarks() {
        val saved = bookmarks.all()
        adapter.items = saved
        adapter.refreshMarks()
        tagline.text = getString(R.string.bookmarks) + " · " + bookmarksLabel(saved.size)
        bookmarksEmpty.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (saved.isEmpty()) View.INVISIBLE else View.VISIBLE
        footerProgress.visibility = View.GONE
        footerText.text = ""
    }

    /** Back leaves the bookmarks view first, then the app. */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (showingBookmarks && backCallback == null) {
            val cb = OnBackInvokedCallback { setBookmarkMode(false) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!showingBookmarks) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    @Deprecated("Used below Android 13; newer versions use updateBackHandling().")
    override fun onBackPressed() {
        if (showingBookmarks) setBookmarkMode(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun refresh() {
        val firstLoad = FeedRepository.items.isEmpty()
        errorBox.visibility = View.GONE
        progress.visibility = if (firstLoad) View.VISIBLE else View.GONE
        refreshing.visibility = if (firstLoad) View.GONE else View.VISIBLE
        footerFailed = false
        FeedRepository.refresh { result ->
            if (isDestroyed) return@refresh
            progress.visibility = View.GONE
            refreshing.visibility = View.GONE
            if (showingBookmarks) return@refresh
            result.onSuccess { count ->
                if (count == 0) showError(getString(R.string.error_empty)) else list.setSelection(0)
            }.onFailure { e ->
                if (FeedRepository.items.isEmpty()) showError(describe(e))
                else android.widget.Toast.makeText(this, describe(e), android.widget.Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    private fun loadMore() {
        val started = FeedRepository.loadMore { result ->
            if (isDestroyed) return@loadMore
            footerFailed = result.isFailure
            render()
        }
        if (started) {
            footerFailed = false
            render()
        }
    }

    private fun render() {
        if (showingBookmarks) return
        adapter.notifyDataSetChanged()
        val hasItems = FeedRepository.items.isNotEmpty()
        list.visibility = if (hasItems) View.VISIBLE else View.INVISIBLE
        if (hasItems) errorBox.visibility = View.GONE

        when {
            FeedRepository.loading && FeedRepository.nextUrl != null && hasItems && refreshing.visibility != View.VISIBLE -> {
                footerProgress.visibility = View.VISIBLE
                footerText.setText(R.string.loading_more)
            }
            footerFailed -> {
                footerProgress.visibility = View.GONE
                footerText.setText(R.string.load_more_failed)
            }
            FeedRepository.nextUrl == null && hasItems -> {
                footerProgress.visibility = View.GONE
                footerText.setText(R.string.end_of_list)
            }
            else -> {
                footerProgress.visibility = View.VISIBLE
                footerText.setText(R.string.loading_more)
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorBox.visibility = View.VISIBLE
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> getString(R.string.error_offline)
        is IOException -> getString(R.string.error_generic) + "\n(" + (e.message ?: e.javaClass.simpleName) + ")"
        else -> getString(R.string.error_generic)
    }

    private fun openArticle(item: FeedItem) {
        prefs.markRead(item.id)
        startActivity(
            Intent(this, ArticleActivity::class.java)
                .putExtra(ArticleActivity.EXTRA_URL, item.url)
                .putExtra(ArticleActivity.EXTRA_TITLE, item.title)
                .putExtra(ArticleActivity.EXTRA_COMMENTS, item.comments ?: -1)
                .putExtra(ArticleActivity.EXTRA_PEREX, item.perex)
                .putExtra(ArticleActivity.EXTRA_PUBLISHED, item.publishedAt ?: -1L)
        )
    }

    companion object {
        private const val STALE_AFTER_MS = 30L * 60 * 1000
        private const val STATE_BOOKMARKS = "bookmarks"
    }
}
