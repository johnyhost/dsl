package sk.freelancegroup.dslsk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AbsListView
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toolbar
import java.io.IOException
import java.net.UnknownHostException

/** The news list: newest first, older pages load automatically while scrolling. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
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

        topBar = findViewById(R.id.topBar)
        list = findViewById(R.id.list)
        refreshing = findViewById(R.id.refreshing)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)

        findViewById<TextView>(R.id.brand).typeface = Fonts.brand(this)
        findViewById<TextView>(R.id.tagline).typeface = Fonts.tagline(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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

        adapter = FeedAdapter(this, FeedRepository.items, prefs)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (position < adapter.count) openArticle(adapter.getItem(position))
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (total > 1 && first + visible >= total - 4 && !footerFailed) loadMore()
            }
        })

        findViewById<Button>(R.id.retry).setOnClickListener { refresh() }
        findViewById<Button>(R.id.openWeb).setOnClickListener { openExternal(DslParser.HOME) }

        edgeToEdge { top, bottom ->
            topBar.setPadding(0, top, 0, 0)
            list.setPadding(0, 0, 0, bottom)
            errorBox.setPadding(errorBox.paddingLeft, errorBox.paddingTop, errorBox.paddingRight, bottom)
        }

        if (FeedRepository.items.isEmpty()) refresh() else render()
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshReadState()
        val stale = System.currentTimeMillis() - FeedRepository.loadedAt > STALE_AFTER_MS
        // Auto-refresh an old list, but never yank the reader back up from deep in the list.
        if (FeedRepository.items.isNotEmpty() && stale && !FeedRepository.loading && list.firstVisiblePosition < 3) refresh()
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
        )
    }

    companion object {
        private const val STALE_AFTER_MS = 30L * 60 * 1000
    }
}
