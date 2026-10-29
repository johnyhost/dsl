package sk.freelancegroup.dslsk

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import java.util.concurrent.Executors

/** Background work without AndroidX: run on a small pool, deliver on the main thread. */
object Async {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { done(result) }
        }
    }
}

/** Small persisted settings: article text size and which articles were opened. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dslsk", Context.MODE_PRIVATE)

    var textZoom: Int
        get() = sp.getInt("textZoom", 100)
        set(value) = sp.edit().putInt("textZoom", value.coerceIn(MIN_ZOOM, MAX_ZOOM)).apply()

    fun readIds(): Set<Int> =
        sp.getString("readIds", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }.toSet()

    fun markRead(id: Int) {
        val ids = sp.getString("readIds", "").orEmpty().split(',').filter { it.isNotEmpty() && it != id.toString() }
        val kept = (ids + id.toString()).takeLast(600)
        sp.edit().putString("readIds", kept.joinToString(",")).apply()
    }

    companion object {
        const val MIN_ZOOM = 70
        const val MAX_ZOOM = 200
        const val ZOOM_STEP = 10
    }
}

object Fonts {
    private var demibold: Typeface? = null
    private var regular: Typeface? = null

    fun brand(context: Context): Typeface =
        demibold ?: Typeface.createFromAsset(context.assets, "RawengulkDemibold.ttf").also { demibold = it }

    fun tagline(context: Context): Typeface =
        regular ?: Typeface.createFromAsset(context.assets, "RawengulkRegular.ttf").also { regular = it }
}

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * Draw behind the system bars (mandatory when targeting Android 15) and hand the
 * bar sizes to the caller so it can pad the top bar and the scrolling content.
 */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge(onInsets: (top: Int, bottom: Int) -> Unit) {
    val light = !isNightMode()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (light) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }
    val root = findViewById<View>(android.R.id.content)
    root.setOnApplyWindowInsetsListener { _, insets ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            onInsets(bars.top, bars.bottom)
        } else {
            onInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
        }
        insets
    }
    root.requestApplyInsets()
}

/** Opens a link in the user's browser (or another app that handles it). */
fun Context.openExternal(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
    }
}

fun Context.shareLink(title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    startActivity(Intent.createChooser(send, getString(R.string.share)))
}

fun Context.articlePalette(): ArticleHtml.Palette {
    fun hex(res: Int) = String.format("#%06X", 0xFFFFFF and getColor(res))
    return ArticleHtml.Palette(
        bg = hex(R.color.bg), fg = hex(R.color.fg), muted = hex(R.color.muted),
        accent = hex(R.color.accent), rule = hex(R.color.rule), chip = hex(R.color.chip),
    )
}
