package sk.freelancegroup.dslsk

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

class FeedAdapter(
    private val context: Context,
    /** Either the live news list or the bookmarks; swap it and call notifyDataSetChanged(). */
    var items: List<FeedItem>,
    private val prefs: Prefs,
    private val bookmarks: Bookmarks,
) : BaseAdapter() {

    private var readIds: Set<Int> = prefs.readIds()
    private var starredIds: Set<Int> = bookmarks.ids()

    /** Re-reads which articles are opened / bookmarked and redraws. */
    fun refreshMarks() {
        readIds = prefs.readIds()
        starredIds = bookmarks.ids()
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): FeedItem = items[position]
    override fun getItemId(position: Int) = items[position].id.toLong()
    override fun hasStableIds() = true

    private class Holder(view: View) {
        val day: TextView = view.findViewById(R.id.day)
        val title: TextView = view.findViewById(R.id.title)
        val perex: TextView = view.findViewById(R.id.perex)
        val meta: TextView = view.findViewById(R.id.meta)
        val divider: View = view.findViewById(R.id.divider)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_article, parent, false).also {
            it.tag = Holder(it)
        }
        val h = view.tag as Holder
        val item = items[position]

        val day = dayOf(item)
        val prevDay = items.getOrNull(position - 1)?.let(::dayOf)
        val nextDay = items.getOrNull(position + 1)?.let(::dayOf)
        val startsDay = day != null && day != prevDay
        h.day.visibility = if (startsDay) View.VISIBLE else View.GONE
        if (startsDay) h.day.text = dayHeading(day!!)
        h.divider.visibility = if (position == items.lastIndex || (nextDay != null && nextDay != day)) View.GONE else View.VISIBLE

        h.title.text = item.title
        h.title.setTextColor(context.getColor(if (item.id in readIds) R.color.read else R.color.fg))

        h.perex.visibility = if (item.perex.isNullOrBlank()) View.GONE else View.VISIBLE
        h.perex.text = item.perex

        val meta = SpannableStringBuilder()
        if (item.id in starredIds) {
            meta.append("★", ForegroundColorSpan(context.getColor(R.color.accent)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        listOfNotNull(
            item.publishedAt?.let(::timeLabel)?.takeIf { it.isNotEmpty() },
            item.comments?.let { commentsLabel(it) },
        ).forEach { part ->
            if (meta.isNotEmpty()) meta.append(if (meta.length == 1 && item.id in starredIds) "  " else "  ·  ")
            meta.append(part)
        }
        h.meta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
        h.meta.text = meta
        return view
    }

    private fun dayOf(item: FeedItem): LocalDate? =
        item.publishedAt?.let { Instant.ofEpochMilli(it).atZone(DslParser.ZONE).toLocalDate() }

    private fun dayHeading(day: LocalDate): String {
        val today = LocalDate.now(DslParser.ZONE)
        return when (day) {
            today -> context.getString(R.string.today)
            today.minusDays(1) -> context.getString(R.string.yesterday)
            else -> {
                val name = DAY_NAMES[day.dayOfWeek.value - 1]
                if (day.year == today.year) "$name ${day.dayOfMonth}. ${day.monthValue}."
                else "$name ${day.dayOfMonth}. ${day.monthValue}. ${day.year}"
            }
        }
    }

    private fun timeLabel(ms: Long): String {
        val t: ZonedDateTime = Instant.ofEpochMilli(ms).atZone(DslParser.ZONE)
        // Dates shown without a time on the site are stored as noon; don't pretend we know the hour.
        return if (t.hour == 12 && t.minute == 0) "" else "%d:%02d".format(t.hour, t.minute)
    }

    companion object {
        private val DAY_NAMES = listOf("Pondelok", "Utorok", "Streda", "Štvrtok", "Piatok", "Sobota", "Nedeľa")
    }
}
