package momoi.mod.qqpro.hook.translate

import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Translation client over the MyMemory API (verified reachable from CN networks; the previous
 * ai-life.xyz endpoint — a Lingva front — is dead, walled behind a Cloudflare JS challenge):
 *   GET https://api.mymemory.translated.net/get?q=<text>&langpair=Autodetect|<target>
 * The source language is auto-detected ("Autodetect"); <target> is the 2-letter output code. The
 * JSON response is `{"responseData":{"translatedText":"…"},"responseStatus":200, …}`.
 *
 * Quirk: when the free anonymous quota (≈5000 chars/day per IP) is exhausted, MyMemory returns
 * HTTP 200 with the warning text INSIDE translatedText (and/or a "MYMEMORY WARNING" prefix) —
 * treat any response whose text doesn't parse as a clean translation as a failure.
 *
 * [translate] runs on a background thread and calls back there; callers that touch views must hop
 * to the UI thread themselves (runOnUi).
 */
object Translator {
    /**
     * Target languages offered in the 查看语言 / 发送语言 pickers, as (api-code, display-name). The API
     * source is always auto-detected, so these are output languages only (no "auto" entry).
     */
    val TARGETS: List<Pair<String, String>> = listOf(
        "zh" to "中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "ru" to "Русский",
        "pt" to "Português",
        "ar" to "العربية",
    )

    /** Display name for a stored language code (falls back to the raw code). */
    fun nameOf(code: String): String = TARGETS.firstOrNull { it.first == code }?.second ?: code

    private const val BASE = "https://api.mymemory.translated.net/get"

    /**
     * Translate [text] into [target]. [callback] receives the translated string, or null on any
     * failure (network error / blank input / quota exhausted / unparsable response). Invoked on a
     * background thread.
     */
    fun translate(text: String, target: String, callback: (String?) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || target.isBlank()) { callback(null); return }
        val q = runCatching { URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20") }
            .getOrElse { callback(null); return }
        val url = "$BASE?q=$q&langpair=Autodetect|$target"
        thread {
            var conn: HttpURLConnection? = null
            val resp = try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    val out = ByteArrayOutputStream()
                    conn.inputStream.use { it.copyTo(out) }
                    String(out.toByteArray(), Charsets.UTF_8)
                } else {
                    Utils.log("Translator: HTTP $code for $target")
                    null
                }
            } catch (e: Exception) {
                Utils.log("Translator: request failed: ${e.message}")
                null
            } finally {
                conn?.disconnect()
            }
            val result = resp
                ?.let { runCatching { Json(it).json("responseData")?.str("translatedText") }.getOrNull() }
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                // Quota-exhausted responses carry the warning inside translatedText.
                ?.takeUnless { it.contains("MYMEMORY WARNING", ignoreCase = true) }
                // Some TM matches echo the source text unchanged — that's still a valid result.
            if (resp != null && result == null) Utils.log("Translator: no translation in: ${resp.take(160)}")
            callback(result)
        }
    }
}
