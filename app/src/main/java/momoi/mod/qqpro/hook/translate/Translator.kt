package momoi.mod.qqpro.hook.translate

import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Translation client.
 *
 * Built-in backend: MyMemory (verified reachable from CN networks; the previous ai-life.xyz
 * endpoint — a Lingva front — is dead behind a Cloudflare JS challenge):
 *   GET https://api.mymemory.translated.net/get?q=<text>&langpair=Autodetect|<target>
 * The source language is auto-detected; <target> is the 2-letter output code. Response:
 * `{"responseData":{"translatedText":"…","detectedLanguage":"zh-CN"}, …}`.
 *
 * Quirks handled:
 *  - Same language pair (e.g. detecting zh when translating INTO zh): MyMemory answers HTTP 200
 *    with `translatedText: null`. That's not an error — the text already IS in the target
 *    language — so we return the original text instead of failing.
 *  - Quota exhausted (≈5000 chars/day/IP): HTTP 200 with the warning INSIDE translatedText.
 *
 * CUSTOM ENDPOINT: 设置 › 翻译 › 自定义翻译接口. A URL template with placeholders
 * `{q}` (URL-encoded text), `{text}` (raw text), `{to}` (target code). Sent as GET; the response
 * may be JSON (we search common fields: translation / translatedText / trans_result / text, at
 * any depth) or plain text. Empty setting = built-in MyMemory.
 *
 * [translate] runs on a background thread and calls back there; callers that touch views must
 * hop to the UI thread themselves (runOnUi).
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
        val custom = Settings.customTranslateUrl.value.trim()
        thread {
            if (custom.isNotEmpty()) {
                val result = runCatching { fetch(customUrl(custom, trimmed, target)) }
                    .getOrElse { Utils.log("Translator: custom request failed: ${it.message}"); null }
                    ?.let { parseCustom(it) }
                if (result == null) Utils.log("Translator: custom endpoint returned no translation")
                callback(result)
            } else {
                translateMymemory(trimmed, target, callback)
            }
        }
    }

    /** MyMemory backend with same-language fallback (returns the original text instead of failing). */
    private fun translateMymemory(text: String, target: String, callback: (String?) -> Unit) {
        val q = runCatching { URLEncoder.encode(text, "UTF-8").replace("+", "%20") }
            .getOrElse { callback(null); return }
        val url = "$BASE?q=$q&langpair=Autodetect|$target"
        val resp = fetch(url)
        if (resp == null) { callback(null); return }
        val json = runCatching { Json(resp) }.getOrNull()
        if (json == null) {
            Utils.log("Translator: unparsable response: ${resp.take(160)}")
            callback(null); return
        }
        val data = json.json("responseData")
        val detected = data?.str("detectedLanguage")
        val translated = data?.str("translatedText")?.trim()
        when {
            // Same language as the target (zh-CN detected while translating into zh): MyMemory
            // answers with a null translation — the text already IS in the target language.
            translated.isNullOrBlank() && detected != null && normalizeLang(detected) == normalizeLang(target) -> {
                Utils.log("Translator: source already $target, returning original")
                callback(text)
            }
            translated.isNullOrBlank() -> {
                Utils.log("Translator: no translation in: ${resp.take(160)}")
                callback(null)
            }
            // Quota-exhausted responses carry the warning inside translatedText.
            translated.contains("MYMEMORY WARNING", ignoreCase = true) -> {
                Utils.log("Translator: MyMemory quota exhausted")
                callback(null)
            }
            else -> callback(translated)
        }
    }

    /** Fill a custom-endpoint URL template: {q} encoded, {text} raw, {to} target code. */
    private fun customUrl(template: String, text: String, target: String): String {
        val q = runCatching { URLEncoder.encode(text, "UTF-8").replace("+", "%20") }.getOrElse { text }
        return template
            .replace("{q}", q)
            .replace("{text}", text)
            .replace("{to}", target)
    }

    /**
     * Extract a translation from a custom endpoint's response body: JSON with common translation
     * field names at any depth, or the body itself as plain text.
     */
    private fun parseCustom(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val found = runCatching {
                extractTranslation(if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed))
            }.getOrNull()
            if (!found.isNullOrBlank()) return found.trim()
            Utils.log("Translator: custom JSON had no known translation field")
            return null
        }
        return trimmed
    }

    /** Depth-first search for a translation string among common field names. */
    private fun extractTranslation(any: Any?): String? {
        when (any) {
            is JSONObject -> {
                for (key in listOf("translation", "translatedText", "trans_result", "result", "text", "dst")) {
                    when (val v = any.opt(key)) {
                        is String -> if (v.isNotBlank()) return v
                        is JSONObject -> extractTranslation(v)?.let { return it }
                        is JSONArray -> extractTranslation(v)?.let { return it }
                    }
                }
                any.keys().asSequence().mapNotNull { extractTranslation(any.opt(it)) }.firstOrNull()?.let { return it }
            }
            is JSONArray -> {
                (0 until any.length()).asSequence().mapNotNull { extractTranslation(any.opt(it)) }.firstOrNull()?.let { return it }
            }
        }
        return null
    }

    /** Strip the region suffix and case: "zh-CN" -> "zh". */
    private fun normalizeLang(code: String): String = code.trim().lowercase().substringBefore('-')

    /** GET [url] and return the UTF-8 body, or null on any failure. */
    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Accept", "application/json, text/*;q=0.8, */*;q=0.5")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val out = ByteArrayOutputStream()
                conn.inputStream.use { it.copyTo(out) }
                String(out.toByteArray(), Charsets.UTF_8)
            } else {
                Utils.log("Translator: HTTP $code")
                null
            }
        } catch (e: Exception) {
            Utils.log("Translator: request failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
