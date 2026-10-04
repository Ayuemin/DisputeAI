package com.ayuemin.disputeai

import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class DisputeViewModel(app: Application) : AndroidViewModel(app) {
    private val secrets = SecretStore(app)
    private val store = AppStore(app, secrets)
    private val runToken = AtomicLong(0)
    private val resultToken = AtomicLong(0)

    private val _state = MutableStateFlow(
        UiState(settings = store.loadSettings(), chats = store.loadChats())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (_state.value.chats.isEmpty()) newChat()
        else if (_state.value.activeChatId == null) {
            _state.value = _state.value.copy(activeChatId = sortedChats(_state.value.chats).firstOrNull()?.id)
        }
        refreshKeyFlags()
    }

    private fun sortedChats(chats: List<ChatSession>): List<ChatSession> =
        chats.sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })

    private fun persistChats(chats: List<ChatSession> = _state.value.chats) = store.saveChats(chats)
    private fun persistSettings(settings: AppSettings = _state.value.settings) = store.saveSettings(settings)

    private fun refreshKeyFlags() {
        val s = _state.value.settings
        val participants = s.participants.map { it.copy(hasApiKey = secrets.has(it.id)) }
        val result = s.resultModel.copy(hasApiKey = secrets.has(s.resultModel.id))
        _state.value = _state.value.copy(settings = s.copy(participants = participants, resultModel = result))
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null) }
    private fun notice(text: String) { _state.value = _state.value.copy(notice = text) }

    fun newChat() {
        resultToken.incrementAndGet()
        stopCycle(markFinished = false)
        val chat = ChatSession()
        val chats = listOf(chat) + _state.value.chats
        _state.value = _state.value.copy(chats = chats, activeChatId = chat.id, pendingAttachments = emptyList())
        persistChats(chats)
    }

    fun selectChat(id: String) {
        resultToken.incrementAndGet()
        if (_state.value.run.mode != RunMode.IDLE) stopCycle(markFinished = true)
        _state.value = _state.value.copy(activeChatId = id, pendingAttachments = emptyList())
    }

    fun pinChat(id: String, pinned: Boolean) = updateChat(id) { it.copy(pinned = pinned, updatedAt = System.currentTimeMillis()) }

    fun renameChat(id: String, title: String) {
        val clean = title.trim().take(100)
        if (clean.isNotEmpty()) {
            updateChat(id) { it.copy(title = clean, titleIsManual = true, updatedAt = System.currentTimeMillis()) }
        }
    }

    fun deleteChat(id: String) {
        resultToken.incrementAndGet()
        val target = _state.value.chats.firstOrNull { it.id == id } ?: return
        if (_state.value.activeChatId == id && _state.value.run.mode != RunMode.IDLE) stopCycle(markFinished = false)
        target.attachments.forEach { runCatching { File(it.path).delete() } }
        var chats = _state.value.chats.filterNot { it.id == id }
        if (chats.isEmpty()) {
            val fresh = ChatSession()
            chats = listOf(fresh)
            _state.value = _state.value.copy(chats = chats, activeChatId = fresh.id, pendingAttachments = emptyList())
        } else {
            val next = if (_state.value.activeChatId == id) sortedChats(chats).first().id else _state.value.activeChatId
            _state.value = _state.value.copy(chats = chats, activeChatId = next, pendingAttachments = emptyList())
        }
        persistChats(chats)
    }

    private fun updateChat(id: String, persist: Boolean = true, transform: (ChatSession) -> ChatSession) {
        val chats = _state.value.chats.map { if (it.id == id) transform(it) else it }
        _state.value = _state.value.copy(chats = chats)
        if (persist) persistChats(chats)
    }

    private fun updateActiveChat(persist: Boolean = true, transform: (ChatSession) -> ChatSession) {
        val id = _state.value.activeChatId ?: return
        updateChat(id, persist, transform)
    }

    fun updateParticipant(config: ModelConfig) {
        val s = _state.value.settings
        val previous = s.participants.firstOrNull { it.id == config.id }
        val updated = s.participants.map { if (it.id == config.id) config.copy(hasApiKey = secrets.has(config.id)) else it }
        val first = if (updated.any { it.id == s.general.firstModelId }) s.general.firstModelId else updated.first().id
        val next = s.copy(participants = updated, general = s.general.copy(firstModelId = first))
        var caps = _state.value.capabilities
        var tests = _state.value.modelTests
        if (previous == null || previous.baseUrl != config.baseUrl || previous.model != config.model || previous.provider != config.provider) {
            caps = caps - config.id
            tests = tests - config.id
        }
        _state.value = _state.value.copy(settings = next, capabilities = caps, modelTests = tests)
        persistSettings(next)
    }

    fun addParticipant() {
        val s = _state.value.settings
        val n = s.participants.size + 1
        val config = ModelConfig(
            id = "m_" + UUID.randomUUID().toString().take(8),
            name = "Модель $n",
            responseColor = participantDefaultColor(s.participants.size)
        )
        val next = s.copy(participants = s.participants + config)
        _state.value = _state.value.copy(settings = next)
        persistSettings(next)
    }

    fun removeParticipant(id: String) {
        val s = _state.value.settings
        if (s.participants.size <= 2) { notice("Должно остаться минимум две модели"); return }
        secrets.remove(id)
        val list = s.participants.filterNot { it.id == id }
        val first = if (list.any { it.id == s.general.firstModelId }) s.general.firstModelId else list.first().id
        val next = s.copy(participants = list, general = s.general.copy(firstModelId = first))
        _state.value = _state.value.copy(
            settings = next,
            capabilities = _state.value.capabilities - id,
            modelTests = _state.value.modelTests - id
        )
        persistSettings(next)
    }

    fun updateResultModel(config: ModelConfig) {
        val s = _state.value.settings
        val previous = s.resultModel
        val next = s.copy(resultModel = config.copy(hasApiKey = secrets.has(config.id)))
        var caps = _state.value.capabilities
        var tests = _state.value.modelTests
        if (previous.baseUrl != config.baseUrl || previous.model != config.model || previous.provider != config.provider) {
            caps = caps - config.id
            tests = tests - config.id
        }
        _state.value = _state.value.copy(settings = next, capabilities = caps, modelTests = tests)
        persistSettings(next)
    }

    fun updateGeneral(general: GeneralSettings) {
        val next = _state.value.settings.copy(general = general)
        _state.value = _state.value.copy(settings = next)
        persistSettings(next)
    }

    fun saveApiKey(modelId: String, key: String) {
        val clean = key.trim()
        if (clean.isNotEmpty()) secrets.put(modelId, clean)
        refreshKeyFlags()
        _state.value = _state.value.copy(modelTests = _state.value.modelTests - modelId)
        persistSettings()
        notice("API-ключ сохранён в Android Keystore")
    }

    fun clearApiKey(modelId: String) {
        secrets.remove(modelId)
        refreshKeyFlags()
        _state.value = _state.value.copy(modelTests = _state.value.modelTests - modelId)
        persistSettings()
        notice("API-ключ удалён")
    }

    fun resetSettings() {
        resultToken.incrementAndGet()
        stopCycle(markFinished = false)
        val defaults = store.resetSettings()
        _state.value = _state.value.copy(settings = defaults, capabilities = emptyMap(), modelTests = emptyMap())
        notice("Настройки сброшены. История чатов сохранена.")
    }

    fun probeCapabilities(modelId: String) {
        val model = findModel(modelId) ?: return
        if (model.model.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val cap = runCatching {
                val slot = slotJson(model, model.systemPrompt)
                val result = LlmApi.capabilities(slot)
                ModelCapability(
                    known = result.optBoolean("known", false),
                    reasoningSupported = result.optBoolean("reasoningSupported", false),
                    reasoningAlwaysOn = result.optBoolean("reasoningAlwaysOn", false),
                    supportedEfforts = result.optJSONArray("supportedEfforts")?.let { arr ->
                        buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                    } ?: emptyList(),
                    temperatureSupported = result.optBoolean("temperatureSupported", true)
                )
            }.getOrElse { ModelCapability() }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(capabilities = _state.value.capabilities + (modelId to cap))
            }
        }
    }

    fun testModel(modelId: String) {
        val model = findModel(modelId) ?: return
        if (model.baseUrl.isBlank() || model.model.isBlank()) {
            _state.value = _state.value.copy(
                modelTests = _state.value.modelTests + (modelId to ModelTestState(success = false, message = "Укажите адрес API и ID модели"))
            )
            return
        }
        _state.value = _state.value.copy(
            modelTests = _state.value.modelTests + (modelId to ModelTestState(checking = true, message = "Проверяем подключение…"))
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val slot = slotJson(model, model.systemPrompt)
                LlmApi.call(slot, "[{\"role\":\"user\",\"content\":\"Ответь одним словом: OK\"}]", true)
            }.getOrElse { LlmApi.fail(it) }
            withContext(Dispatchers.Main) {
                val ok = result.optBoolean("ok")
                val text = if (ok) "Подключение работает" else result.optString("error", "Ошибка подключения")
                _state.value = _state.value.copy(
                    modelTests = _state.value.modelTests + (modelId to ModelTestState(checking = false, success = ok, message = text))
                )
            }
            probeCapabilities(modelId)
        }
    }

    private fun findModel(id: String): ModelConfig? {
        val s = _state.value.settings
        return if (s.resultModel.id == id) s.resultModel else s.participants.firstOrNull { it.id == id }
    }

    suspend fun importAttachments(uris: List<Uri>) {
        val chat = _state.value.activeChat ?: return
        val imported = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> importOne(chat.id, uri) } }
        if (imported.isEmpty()) return
        val chats = _state.value.chats.map {
            if (it.id == chat.id) it.copy(attachments = it.attachments + imported, updatedAt = System.currentTimeMillis()) else it
        }
        _state.value = _state.value.copy(chats = chats, pendingAttachments = _state.value.pendingAttachments + imported)
        persistChats(chats)
    }

    private fun importOne(chatId: String, uri: Uri): AttachmentMeta? {
        val resolver = getApplication<Application>().contentResolver
        var name = "file"
        var size = -1L
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0) ?: name
                size = if (!cursor.isNull(1)) cursor.getLong(1) else -1L
            }
        } catch (_: Exception) { } finally { cursor?.close() }
        if (size > 50L * 1024 * 1024) {
            viewModelScope.launch(Dispatchers.Main) { notice("Файл $name больше 50 МБ") }
            return null
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val id = "f_" + UUID.randomUUID().toString().take(12)
        val dir = File(getApplication<Application>().filesDir, "attachments/$chatId").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_").take(120)
        val file = File(dir, "${id}_$safeName")
        return try {
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } } ?: return null
            AttachmentMeta(id, name, mime, file.length(), file.absolutePath)
        } catch (_: Exception) { null }
    }

    fun removePendingAttachment(id: String) {
        val pending = _state.value.pendingAttachments
        val target = pending.firstOrNull { it.id == id } ?: return
        runCatching { File(target.path).delete() }
        val activeId = _state.value.activeChatId
        val chats = _state.value.chats.map { c ->
            if (c.id == activeId) c.copy(attachments = c.attachments.filterNot { it.id == id }) else c
        }
        _state.value = _state.value.copy(chats = chats, pendingAttachments = pending.filterNot { it.id == id })
        persistChats(chats)
    }

    fun sendAndStart(text: String) {
        if (_state.value.run.mode != RunMode.IDLE) return
        val clean = text.trim()
        val attachments = _state.value.pendingAttachments
        if (clean.isEmpty() && attachments.isEmpty()) return
        val discussionId = "d_" + UUID.randomUUID().toString().take(12)
        addUserMessage(clean, attachments.map { it.id }, discussionId)
        _state.value = _state.value.copy(pendingAttachments = emptyList())
        startCycle(discussionId)
    }

    fun pauseCycle() {
        if (_state.value.run.mode == RunMode.RUNNING) {
            _state.value = _state.value.copy(run = _state.value.run.copy(mode = RunMode.PAUSED))
        }
    }

    fun continueCycle(userText: String) {
        if (_state.value.run.mode != RunMode.PAUSED) return
        val clean = userText.trim()
        val attachments = _state.value.pendingAttachments
        if (clean.isNotEmpty() || attachments.isNotEmpty()) {
            addUserMessage(clean, attachments.map { it.id }, _state.value.run.discussionId)
            _state.value = _state.value.copy(pendingAttachments = emptyList())
        }
        _state.value = _state.value.copy(run = _state.value.run.copy(mode = RunMode.RUNNING))
    }

    fun stopCycle(markFinished: Boolean = true) {
        runToken.incrementAndGet()
        val id = _state.value.activeChatId
        _state.value = _state.value.copy(run = RunProgress())
        if (id != null) {
            updateChat(id) { chat ->
                chat.copy(
                    messages = chat.messages.filterNot { it.inProgress },
                    discussionFinished = if (markFinished) true else chat.discussionFinished,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun addUserMessage(text: String, attachmentIds: List<String>, discussionId: String?) {
        updateActiveChat { chat ->
            val msg = ChatMessage(
                authorId = "user",
                authorName = "Вы",
                text = text,
                attachmentIds = attachmentIds,
                discussionId = discussionId
            )
            val shouldAutoTitle = !chat.titleIsManual && chat.messages.none { it.authorId == "user" }
            val title = if (shouldAutoTitle) makeTitle(text, attachmentIds, chat) else chat.title
            chat.copy(title = title, messages = chat.messages + msg, updatedAt = System.currentTimeMillis(), discussionFinished = false)
        }
    }

    private fun makeTitle(text: String, attachmentIds: List<String>, chat: ChatSession): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isNotEmpty()) return clean.take(48) + if (clean.length > 48) "…" else ""
        val name = chat.attachments.firstOrNull { it.id in attachmentIds }?.name
        return name?.take(48) ?: "Новый чат"
    }

    private fun readyParticipants(): List<ModelConfig> = _state.value.settings.participants.filter {
        it.enabled && it.baseUrl.isNotBlank() && it.model.isNotBlank()
    }

    private fun startCycle(discussionId: String) {
        val models = readyParticipants()
        if (models.size < 2) { notice("Настройте минимум две модели-участника"); return }
        val token = runToken.incrementAndGet()
        _state.value = _state.value.copy(
            run = RunProgress(mode = RunMode.RUNNING, cycle = 1, discussionId = discussionId)
        )
        viewModelScope.launch(Dispatchers.IO) { runDiscussion(token, models, discussionId) }
    }

    private suspend fun runDiscussion(token: Long, baseModels: List<ModelConfig>, discussionId: String) {
        val general = _state.value.settings.general
        val firstIndex = baseModels.indexOfFirst { it.id == general.firstModelId }.let { if (it < 0) 0 else it }
        val models = baseModels.drop(firstIndex) + baseModels.take(firstIndex)
        val baselineIds = _state.value.activeChat
            ?.messages
            ?.filterNot { it.inProgress }
            ?.map { it.id }
            ?.toSet()
            .orEmpty()

        for (cycle in 1..general.rounds) {
            for (model in models) {
                if (runToken.get() != token) return
                waitIfPaused(token)
                if (runToken.get() != token) return

                val chat = _state.value.activeChat ?: return
                val contextChat = if (cycle == 1) {
                    chat.copy(
                        messages = chat.messages.filter { message ->
                            !message.inProgress && (
                                message.id in baselineIds ||
                                    (message.authorId == "user" && message.discussionId == discussionId)
                                )
                        }
                    )
                } else {
                    chat
                }
                val chatId = chat.id
                val messageId = withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        run = _state.value.run.copy(cycle = cycle, currentModelId = model.id, discussionId = discussionId)
                    )
                    appendProgressMessage(chatId, model, cycle, isResult = false, discussionId = discussionId)
                }
                val result = askModel(model, contextChat, cycle = cycle) { text ->
                    if (runToken.get() == token) {
                        viewModelScope.launch(Dispatchers.Main) { updateProgressMessage(chatId, messageId, text) }
                    }
                }
                if (runToken.get() != token) return

                withContext(Dispatchers.Main) {
                    if (result.optBoolean("ok")) {
                        finalizeProgressMessage(chatId, messageId, result.optString("text"), error = false)
                    } else {
                        val error = result.optString("error", "Ошибка модели")
                        finalizeProgressMessage(chatId, messageId, error, error = true)
                        notice("Цикл остановлен: $error")
                        stopCycle(markFinished = true)
                    }
                }
                if (!result.optBoolean("ok")) return
            }
        }
        if (runToken.get() == token) withContext(Dispatchers.Main) {
            _state.value = _state.value.copy(run = RunProgress())
            updateActiveChat { it.copy(discussionFinished = true, updatedAt = System.currentTimeMillis()) }
        }
    }

    private suspend fun waitIfPaused(token: Long) {
        while (runToken.get() == token && _state.value.run.mode == RunMode.PAUSED) delay(120)
    }

    private fun appendProgressMessage(
        chatId: String,
        model: ModelConfig,
        cycle: Int?,
        isResult: Boolean,
        discussionId: String?
    ): String {
        val id = UUID.randomUUID().toString()
        updateChat(chatId, persist = false) { chat ->
            chat.copy(
                messages = chat.messages + ChatMessage(
                    id = id,
                    authorId = model.id,
                    authorName = model.name.ifBlank { "Модель" },
                    text = "",
                    cycle = cycle,
                    isResult = isResult,
                    inProgress = true,
                    discussionId = discussionId
                ),
                updatedAt = System.currentTimeMillis()
            )
        }
        return id
    }

    private fun updateProgressMessage(chatId: String, messageId: String, text: String) {
        updateChat(chatId, persist = false) { chat ->
            chat.copy(messages = chat.messages.map { if (it.id == messageId) it.copy(text = text, inProgress = true) else it })
        }
    }

    private fun finalizeProgressMessage(chatId: String, messageId: String, text: String, error: Boolean) {
        updateChat(chatId) { chat ->
            chat.copy(
                messages = chat.messages.map { if (it.id == messageId) it.copy(text = text, error = error, inProgress = false) else it },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun removeProgressMessage(chatId: String, messageId: String) {
        updateChat(chatId, persist = false) { chat -> chat.copy(messages = chat.messages.filterNot { it.id == messageId }) }
    }

    fun generateResult() {
        val model = _state.value.settings.resultModel
        if (model.baseUrl.isBlank() || model.model.isBlank()) {
            notice("Настройте модель результата в настройках")
            return
        }
        val chat = _state.value.activeChat ?: return
        if (chat.messages.any { it.isResult && it.inProgress }) return
        val discussionId = chat.messages
            .asReversed()
            .firstOrNull { !it.isResult && !it.error && !it.inProgress && it.discussionId != null }
            ?.discussionId
        val token = resultToken.incrementAndGet()
        val chatId = chat.id
        val messageId = appendProgressMessage(chatId, model, null, isResult = true, discussionId = discussionId)
        viewModelScope.launch(Dispatchers.IO) {
            val result = askResultModel(model, chat, discussionId) { text ->
                if (resultToken.get() == token) {
                    viewModelScope.launch(Dispatchers.Main) { updateProgressMessage(chatId, messageId, text) }
                }
            }
            if (resultToken.get() != token) return@launch
            withContext(Dispatchers.Main) {
                if (result.optBoolean("ok")) {
                    finalizeProgressMessage(chatId, messageId, result.optString("text"), error = false)
                } else {
                    removeProgressMessage(chatId, messageId)
                    notice(result.optString("error", "Не удалось получить результат"))
                }
            }
        }
    }

    private fun askResultModel(
        model: ModelConfig,
        chat: ChatSession,
        discussionId: String?,
        onProgress: (String) -> Unit
    ): JSONObject {
        val g = _state.value.settings.general
        val scoped = if (discussionId == null) {
            chat.messages.filterNot { it.inProgress }
        } else {
            chat.messages.filter { !it.inProgress && (it.authorId == "user" || it.discussionId == discussionId) }
        }
        val maxCycle = scoped
            .filter { discussionId == null || it.discussionId == discussionId }
            .mapNotNull { it.cycle }
            .maxOrNull() ?: 0
        val minCycle = if (g.resultUseAllCycles) 0 else (maxCycle - g.resultContextCycles + 1).coerceAtLeast(1)
        val selected = scoped.filter { m ->
            when {
                m.authorId == "user" -> true
                m.isResult || m.cycle == null -> false
                discussionId != null && m.discussionId != discussionId -> false
                g.resultUseAllCycles -> true
                m.cycle == 1 -> true
                else -> m.cycle >= minCycle
            }
        }
        return askModel(model, chat.copy(messages = selected), resultMode = true, onProgress = onProgress)
    }

    private fun askModel(
        model: ModelConfig,
        chat: ChatSession,
        resultMode: Boolean = false,
        cycle: Int? = null,
        onProgress: (String) -> Unit = {}
    ): JSONObject {
        val started = System.currentTimeMillis()
        val system = buildSystem(model, chat, resultMode, cycle)
        val slot = slotJson(model, system)
        val messages = buildMessages(model, chat)
        var accumulated = ""
        var fileHops = 0
        var continuationHops = 0
        val usage = JSONObject()
        var lastUiPush = 0L

        fun publish(text: String, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (force || now - lastUiPush >= 45L) {
                lastUiPush = now
                onProgress(text)
            }
        }

        while (true) {
            val current = StringBuilder()
            val res = try {
                StreamingLlmApi.call(slot, messages.toString()) { delta ->
                    current.append(delta)
                    val live = current.toString()
                    if (!containsAttachmentCommand(live)) publish(accumulated + live)
                }
            } catch (e: Exception) {
                LlmApi.fail(e)
            }
            if (!res.optBoolean("ok")) return res
            mergeUsage(usage, res.optJSONObject("usage"))
            val text = res.optString("text").ifBlank { current.toString() }
            val requested = parseAttachmentRequest(text)
            if (requested.isNotEmpty()) {
                if (++fileHops > 12) return LlmApi.fail(Exception("Модель слишком много раз запрашивает вложения"))
                messages.put(JSONObject().put("role", "assistant").put("content", text))
                messages.put(requestedAttachmentMessage(requested, chat))
                continue
            }

            accumulated += text
            publish(accumulated, force = true)
            if (finishWasLength(res.optString("finishReason"))) {
                if (++continuationHops > 64) break
                messages.put(JSONObject().put("role", "assistant").put("content", text))
                messages.put(JSONObject().put("role", "user").put("content", "Продолжи ответ точно с места обрыва. Не повторяй уже написанное."))
                continue
            }
            break
        }
        return JSONObject()
            .put("ok", true)
            .put("text", accumulated)
            .put("ms", System.currentTimeMillis() - started)
            .put("usage", usage)
    }

    private fun slotJson(model: ModelConfig, system: String): JSONObject = JSONObject().apply {
        put("provider", model.provider)
        put("baseUrl", model.baseUrl.trim())
        put("model", model.model.trim())
        put("apiKey", secrets.get(model.id))
        put("system", system)
        put("temperatureEnabled", model.temperatureEnabled)
        put("temperature", model.temperature)
        put("timeoutSec", model.timeoutSec)
        put("reasoningEnabled", model.reasoningEnabled)
        put("reasoningEffort", model.reasoningEffort)
        put("reasoningBudget", model.reasoningBudget)
        put("webSearchEnabled", model.webSearchEnabled)
        put("webSearchEngine", model.webSearchEngine)
        put("webSearchMaxCalls", model.webSearchMaxCalls.coerceIn(1, 100))
        _state.value.capabilities[model.id]?.let { cap ->
            put("reasoningAvailable", cap.reasoningSupported)
            put("reasoningAlwaysOn", cap.reasoningAlwaysOn)
            put("temperatureAvailable", cap.temperatureSupported)
        }
    }

    private fun buildSystem(model: ModelConfig, chat: ChatSession, resultMode: Boolean, cycle: Int?): String {
        val participants = _state.value.settings.participants.filter { it.enabled }.joinToString(", ") { it.name }
        val general = _state.value.settings.general
        val base = if (resultMode) {
            "Ты формируешь результат завершённой дискуссии. Не продолжай спор и не защищай позицию участников. " +
                "Пользователь должен получить конечный продукт, соответствующий исходной задаче."
        } else {
            "Ты — ${model.name}. В общей работе участвуют пользователь и модели: $participants. Отвечай только от себя."
        }
        val stage = when {
            resultMode -> ""
            cycle == 1 -> general.firstCyclePrompt.trim()
            else -> general.laterCyclesPrompt.trim()
        }
        val searchRule = if (model.webSearchEnabled) {
            "\n\nИнтернет-поиск доступен. Используй его только когда для задачи действительно нужны актуальные, проверяемые или неизвестные тебе факты. Не ищи в интернете ради самого поиска."
        } else ""
        return buildString {
            append(base)
            if (model.systemPrompt.isNotBlank()) append("\n\n").append(model.systemPrompt.trim())
            if (stage.isNotBlank()) append("\n\n").append(stage)
            append(searchRule)
            append(attachmentCatalog(chat))
        }
    }

    private fun attachmentCatalog(chat: ChatSession): String {
        if (chat.attachments.isEmpty()) return ""
        val lines = chat.attachments.joinToString("\n") { "- ID=${it.id} · ${it.name} · ${it.mime}" }
        return "\n\nВ этом чате есть вложения. Их содержимое не передаётся автоматически.\n$lines\n" +
            "Если содержимое действительно нужно, ответь ТОЛЬКО командой [[DISPUTEAI_FILE:ID]]. " +
            "Для нескольких файлов: [[DISPUTEAI_FILE:ID1,ID2]]. Приложение передаст только запрошенные файлы на один следующий вызов."
    }

    private fun buildMessages(model: ModelConfig, chat: ChatSession): JSONArray {
        val out = JSONArray()
        chat.messages.filterNot { it.error || it.inProgress }.forEach { m ->
            val role = if (m.authorId == model.id) "assistant" else "user"
            val prefix = if (m.authorId == model.id) "" else "${if (m.isResult) "Результат предыдущей дискуссии" else m.authorName}: "
            var text = prefix + m.text
            if (m.attachmentIds.isNotEmpty()) {
                val metas = m.attachmentIds.mapNotNull { id -> chat.attachments.firstOrNull { it.id == id } }
                    .joinToString("\n") { "[Вложение: ${it.name}; ID=${it.id}; ${it.mime}; содержимое доступно по запросу]" }
                if (metas.isNotBlank()) text += "\n$metas"
            }
            val last = out.optJSONObject(out.length() - 1)
            if (last != null && last.optString("role") == role && last.opt("content") is String) {
                last.put("content", last.optString("content") + "\n\n" + text)
            } else {
                out.put(JSONObject().put("role", role).put("content", text))
            }
        }
        if (out.length() == 0) out.put(JSONObject().put("role", "user").put("content", "Начни обсуждение."))
        return out
    }

    private fun containsAttachmentCommand(text: String): Boolean =
        text.contains("[[DISPUTEAI_FILE:", ignoreCase = true)

    private fun parseAttachmentRequest(text: String): List<String> {
        val ids = linkedSetOf<String>()
        Regex("\\[\\[DISPUTEAI_FILE:([^]]+)]]", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(ids::add)
        }
        return ids.toList()
    }

    private fun requestedAttachmentMessage(ids: List<String>, chat: ChatSession): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "Вот содержимое запрошенных вложений. Используй его для ответа."))
        ids.forEach { id ->
            val a = chat.attachments.firstOrNull { it.id == id }
            if (a == null) {
                content.put(JSONObject().put("type", "text").put("text", "[Вложение ID=$id не найдено]"))
                return@forEach
            }
            val file = File(a.path)
            if (!file.exists()) {
                content.put(JSONObject().put("type", "text").put("text", "[Файл ${a.name} недоступен]"))
                return@forEach
            }
            if (a.mime.startsWith("text/") || a.name.endsWith(".md", true) || a.name.endsWith(".json", true) || a.name.endsWith(".csv", true)) {
                val text = runCatching { file.readText().take(1_500_000) }.getOrElse { "[Не удалось прочитать файл]" }
                content.put(JSONObject().put("type", "text").put("text", "\n[Файл ${a.name}; ID=${a.id}]\n$text"))
            } else {
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val dataUrl = "data:${a.mime};base64,$encoded"
                if (a.mime.startsWith("image/")) {
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl)))
                } else {
                    content.put(JSONObject().put("type", "file").put("file", JSONObject().put("filename", a.name).put("file_data", dataUrl)))
                }
            }
        }
        return JSONObject().put("role", "user").put("content", content)
    }

    private fun finishWasLength(reason: String): Boolean = reason.lowercase() in setOf(
        "length", "max_tokens", "max_output_tokens", "max_tokens_reached"
    )

    private fun mergeUsage(total: JSONObject, next: JSONObject?) {
        if (next == null) return
        next.keys().forEach { key ->
            val v = next.opt(key)
            if (v is Number) total.put(key, total.optDouble(key, 0.0) + v.toDouble())
            else if (!total.has(key)) total.put(key, v)
        }
    }
}
