package sk.freelancegroup.dslsk

/**
 * Holds the loaded news list for the lifetime of the process, so rotating the
 * phone or coming back from an article never reloads or loses the scroll position.
 */
object FeedRepository {

    val items = ArrayList<FeedItem>()
    private val ids = HashSet<Int>()

    var nextUrl: String? = null
        private set
    var loading = false
        private set
    var loadedAt = 0L
        private set

    private var generation = 0

    /** Reloads the home page and replaces the list. */
    fun refresh(done: (Result<Int>) -> Unit) {
        generation++
        load(DslParser.HOME, replace = true, done)
    }

    /** Appends the next older page ("Staršie správy"); no-op if already loading or at the end. */
    fun loadMore(done: (Result<Int>) -> Unit): Boolean {
        val url = nextUrl ?: return false
        if (loading) return false
        load(url, replace = false, done)
        return true
    }

    private fun load(url: String, replace: Boolean, done: (Result<Int>) -> Unit) {
        loading = true
        val gen = generation
        Async.run({ DslParser.parseFeed(Http.getDocument(url)) }) { result ->
            if (gen != generation) return@run // superseded by a newer refresh
            loading = false
            result.onSuccess { page ->
                if (replace) {
                    items.clear()
                    ids.clear()
                    loadedAt = System.currentTimeMillis()
                }
                val fresh = page.items.filter { ids.add(it.id) }
                items.addAll(fresh)
                nextUrl = page.nextUrl?.takeIf { it != url }
            }
            done(result.map { it.items.size })
        }
    }
}
