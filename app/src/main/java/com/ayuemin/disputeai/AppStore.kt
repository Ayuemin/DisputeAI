package com.ayuemin.disputeai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppStore(private val context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("disputeai_settings", Context.MODE_PRIVATE)
    private val chatsFile = File(context.filesDir, "chats.json")

    fun loadSettings(): AppSettings {
        val raw = prefs.getString("settings", null) ?: return AppSettings()
        return try { settingsFromJson(JSONObject(raw)) } catch (_: Exception) { AppSettings() }
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString("settings", settingsToJson(settings).toString()).apply()
    }

    fun loadChats(): List<ChatSession> {
        if (!chatsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(chatsFile.readText())
            buildList {
                for (i in 0 until arr.length()) add(chatFromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveChats(chats: List<ChatSession>) {
        try {
            val arr = JSONArray()
            chats.forEach { arr.put(chatToJson(it)) }
            chatsFile.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun resetSettings(): AppSettings {
        secrets.clearAll()
        val defaults = AppSettings()
        saveSettings(defaults)
        return defaults
    }

    private fun settingsToJson(settings: AppSettings): JSONObject = JSONObject().apply {
        put("participants", JSONArray().apply { settings.participants.forEach { put(modelToJson(it)) } })
        put("resultModel", modelToJson(settings.resultModel))
        put("general", JSONObject().apply {
            put("rounds", settings.general.rounds)
            put("firstModelId", settings.general.firstModelId)
            put("resultContextCycles", settings.general.resultContextCycles)
            put("resultUseAllCycles", settings.general.resultUseAllCycles)
            put("firstCyclePrompt", settings.general.firstCyclePrompt)
            put("laterCyclesPrompt", settings.general.laterCyclesPrompt)
        })
    }

    private fun settingsFromJson(o: JSONObject): AppSettings {
        val participantsArray = o.optJSONArray("participants")
        val participants = mutableListOf<ModelConfig>()
        if (participantsArray != null) {
            for (i in 0 until participantsArray.length()) {
                participants += modelFromJson(participantsArray.getJSONObject(i), false, participantDefaultColor(i))
            }
        }
        if (participants.size < 2) return AppSettings()
        val result = o.optJSONObject("resultModel")?.let { modelFromJson(it, true, DEFAULT_RESULT_COLOR) } ?: defaultResultModel()
        val g = o.optJSONObject("general")
        val general = GeneralSettings(
            rounds = g?.optInt("rounds", 10)?.coerceIn(1, 100) ?: 10,
            firstModelId = g?.optString("firstModelId", participants.first().id).orEmpty().ifBlank { participants.first().id },
            resultContextCycles = g?.optInt("resultContextCycles", 3)?.coerceIn(1, 100) ?: 3,
            resultUseAllCycles = g?.optBoolean("resultUseAllCycles", true) ?: true,
            firstCyclePrompt = g?.optString("firstCyclePrompt", DEFAULT_FIRST_CYCLE_PROMPT).orEmpty().ifBlank { DEFAULT_FIRST_CYCLE_PROMPT },
            laterCyclesPrompt = g?.optString("laterCyclesPrompt", DEFAULT_LATER_CYCLES_PROMPT).orEmpty().ifBlank { DEFAULT_LATER_CYCLES_PROMPT }
        )
        return AppSettings(participants, result, general)
    }

    private fun modelToJson(m: ModelConfig): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("enabled", m.enabled)
        put("provider", m.provider)
        put("baseUrl", m.baseUrl)
        put("model", m.model)
        put("hasApiKey", secrets.has(m.id))
        put("systemPrompt", m.systemPrompt)
        put("temperatureEnabled", m.temperatureEnabled)
        put("temperature", m.temperature)
        put("timeoutSec", m.timeoutSec)
        put("reasoningEnabled", m.reasoningEnabled)
        put("reasoningEffort", m.reasoningEffort)
        put("reasoningBudget", m.reasoningBudget)
        put("responseColor", m.responseColor)
        put("webSearchEnabled", m.webSearchEnabled)
        put("webSearchEngine", m.webSearchEngine)
        put("webSearchMaxCalls", m.webSearchMaxCalls)
    }

    private fun modelFromJson(o: JSONObject, result: Boolean, defaultColor: Int): ModelConfig {
        val id = o.optString("id", if (result) "result" else "m")
        val engine = o.optString("webSearchEngine", "auto").lowercase().let {
            if (it in setOf("auto", "native", "exa", "parallel", "perplexity")) it else "auto"
        }
        return ModelConfig(
            id = id,
            name = o.optString("name", if (result) "Модель результата" else "Модель"),
            enabled = o.optBoolean("enabled", true),
            provider = o.optString("provider", "openai"),
            baseUrl = o.optString("baseUrl", ""),
            model = o.optString("model", ""),
            hasApiKey = secrets.has(id),
            systemPrompt = o.optString("systemPrompt", if (result) DEFAULT_RESULT_PROMPT else DEFAULT_PARTICIPANT_PROMPT),
            temperatureEnabled = o.optBoolean("temperatureEnabled", false),
            temperature = o.optDouble("temperature", 0.8).coerceIn(0.0, 2.0),
            timeoutSec = o.optInt("timeoutSec", 180).coerceIn(10, 600),
            reasoningEnabled = o.optBoolean("reasoningEnabled", false),
            reasoningEffort = o.optString("reasoningEffort", "auto"),
            reasoningBudget = o.optInt("reasoningBudget", 0).coerceAtLeast(0),
            responseColor = if (o.has("responseColor")) o.optInt("responseColor", defaultColor) else defaultColor,
            webSearchEnabled = o.optBoolean("webSearchEnabled", false),
            webSearchEngine = engine,
            webSearchMaxCalls = o.optInt("webSearchMaxCalls", 2).coerceIn(1, 100)
        )
    }

    private fun chatToJson(c: ChatSession): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("titleIsManual", c.titleIsManual)
        put("pinned", c.pinned)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        put("discussionFinished", c.discussionFinished)
        put("attachments", JSONArray().apply { c.attachments.forEach { a ->
            put(JSONObject().apply {
                put("id", a.id); put("name", a.name); put("mime", a.mime); put("size", a.size); put("path", a.path)
            })
        } })
        put("messages", JSONArray().apply { c.messages.filterNot { it.inProgress }.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id); put("authorId", m.authorId); put("authorName", m.authorName); put("text", m.text)
                put("timestamp", m.timestamp); put("cycle", m.cycle ?: JSONObject.NULL); put("isResult", m.isResult); put("error", m.error)
                put("attachmentIds", JSONArray(m.attachmentIds)); put("discussionId", m.discussionId ?: JSONObject.NULL)
            })
        } })
    }

    private fun chatFromJson(o: JSONObject): ChatSession {
        val attachments = mutableListOf<AttachmentMeta>()
        o.optJSONArray("attachments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                attachments += AttachmentMeta(
                    id = a.optString("id"), name = a.optString("name"), mime = a.optString("mime"),
                    size = a.optLong("size"), path = a.optString("path")
                )
            }
        }
        val messages = mutableListOf<ChatMessage>()
        o.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val ids = mutableListOf<String>()
                m.optJSONArray("attachmentIds")?.let { a -> for (j in 0 until a.length()) ids += a.optString(j) }
                messages += ChatMessage(
                    id = m.optString("id"), authorId = m.optString("authorId"), authorName = m.optString("authorName"),
                    text = m.optString("text"), timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    attachmentIds = ids, cycle = if (m.isNull("cycle")) null else m.optInt("cycle"),
                    isResult = m.optBoolean("isResult"), error = m.optBoolean("error"), inProgress = false,
                    discussionId = if (m.isNull("discussionId")) null else m.optString("discussionId").ifBlank { null }
                )
            }
        }
        return ChatSession(
            id = o.optString("id"), title = o.optString("title", "Новый чат"), titleIsManual = o.optBoolean("titleIsManual", false),
            pinned = o.optBoolean("pinned"), createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()), messages = messages,
            attachments = attachments, discussionFinished = o.optBoolean("discussionFinished", false)
        )
    }
}
