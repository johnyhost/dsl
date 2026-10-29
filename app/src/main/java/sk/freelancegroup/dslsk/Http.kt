package sk.freelancegroup.dslsk

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Minimal page downloader with dsl.sk-friendly charset detection. */
object Http {

    // A desktop browser UA so the site serves the same layout the parser expects.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

    fun getDocument(url: String): Document {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "sk-SK,sk;q=0.9,cs;q=0.8,en;q=0.6")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code bez presmerovania")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("Server odpovedal HTTP $code")
                val bytes = conn.inputStream.use { it.readBytes() }
                val charset = charsetOf(conn.contentType, bytes)
                return Jsoup.parse(String(bytes, charset), current)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Príliš veľa presmerovaní")
    }

    /** Header charset → `<meta charset>` → valid UTF-8 → windows-1250 (old Slovak sites). */
    fun charsetOf(contentType: String?, bytes: ByteArray): Charset {
        contentType?.substringAfter("charset=", "")?.trim(' ', '"', '\'')?.takeIf { it.isNotEmpty() }
            ?.let { name -> runCatching { return Charset.forName(name) } }
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        META_CHARSET.find(head)?.groupValues?.get(1)
            ?.let { name -> runCatching { return Charset.forName(name) } }
        return if (isValidUtf8(bytes)) Charsets.UTF_8 else Charset.forName("windows-1250")
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}
