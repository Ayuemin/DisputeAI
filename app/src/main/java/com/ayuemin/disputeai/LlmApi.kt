package com.ayuemin.disputeai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object LlmApi {
    private const val MAX_ERROR_BODY = 6000
    private val keyQuery = Regex("([?&](?:key|api_key|apikey)=)[^&\\s]+", RegexOption.IGNORE_CASE)
    private val bearer = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE)
    private val openAiStyleKey = Regex("\\b(?:sk|sk-or-v1)-[A-Za-z0-9_-]{8,}\\b", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun fail(e: Throwable?): JSONObject = JSONObject().apply {
        put("ok", false)
        val msg = e?.message?.takeIf { it.isNotBlank() } ?: e?.javaClass?.simpleName ?: "Неизвестная ошибка"
        put("error", userFriendlyError(msg))
    }

    @JvmStatic
    fun userFriendlyError(raw: String): String {
        val cleaned = redact(raw).trim()
        if (cleaned.isBlank()) return "Не удалось выполнить запрос. Попробуйте ещё раз."
        val providerMessage = extractProviderMessage(cleaned)?.trim().orEmpty()
        val combined = (cleaned + "\n" + providerMessage).lowercase(Locale.ROOT)

        return when {
            "openrouter:web_search" in combined || "server tool" in combined && "web_search" in combined -> {
                val code = httpCode(cleaned) ?: httpCode(providerMessage)
                "Интернет-поиск OpenRouter временно недоступен${code?.let { " (HTTP $it)" }.orEmpty()}. " +
                    "Попробуйте ещё раз или выберите другой движок поиска."
            }
            "reasoning is mandatory" in combined ||
                "reasoning cannot be disabled" in combined ||
                ("reasoning" in combined && "mandatory" in combined) ->
                "Для этой модели размышление обязательно и не может быть выключено. " +
                    "Оставьте «Размышление» включённым; если не уверены в уровне, выберите «Авто»."
            ("reasoning" in combined && "effort" in combined && ("unsupported" in combined || "not supported" in combined || "invalid" in combined)) ||
                "unsupported reasoning effort" in combined ->
                "Выбранный уровень размышления не поддерживается этой моделью. Попробуйте уровень «Авто» или другой доступный уровень."
            "provider_overloaded" in combined ||
                "service temporarily overloaded" in combined ||
                "provider is overloaded" in combined ||
                "upstream error from nvidia" in combined ||
                ("503" in combined && "overload" in combined) ->
                "Провайдер модели временно перегружен (HTTP 503). Это не ошибка настроек. Попробуйте ещё раз через несколько секунд."
            "rate limit" in combined || "too many requests" in combined || "http 429" in combined ->
                "Превышен лимит запросов API (HTTP 429). Подождите немного и повторите попытку."
            "context length" in combined || "context_length" in combined || "maximum context" in combined || "too many tokens" in combined ->
                "Контекст запроса слишком большой для этой модели. Уменьшите объём истории, вложений или текста и попробуйте снова."
            "invalid api key" in combined || "unauthorized" in combined || "authentication" in combined || "http 401" in combined ->
                "Не удалось авторизоваться в API. Проверьте API-ключ и адрес сервиса."
            "forbidden" in combined || "http 403" in combined ->
                "API отклонил запрос (HTTP 403). Проверьте права API-ключа и доступность выбранной модели."
            ("model" in combined && "not found" in combined) || "http 404" in combined ->
                "Модель или API-адрес не найдены (HTTP 404). Проверьте ID модели и Base URL."
            "timeout" in combined || "timed out" in combined || "sockettimeoutexception" in combined ->
                "Модель не успела ответить за установленное время. Попробуйте ещё раз или увеличьте таймаут."
            providerMessage.isNotBlank() -> {
                val concise = providerMessage.replace(Regex("\\s+"), " ").take(500)
                "Ошибка провайдера: $concise"
            }
            else -> cleaned.replace(Regex("\\s+"), " ").take(500)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun call(slot: JSONObject, messagesJson: String?, brief: Boolean): JSONObject {
        val started = System.currentTimeMillis()
        val messages = JSONArray(messagesJson ?: "[]")
        val result = when (slot.optString("provider", "openai")) {
            "anthropic" -> anthropic(slot, messages)
            "gemini" -> gemini(slot, messages)
            else -> openAi(slot, messages)
        }
        result.put("ms", System.currentTimeMillis() - started)
        if (brief && result.optBoolean("ok")) {
            val text = result.optString("text", "")
            if (text.length > 200) result.put("text", text.take(200))
        }
        return result
    }

    @JvmStatic
    fun capabilities(slot: JSONObject): JSONObject {
        val provider = slot.optString("provider", "openai")
        val modelId = slot.optString("model", "").trim()
        val base = trimSlash(slot.optString("baseUrl", ""))
        if (modelId.isBlank()) return JSONObject().put("known", false)

        if (provider == "openai" && base.contains("openrouter.ai", ignoreCase = true)) {
            try {
                val endpoint = if (base.endsWith("/models")) base else "$base/models"
                val json = getJson(endpoint, slot.optString("apiKey", ""))
                val data = json.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val m = data.optJSONObject(i) ?: continue
                    if (m.optString("id") != modelId) continue
                    val params = m.optJSONArray("supported_parameters")
                    val p = mutableSetOf<String>()
                    if (params != null) for (j in 0 until params.length()) p += params.optString(j)
                    val reasoningObj = m.optJSONObject("reasoning")
                    val efforts = mutableListOf<String>()
                    reasoningObj?.optJSONArray("supported_efforts")?.let { arr ->
                        for (j in 0 until arr.length()) arr.optString(j).takeIf { it.isNotBlank() }?.let(efforts::add)
                    }
                    val reasoning = "reasoning" in p || reasoningObj != null || heuristicReasoningSupported(modelId)
                    return JSONObject().apply {
                        put("known", true)
                        put("reasoningSupported", reasoning)
                        put(
                            "reasoningAlwaysOn",
                            (reasoningObj?.optBoolean("always_on", false) ?: false) || heuristicReasoningAlwaysOn(modelId)
                        )
                        put("supportedEfforts", JSONArray(efforts))
                        put("temperatureSupported", params == null || "temperature" in p)
                    }
                }
            } catch (_: Exception) { }
        }
        return heuristicCapabilities(modelId)
    }

    private fun heuristicReasoningSupported(modelId: String): Boolean {
        val id = modelId.lowercase(Locale.ROOT)
        return listOf(
            "o1", "o3", "o4", "gpt-5", "gpt-6", "gpt-oss", "r1", "reason", "thinking",
            "qwq", "deepseek-r", "glm-5", "kimi-k", "magistral", "nemotron"
        ).any { id.contains(it) }
    }

    private fun heuristicReasoningAlwaysOn(modelId: String): Boolean {
        val id = modelId.lowercase(Locale.ROOT)
        return id.contains("thinking") || id.contains("glm-5.3") || id.contains("nemotron-3-ultra")
    }

    private fun heuristicCapabilities(modelId: String): JSONObject {
        val reasoning = heuristicReasoningSupported(modelId)
        val efforts = if (reasoning) JSONArray(listOf("low", "medium", "high")) else JSONArray()
        return JSONObject().apply {
            put("known", reasoning)
            put("reasoningSupported", reasoning)
            put("reasoningAlwaysOn", heuristicReasoningAlwaysOn(modelId))
            put("supportedEfforts", efforts)
            put("temperatureSupported", true)
        }
    }

    private fun openAi(slot: JSONObject, messages: JSONArray): JSONObject {
        val base = trimSlash(slot.optString("baseUrl", ""))
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val body = JSONObject().apply {
            put("model", slot.optString("model"))
            val all = JSONArray()
            val system = slot.optString("system", "")
            if (system.isNotEmpty()) all.put(JSONObject().put("role", "system").put("content", system))
            for (i in 0 until messages.length()) all.put(messages.get(i))
            put("messages", all)
            if (slot.optBoolean("temperatureEnabled", false)) put("temperature", slot.optDouble("temperature", 0.8))
            put("stream", false)

            val modelId = slot.optString("model", "")
            val cap = heuristicCapabilities(modelId)
            val reasoningAvailable = slot.optBoolean("reasoningAvailable", cap.optBoolean("reasoningSupported", false))
            if (reasoningAvailable) {
                val reasoning = JSONObject()
                val alwaysOn = slot.optBoolean("reasoningAlwaysOn", cap.optBoolean("reasoningAlwaysOn", false))
                val enabled = alwaysOn || slot.optBoolean("reasoningEnabled", false)
                reasoning.put("enabled", enabled)
                reasoning.put("exclude", true)
                if (enabled) {
                    val effort = slot.optString("reasoningEffort", "auto")
                    if (effort.isNotBlank() && effort != "auto") reasoning.put("effort", effort)
                    val budget = slot.optInt("reasoningBudget", 0)
                    if (budget > 0) reasoning.put("max_tokens", budget)
                }
                put("reasoning", reasoning)
            }
        }

        val json = post(endpoint, body, slot, null)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw Exception("API не вернул choices: ${safeBody(json.toString())}")
        val choice = choices.getJSONObject(0)
        val msg = choice.optJSONObject("message")
        var text = extractText(msg?.opt("content"))
        if (text.isNullOrEmpty() && msg != null) text = msg.optString("reasoning_content", "")
        return JSONObject().apply {
            put("ok", true)
            put("text", text ?: "")
            if (json.has("usage")) put("usage", json.get("usage"))
            if (choice.has("finish_reason")) put("finishReason", choice.optString("finish_reason"))
        }
    }

    private fun anthropic(slot: JSONObject, messages: JSONArray): JSONObject {
        val base = trimSlash(slot.optString("baseUrl", "https://api.anthropic.com"))
        val endpoint = if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
        val body = JSONObject().apply {
            put("model", slot.optString("model"))
            put("system", slot.optString("system", ""))
            put("max_tokens", 32768)
            if (slot.optBoolean("temperatureEnabled", false)) put("temperature", slot.optDouble("temperature", 0.8))
            val converted = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val role = if (m.optString("role") == "assistant") "assistant" else "user"
                converted.put(JSONObject().put("role", role).put("content", toAnthropicBlocks(m.opt("content"))))
            }
            put("messages", converted)
        }
        val extra = arrayOf(arrayOf("anthropic-version", "2023-06-01"), arrayOf("x-api-key", slot.optString("apiKey", "")))
        val json = post(endpoint, body, slot, extra)
        val text = extractText(json.opt("content"))
        return JSONObject().apply {
            put("ok", true); put("text", text ?: "")
            if (json.has("usage")) put("usage", json.get("usage"))
            if (json.has("stop_reason")) put("finishReason", json.optString("stop_reason"))
        }
    }

    private fun gemini(slot: JSONObject, messages: JSONArray): JSONObject {
        val base = trimSlash(slot.optString("baseUrl", "https://generativelanguage.googleapis.com"))
        val model = URLEncoder.encode(slot.optString("model"), "UTF-8").replace("+", "%20")
        val key = URLEncoder.encode(slot.optString("apiKey", ""), "UTF-8")
        val endpoint = "$base/v1beta/models/$model:generateContent" + if (key.isEmpty()) "" else "?key=$key"
        val body = JSONObject().apply {
            val contents = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val role = if (m.optString("role") == "assistant") "model" else "user"
                contents.put(JSONObject().put("role", role).put("parts", toGeminiParts(m.opt("content"))))
            }
            put("contents", contents)
            val system = slot.optString("system", "")
            if (system.isNotEmpty()) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            if (slot.optBoolean("temperatureEnabled", false)) put("generationConfig", JSONObject().put("temperature", slot.optDouble("temperature", 0.8)))
        }
        val json = post(endpoint, body, slot, null)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) throw Exception("Gemini не вернул ответ: ${safeBody(json.toString())}")
        val cand = candidates.getJSONObject(0)
        val text = cand.optJSONObject("content")?.let { extractText(it.opt("parts")) }.orEmpty()
        return JSONObject().apply {
            put("ok", true); put("text", text)
            if (json.has("usageMetadata")) put("usage", json.get("usageMetadata"))
            if (cand.has("finishReason")) put("finishReason", cand.optString("finishReason"))
        }
    }

    private fun post(url: String, body: JSONObject, slot: JSONObject, extraHeaders: Array<Array<String>>?): JSONObject {
        val key = slot.optString("apiKey", "")
        validateEndpoint(url, key)
        val c = URL(url).openConnection() as HttpURLConnection
        val timeout = slot.optInt("timeoutSec", 180).coerceIn(10, 600) * 1000
        c.connectTimeout = minOf(timeout, 60_000)
        c.readTimeout = timeout
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "application/json")
        c.useCaches = false
        c.doOutput = true
        val provider = slot.optString("provider", "openai")
        if (key.isNotEmpty() && provider != "anthropic" && provider != "gemini") c.setRequestProperty("Authorization", "Bearer $key")
        extraHeaders?.forEach { h -> if (h.size == 2 && h[1].isNotEmpty()) c.setRequestProperty(h[0], h[1]) }
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        if (code in 300..399) {
            c.disconnect()
            throw Exception("HTTP $code: перенаправление API заблокировано. Проверьте базовый адрес.")
        }
        val input = if (code in 200..299) c.inputStream else c.errorStream
        val text = readAll(input)
        c.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code: ${safeBody(text)}")
        return JSONObject(text)
    }

    private fun getJson(url: String, apiKey: String): JSONObject {
        validateEndpoint(url, apiKey)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        val code = c.responseCode
        val text = readAll(if (code in 200..299) c.inputStream else c.errorStream)
        c.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code: ${safeBody(text)}")
        return JSONObject(text)
    }

    private fun validateEndpoint(rawUrl: String, apiKey: String) {
        if (rawUrl.isBlank()) throw Exception("Не указан адрес API")
        val uri = URI.create(rawUrl.trim())
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "https" && scheme != "http") throw Exception("Разрешены только HTTPS и локальный HTTP")
        if (uri.host.isNullOrBlank()) throw Exception("Некорректный адрес API")
        if (uri.userInfo != null) throw Exception("Логин/пароль в URL запрещены")
        if (uri.fragment != null) throw Exception("Фрагмент # в адресе API не поддерживается")
        if (scheme == "http") {
            if (apiKey.isNotEmpty()) throw Exception("API-ключ не отправляется по HTTP. Используйте HTTPS.")
            if (!isPrivateHost(uri.host)) throw Exception("HTTP разрешён только для localhost/локальной сети и только без API-ключа.")
        }
    }

    private fun isPrivateHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase(Locale.ROOT)
        if (h == "localhost" || h == "::1" || h.endsWith(".local")) return true
        return try {
            val a = InetAddress.getByName(host)
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress
        } catch (_: Exception) { false }
    }

    private fun toAnthropicBlocks(content: Any?): JSONArray {
        val out = JSONArray()
        if (content == null || content == JSONObject.NULL) return out.put(JSONObject().put("type", "text").put("text", ""))
        if (content is String) return out.put(JSONObject().put("type", "text").put("text", content))
        if (content !is JSONArray) return out.put(JSONObject().put("type", "text").put("text", content.toString()))
        for (i in 0 until content.length()) {
            val p = content.optJSONObject(i) ?: continue
            when (p.optString("type")) {
                "text" -> out.put(JSONObject().put("type", "text").put("text", p.optString("text")))
                "image_url" -> parseDataUrl(p.optJSONObject("image_url")?.optString("url").orEmpty())?.let { d ->
                    out.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", d.mime).put("data", d.data)))
                }
                "file" -> parseDataUrl(p.optJSONObject("file")?.optString("file_data").orEmpty())?.let { d ->
                    out.put(JSONObject().put("type", "document").put("source", JSONObject().put("type", "base64").put("media_type", d.mime).put("data", d.data)))
                }
            }
        }
        if (out.length() == 0) out.put(JSONObject().put("type", "text").put("text", ""))
        return out
    }

    private fun toGeminiParts(content: Any?): JSONArray {
        val out = JSONArray()
        if (content == null || content == JSONObject.NULL) return out.put(JSONObject().put("text", ""))
        if (content is String) return out.put(JSONObject().put("text", content))
        if (content !is JSONArray) return out.put(JSONObject().put("text", content.toString()))
        for (i in 0 until content.length()) {
            val p = content.optJSONObject(i) ?: continue
            when (p.optString("type")) {
                "text" -> out.put(JSONObject().put("text", p.optString("text")))
                "image_url" -> parseDataUrl(p.optJSONObject("image_url")?.optString("url").orEmpty())?.let { d ->
                    out.put(JSONObject().put("inlineData", JSONObject().put("mimeType", d.mime).put("data", d.data)))
                }
                "file" -> parseDataUrl(p.optJSONObject("file")?.optString("file_data").orEmpty())?.let { d ->
                    out.put(JSONObject().put("inlineData", JSONObject().put("mimeType", d.mime).put("data", d.data)))
                }
            }
        }
        if (out.length() == 0) out.put(JSONObject().put("text", ""))
        return out
    }

    private data class DataUrl(val mime: String, val data: String)
    private fun parseDataUrl(s: String): DataUrl? {
        if (!s.startsWith("data:")) return null
        val comma = s.indexOf(',')
        if (comma < 0) return null
        val head = s.substring(5, comma)
        if (!head.contains(";base64", ignoreCase = true)) return null
        val mime = head.substringBefore(';').ifBlank { "application/octet-stream" }
        val data = s.substring(comma + 1)
        return data.takeIf { it.isNotEmpty() }?.let { DataUrl(mime, it) }
    }

    private fun extractText(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return ""
        if (value is String) return value
        if (value is JSONObject) {
            if (value.has("text")) return value.optString("text")
            return value.toString()
        }
        if (value is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until value.length()) {
                val x = value.opt(i)
                when (x) {
                    is String -> parts += x
                    is JSONObject -> if (x.has("text")) parts += x.optString("text")
                }
            }
            return parts.joinToString("\n")
        }
        return value.toString()
    }

    private fun readAll(input: InputStream?): String {
        if (input == null) return ""
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun safeBody(s: String?): String {
        if (s == null) return ""
        val redacted = redact(s)
        return if (redacted.length > MAX_ERROR_BODY) redacted.take(MAX_ERROR_BODY) + "…" else redacted
    }

    private fun extractProviderMessage(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        return when (val error = json.opt("error")) {
            is JSONObject -> error.optString("message").takeIf { it.isNotBlank() }
            is String -> error.takeIf { it.isNotBlank() }
            else -> json.optString("message").takeIf { it.isNotBlank() }
        }
    }

    private fun httpCode(raw: String): Int? =
        Regex("(?:HTTP\\s*)?(\\d{3})", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun redact(s: String): String = s
        .replace(keyQuery) { it.groupValues[1] + "***" }
        .replace(bearer, "Bearer ***")
        .replace(openAiStyleKey, "***")
        .replace(Regex("(\"user_id\"\\s*:\\s*\")[^\"]+(\")", RegexOption.IGNORE_CASE)) { it.groupValues[1] + "***" + it.groupValues[2] }

    private fun trimSlash(s: String): String = s.trim().trimEnd('/')
}
