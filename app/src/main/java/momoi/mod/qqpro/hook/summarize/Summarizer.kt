package momoi.mod.qqpro.hook.summarize

import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Streaming client over the chat-summarization Cloudflare Worker:
 *   POST https://onyxai.ai-life.xyz/chat/summarize  (Server-Sent Events response)
 *
 * The body is `{messages:[{sender,text}], style, language}`; the response is a token stream of
 * `data: {"response":"<fragment>"}` lines terminated by `data: [DONE]`. [summarize] runs on a
 * background thread and delivers fragments to [Listener] there — callers that touch views must hop to
 * the UI thread themselves (runOnUi).
 */
object Summarizer {

    private const val URL = "https://onyxai.ai-life.xyz/chat/summarize"
    private const val APP_ID = "qqmax"

    /** One message to summarize (only sender + text are read by the server). */
    class Msg(val sender: String, val text: String)

    interface Listener {
        /** A streamed text fragment arrived; append it to the accumulating summary. */
        fun onChunk(fragment: String)
        /** The stream finished. [used]/[limit] are today's quota (−1 when the header was absent). */
        fun onDone(used: Int, limit: Int)
        /** The call failed. [retryable] = the same request may be retried (overloaded / server fault). */
        fun onError(message: String, retryable: Boolean)
    }

    /** Style code (Settings.summarizeStyle) → API style string. */
    private fun styleString(code: Int): String = when (code) {
        1 -> "tldr"
        2 -> "detailed"
        else -> "bullets"
    }

    /** Stable per-install id for the daily quota; generated and persisted on first use. */
    fun userId(): String {
        var id = Settings.installUuid.value
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            Settings.installUuid.value = id
        }
        return id
    }

    /**
     * Summarize [messages] (ordered oldest→newest). [style] is a Settings.summarizeStyle code and
     * [language] is a 2-letter code or "auto" (omitted from the request so the server matches the
     * conversation's own language).
     */
    fun summarize(
        messages: List<Msg>,
        style: Int,
        language: String,
        listener: Listener,
    ) {
        if (messages.isEmpty()) { listener.onError("没有可总结的消息", false); return }
        thread {
            if (Settings.aiApiUrl.value.isNotBlank() || Settings.aiApiKey.value.isNotBlank()) {
                callOpenAI(messages, style, language, listener)
                return@thread
            }
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject().apply {
                    put("messages", JSONArray().apply {
                        messages.forEach { m ->
                            put(JSONObject().apply { put("sender", m.sender); put("text", m.text) })
                        }
                    })
                    put("style", styleString(style))
                    if (language.isNotBlank() && language != "auto") put("language", langName(language))
                }.toString()

                conn = (URL(URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("X-App-ID", APP_ID)
                    setRequestProperty("X-User-ID", userId())
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    handleError(conn, code, listener)
                    return@thread
                }

                val limit = conn.getHeaderField("X-Quota-Limit")?.toIntOrNull() ?: -1
                val used = conn.getHeaderField("X-Quota-Used")?.toIntOrNull() ?: -1

                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload == "[DONE]") break
                        if (payload.isEmpty()) continue
                        val frag = runCatching { JSONObject(payload).optString("response", "") }.getOrNull()
                        if (!frag.isNullOrEmpty()) listener.onChunk(frag)
                    }
                }
                listener.onDone(used, limit)
            } catch (e: Exception) {
                Utils.log("Summarizer: request failed: ${e.message}")
                listener.onError("网络错误，请重试", true)
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun callOpenAI(messages: List<Msg>, style: Int, language: String, listener: Listener) {
        var conn: HttpURLConnection? = null
        try {
            val rawUrl = Settings.aiApiUrl.value.trim().ifEmpty { "https://api.openai.com/v1" }
            val urlString = if (rawUrl.endsWith("/chat/completions")) rawUrl else rawUrl.trimEnd('/') + "/chat/completions"
            val apiKey = Settings.aiApiKey.value.trim()
            val modelName = Settings.aiModel.value.trim().ifEmpty { "gpt-4o-mini" }

            val systemPrompt = buildString {
                append("你是一个精炼、高效的聊天记录总结助手。")
                when (style) {
                    1 -> append("请用一句话高度概括这段聊天的核心信息。")
                    2 -> append("请详细总结这段聊天的背景、讨论要点、达成的共识及待办事项。")
                    else -> append("请用 Markdown 要点列表列出这段聊天的关键信息。")
                }
                if (language.isNotBlank() && language != "auto") {
                    append("输出语言必须为：$language。")
                }
            }

            val userContent = messages.joinToString("\n") { "${it.sender}: ${it.text}" }

            val requestBody = JSONObject().apply {
                put("model", modelName)
                put("stream", true)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                })
            }.toString()

            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                if (apiKey.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Utils.log("OpenAI API HTTP $code: $err")
                val errMsg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message", "") }
                    .getOrNull()?.takeIf { it.isNotEmpty() } ?: "API 调用失败 (HTTP $code)"
                listener.onError(errMsg, true)
                return
            }

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    runCatching {
                        val obj = JSONObject(payload)
                        val choices = obj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                listener.onChunk(content)
                            }
                        }
                    }
                }
            }
            listener.onDone(-1, -1)
        } catch (e: Exception) {
            Utils.log("Summarizer OpenAI error: ${e.message}")
            listener.onError("请求失败: ${e.message}", true)
        } finally {
            conn?.disconnect()
        }
    }

    private fun handleError(conn: HttpURLConnection, code: Int, listener: Listener) {
        val raw = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        Utils.log("Summarizer: HTTP $code body=${raw?.take(160)}")
        when (code) {
            429 -> {
                val limit = runCatching { JSONObject(raw ?: "").optInt("limit", -1) }.getOrDefault(-1)
                listener.onError(
                    if (limit > 0) "今日总结次数已用完（上限 $limit），UTC 0 点重置" else "今日总结次数已用完，UTC 0 点重置",
                    false,
                )
            }
            503 -> listener.onError("服务器繁忙，请稍后重试", true)
            403 -> listener.onError("应用鉴权失败", false)
            400 -> listener.onError("请求无效（消息为空）", false)
            500 -> listener.onError("服务器错误，请重试", true)
            else -> listener.onError("请求失败（$code）", true)
        }
    }

    /** Map a 2-letter code to a language name the server understands (falls back to the raw code). */
    private fun langName(code: String): String = when (code) {
        "zh" -> "中文"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "es" -> "Español"
        "ru" -> "Русский"
        "pt" -> "Português"
        "ar" -> "العربية"
        else -> code
    }
}
