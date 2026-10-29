package sk.freelancegroup.dslsk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Starred articles, stored on the phone with everything needed to list them,
 * so they stay available long after they drop off the news pages.
 * (Included in Android's automatic backup, like the other app settings.)
 */
class Bookmarks(context: Context) {

    private val sp = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    private data class Entry(val item: FeedItem, val savedAt: Long)

    private fun load(): List<Entry> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                FeedItem(
                    id = o.getInt("id"),
                    title = o.getString("title"),
                    url = o.getString("url"),
                    perex = o.optString("perex").takeIf { it.isNotEmpty() },
                    publishedAt = if (o.has("publishedAt")) o.getLong("publishedAt") else null,
                    comments = if (o.has("comments")) o.getInt("comments") else null,
                ),
                o.optLong("savedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { (item, savedAt) ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("url", item.url)
                item.perex?.let { put("perex", it) }
                item.publishedAt?.let { put("publishedAt", it) }
                item.comments?.let { put("comments", it) }
                put("savedAt", savedAt)
            })
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    /** Newest article first (same order and day grouping as the news list). */
    fun all(): List<FeedItem> =
        load().sortedByDescending { it.item.publishedAt ?: it.savedAt }.map { it.item }

    fun ids(): Set<Int> = load().map { it.item.id }.toSet()

    fun contains(id: Int): Boolean = load().any { it.item.id == id }

    fun add(item: FeedItem) {
        val rest = load().filter { it.item.id != item.id }
        save(rest + Entry(item, System.currentTimeMillis()))
    }

    fun remove(id: Int) = save(load().filter { it.item.id != id })

    /** Returns true if the article is bookmarked afterwards. */
    fun toggle(item: FeedItem): Boolean =
        if (contains(item.id)) { remove(item.id); false } else { add(item); true }

    private companion object {
        const val KEY = "items"
    }
}
