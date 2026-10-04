package com.ayuemin.disputeai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Progressive transport for OpenAI-compatible endpoints. Other provider modes
 * keep using the existing whole-response implementation in LlmApi.
 */
object StreamingLlmApi {
    private const val MAX_ERROR_BODY = 6000

    fun call(
        slot: JSONObject,
        messagesJson: String,
        onDelta: (String) -> Unit
    ): JSONObject {
        if (slot.optString("provider", "openai") != "openai") {
            return LlmApi.call(slot, messagesJson, false)
        }
        return try {
            openAiStream(slot, JSONArray(messagesJson), onDelta)
        } catch (e: StreamUnsupportedException) {
            LlmApi.call(slot, messagesJson, false)
        }
    }

    private fun openAiStream(
        slot: JSONObject,
        messages: JSONArray,
        onDelta: (String) -> Unit
    ): JSONObject {
        val base = trimSlash(slot.optString("baseUrl", ""))
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val body = openAiBody(slot, messages, stream = true)
        val key = slot.optString("apiKey", "")
        validateEndpoint(endpoint, key)

        val c = URL(endpoint).openConnection() as HttpURLConnection
        val timeout = slot.optInt("timeoutSec", 180).coerceIn(10, 600) * 1000
        c.connectTimeout = minOf(timeout, 60_000)
        c.readTimeout = timeout
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "text/event-stream, application/json")
        c.useCaches = false
        c.doOutput = true
        if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")

        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }

        val code = c.responseCode
        if (code in 300..399) {
            c.disconnect()
            throw Exception("HTTP $code: перенаправление API заблокировано. Проверьте базовый адрес.")
        }
        if (code !in 200..299) {
            val error = readAll(c.errorStream)
            c.disconnect()
            val low = error.lowercase(Locale.ROOT)
            if (code in setOf(400, 404, 405, 415, 422) && ("stream" in low || "sse" in low || "unsupported" in low)) {
                throw StreamUnsupportedException()
            }
            throw Exception("HTTP $code: ${safeBody(error)}")
        }

        val type = c.contentType.orEmpty().lowercase(Locale.ROOT)
        return try {
            if ("text/event-stream" in type) {
                parseSse(c.inputStream, onDelta)
            } else {
                val raw = readAll(c.inputStream)
                if (raw.lineSequence().any { it.trimStart().startsWith("data:") }) {
                    parseSseText(raw, onDelta)
                } else {
                    parseOpenAiJson(JSONObject(raw), onDelta)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun openAiBody(slot: JSONObject, messages: JSONArray, stream: Boolean): JSONObject = JSONObject().apply {
        put("model", slot.optString("model"))
        val all = JSONArray()
        val system = slot.optString("system", "")
        if (system.isNotEmpty()) all.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until messages.length()) all.put(messages.get(i))
        put("messages", all)
        if (slot.optBoolean("temperatureEnabled", false)) put("temperature", slot.optDouble("temperature", 0.8))
        put("stream", stream)

        val base = slot.optString("baseUrl", "")
        if (slot.optBoolean("webSearchEnabled", false) && base.contains("openrouter.ai", ignoreCase = true)) {
            val engine = slot.optString("webSearchEngine", "auto").lowercase(Locale.ROOT).let {
                if (it in setOf("auto", "native", "exa", "parallel", "perplexity")) it else "auto"
            }
            val parameters = JSONObject().put("engine", engine)
            put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "openrouter:web_search")
                        .put("parameters", parameters)
                )
            )
            put("max_tool_calls", slot.optInt("webSearchMaxCalls", 2).coerceIn(1, 100))
        }

        val modelId = slot.optString("model", "").lowercase(Locale.ROOT)
        val heuristicReasoning = listOf(
            "o1", "o3", "o4", "gpt-5", "gpt-6", "gpt-oss", "r1", "reason", "thinking",
            "qwq", "deepseek-r", "glm-5", "kimi-k", "magistral"
        ).any { modelId.contains(it) }
        val reasoningAvailable = slot.optBoolean("reasoningAvailable", heuristicReasoning)
        if (reasoningAvailable) {
            val reasoning = JSONObject()
            val alwaysOn = slot.optBoolean("reasoningAlwaysOn", modelId.contains("thinking") || modelId.contains("glm-5.3"))
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

    private fun parseSse(input: InputStream, onDelta: (String) -> Unit): JSONObject {
        val text = StringBuilder()
        var finishReason = ""
        var usage: JSONObject? = null
        input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("data:")) return@forEach
                val payload = line.substringAfter("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") return@forEach
                val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
                chunk.optJSONObject("usage")?.let { usage = it }
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
                choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finishReason = it }
                val delta = choice.optJSONObject("delta")
                val part = extractText(delta?.opt("content")).orEmpty()
                if (part.isNotEmpty()) {
                    text.append(part)
                    onDelta(part)
                }
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("text", text.toString())
            put("streamed", true)
            if (finishReason.isNotBlank()) put("finishReason", finishReason)
            usage?.let { put("usage", it) }
        }
    }

    private fun parseSseText(raw: String, onDelta: (String) -> Unit): JSONObject {
        val text = StringBuilder()
        var finishReason = ""
        var usage: JSONObject? = null
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("data:")) return@forEach
            val payload = line.substringAfter("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") return@forEach
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            chunk.optJSONObject("usage")?.let { usage = it }
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
            choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finishReason = it }
            val part = extractText(choice.optJSONObject("delta")?.opt("content")).orEmpty()
            if (part.isNotEmpty()) {
                text.append(part)
                onDelta(part)
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("text", text.toString())
            put("streamed", false)
            if (finishReason.isNotBlank()) put("finishReason", finishReason)
            usage?.let { put("usage", it) }
        }
    }

    private fun parseOpenAiJson(json: JSONObject, onDelta: (String) -> Unit): JSONObject {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw Exception("API не вернул choices: ${safeBody(json.toString())}")
        val choice = choices.getJSONObject(0)
        val msg = choice.optJSONObject("message")
        var text = extractText(msg?.opt("content")).orEmpty()
        if (text.isEmpty() && msg != null) text = msg.optString("reasoning_content", "")
        if (text.isNotEmpty()) onDelta(text)
        return JSONObject().apply {
            put("ok", true)
            put("text", text)
            put("streamed", false)
            if (json.has("usage")) put("usage", json.get("usage"))
            if (choice.has("finish_reason")) put("finishReason", choice.optString("finish_reason"))
        }
    }

    private fun extractText(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return ""
        if (value is String) return value
        if (value is JSONObject) {
            if (value.has("text")) return value.optString("text")
            return ""
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
            return parts.joinToString("")
        }
        return value.toString()
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

    private fun safeBody(s: String): String {
        val redacted = s
            .replace(Regex("([?&](?:key|api_key|apikey)=)[^&\\s]+", RegexOption.IGNORE_CASE)) { it.groupValues[1] + "***" }
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", RegexOption.IGNORE_CASE), "Bearer ***")
            .replace(Regex("\\b(?:sk|sk-or-v1)-[A-Za-z0-9_-]{8,}\\b", RegexOption.IGNORE_CASE), "***")
        return if (redacted.length > MAX_ERROR_BODY) redacted.take(MAX_ERROR_BODY) + "…" else redacted
    }

    private fun trimSlash(s: String): String = s.trim().trimEnd('/')

    private class StreamUnsupportedException : Exception()
}
