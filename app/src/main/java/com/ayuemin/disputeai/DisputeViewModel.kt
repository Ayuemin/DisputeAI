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
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class DisputeViewModel(app: Application) : AndroidViewModel(app) {
    private val secrets = SecretStore(app)
    private val store = AppStore(app, secrets)
    private val runToken = AtomicLong(0)
    private val resultToken = AtomicLong(0)

    private enum class ParticipationState { ACTIVE, DONE_PENDING, DONE }
    private enum class DiscussionProtocolMode { NONE, NORMAL, CONFIRM_DONE, FINAL_SOLO }
    private enum class DiscussionSignal { CONTINUE, DONE }
    private data class ProtocolReply(val visibleText: String, val signal: DiscussionSignal)

    private val statusMarkerRegex = Regex(
        "\\[\\[\\s*DISPUTEAI_STATUS\\s*:\\s*(DONE|CONTINUE)\\s*]]",
        RegexOption.IGNORE_CASE
    )

    private val loadedChats = store.loadChats()
    private val _state = MutableStateFlow(
        UiState(
            settings = loadedChats.firstOrNull()?.settings ?: AppSettings(),
            chats = loadedChats,
            apiProfiles = store.loadApiProfiles()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (_state.value.chats.isEmpty()) {
            newChat()
        } else {
            val id = sortedChats(_state.value.chats).first().id
            val active = _state.value.chats.first { it.id == id }
            _state.value = _state.value.copy(activeChatId = id, settings = active.settings)
        }
        refreshKeyFlags()
    }

    private fun sortedChats(chats: List<ChatSession>): List<ChatSession> =
        chats.sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })

    private fun persistChats(chats: List<ChatSession> = _state.value.chats) = store.saveChats(chats)
    private fun persistSettings(settings: AppSettings = _state.value.settings) {
        val id = _state.value.activeChatId ?: return
        val chats = _state.value.chats.map { chat ->
            if (chat.id == id) chat.copy(settings = settings, updatedAt = System.currentTimeMillis()) else chat
        }
        _state.value = _state.value.copy(chats = chats, settings = settings)
        persistChats(chats)
    }

    private fun persistApiProfiles(profiles: List<ApiProfile> = _state.value.apiProfiles) = store.saveApiProfiles(profiles)

    private fun refreshKeyFlags() {
        val profiles = _state.value.apiProfiles.map { it.copy(hasApiKey = secrets.has(it.id)) }
        _state.value = _state.value.copy(apiProfiles = profiles)
    }

    fun consumeNotice() { _state.value = _state.value.copy(notice = null) }
    private fun notice(text: String) { _state.value = _state.value.copy(notice = text) }

    override fun onCleared() {
        DiscussionForegroundService.stop(getApplication())
        super.onCleared()
    }

    fun newChat() {
        resultToken.incrementAndGet()
        stopCycle(markFinished = false)
        val chat = ChatSession(settings = AppSettings())
        val chats = listOf(chat) + _state.value.chats
        _state.value = _state.value.copy(chats = chats, activeChatId = chat.id, settings = chat.settings, pendingAttachments = emptyList(), capabilities = emptyMap(), modelTests = emptyMap())
        persistChats(chats)
    }

    fun selectChat(id: String) {
        resultToken.incrementAndGet()
        if (_state.value.run.mode != RunMode.IDLE) stopCycle(markFinished = true)
        val target = _state.value.chats.firstOrNull { it.id == id } ?: return
        DiscussionForegroundService.stop(getApplication())
        _state.value = _state.value.copy(
            activeChatId = id,
            settings = target.settings,
            pendingAttachments = emptyList(),
            capabilities = emptyMap(),
            modelTests = emptyMap()
        )
    }

    fun pinChat(id: String, pinned: Boolean) = updateChat(id) { it.copy(pinned = pinned, updatedAt = System.currentTimeMillis()) }

    fun renameChat(id: String, title: String) {
        val clean = title.trim().take(100)
        if (clean.isNotEmpty()) {
            updateChat(id) { it.copy(title = clean, titleIsManual = true, updatedAt = System.currentTimeMillis()) }
        }
    }

    fun deleteChat(id: String) {
        val target = _state.value.chats.firstOrNull { it.id == id } ?: return
        if (_state.value.activeChatId == id) {
            resultToken.incrementAndGet()
            if (_state.value.run.mode != RunMode.IDLE) stopCycle(markFinished = false)
            else DiscussionForegroundService.stop(getApplication())
        }
        target.attachments.forEach { runCatching { File(it.path).delete() } }
        var chats = _state.value.chats.filterNot { it.id == id }
        if (chats.isEmpty()) {
            val fresh = ChatSession()
            chats = listOf(fresh)
            _state.value = _state.value.copy(chats = chats, activeChatId = fresh.id, settings = fresh.settings, pendingAttachments = emptyList(), capabilities = emptyMap(), modelTests = emptyMap())
        } else {
            val next = if (_state.value.activeChatId == id) sortedChats(chats).first().id else _state.value.activeChatId
            val nextSettings = chats.firstOrNull { it.id == next }?.settings ?: AppSettings()
            _state.value = _state.value.copy(chats = chats, activeChatId = next, settings = nextSettings, pendingAttachments = emptyList(), capabilities = emptyMap(), modelTests = emptyMap())
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
        if (previous == null || previous.apiProfileId != config.apiProfileId || previous.model != config.model) {
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
        if (previous.apiProfileId != config.apiProfileId || previous.model != config.model) {
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

    fun addApiProfile() {
        val n = _state.value.apiProfiles.size + 1
        val profile = ApiProfile(
            id = "api_" + UUID.randomUUID().toString().take(10),
            name = "Подключение $n"
        )
        val profiles = _state.value.apiProfiles + profile
        _state.value = _state.value.copy(apiProfiles = profiles)
        persistApiProfiles(profiles)
    }

    fun updateApiProfile(profile: ApiProfile) {
        val profiles = _state.value.apiProfiles.map {
            if (it.id == profile.id) profile.copy(hasApiKey = secrets.has(profile.id)) else it
        }
        _state.value = _state.value.copy(apiProfiles = profiles, capabilities = emptyMap(), modelTests = emptyMap())
        persistApiProfiles(profiles)
    }

    fun saveApiProfileKey(id: String, key: String) {
        val clean = key.trim()
        if (clean.isBlank()) return
        secrets.put(id, clean)
        refreshKeyFlags()
        notice("API-ключ сохранён в Android Keystore")
    }

    fun clearApiProfileKey(id: String) {
        secrets.remove(id)
        refreshKeyFlags()
        notice("API-ключ удалён")
    }

    fun deleteApiProfile(id: String) {
        val inUse = _state.value.chats.any { chat ->
            chat.settings.participants.any { it.apiProfileId == id } || chat.settings.resultModel.apiProfileId == id
        }
        if (inUse) {
            notice("Подключение используется в настройках одного из чатов")
            return
        }
        secrets.remove(id)
        val profiles = _state.value.apiProfiles.filterNot { it.id == id }
        _state.value = _state.value.copy(apiProfiles = profiles)
        persistApiProfiles(profiles)
    }

    fun clearActiveDiscussion() {
        resultToken.incrementAndGet()
        stopCycle(markFinished = false)
        val id = _state.value.activeChatId ?: return
        val chat = _state.value.chats.firstOrNull { it.id == id } ?: return
        chat.attachments.forEach { runCatching { File(it.path).delete() } }
        updateChat(id) { current ->
            current.copy(
                messages = emptyList(),
                attachments = emptyList(),
                discussionFinished = false,
                updatedAt = System.currentTimeMillis()
            )
        }
        _state.value = _state.value.copy(pendingAttachments = emptyList())
        notice("Дискуссия очищена. Настройки чата сохранены.")
    }

    private fun apiProfileFor(model: ModelConfig): ApiProfile? =
        _state.value.apiProfiles.firstOrNull { it.id == model.apiProfileId }

    private fun isModelConfigured(model: ModelConfig): Boolean =
        model.model.isNotBlank() && apiProfileFor(model)?.baseUrl?.isNotBlank() == true

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
        if (!isModelConfigured(model)) {
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
        DiscussionForegroundService.stop(getApplication())
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
        it.enabled && isModelConfigured(it)
    }

    private fun startCycle(discussionId: String) {
        val models = readyParticipants()
        if (models.size < 2) { notice("Настройте минимум две модели-участника"); return }
        val token = runToken.incrementAndGet()
        _state.value = _state.value.copy(
            run = RunProgress(mode = RunMode.RUNNING, cycle = 1, discussionId = discussionId)
        )
        DiscussionForegroundService.start(getApplication(), "Идёт дискуссия · цикл 1")
        viewModelScope.launch(Dispatchers.IO) { runDiscussion(token, models, discussionId) }
    }

    private suspend fun runDiscussion(token: Long, baseModels: List<ModelConfig>, discussionId: String) {
        val general = _state.value.settings.general
        val firstIndex = baseModels.indexOfFirst { it.id == general.firstModelId }.let { if (it < 0) 0 else it }
        val models = baseModels.drop(firstIndex) + baseModels.take(firstIndex)
        val states = models.associate { it.id to ParticipationState.ACTIVE }.toMutableMap()
        var knownUserMessages = discussionUserMessageCount(discussionId)
        var discussionComplete = false

        for (cycle in 1..general.rounds) {
            DiscussionForegroundService.update(getApplication(), "Идёт дискуссия · цикл $cycle")
            var activeAtCycleStart = models.filter { states[it.id] != ParticipationState.DONE }
            if (activeAtCycleStart.isEmpty()) break
            var soloModelId = if (cycle > 1 && activeAtCycleStart.size == 1 && states[activeAtCycleStart.first().id] == ParticipationState.ACTIVE) {
                activeAtCycleStart.first().id
            } else null
            var finalSoloWasRun = false

            for (model in models) {
                if (runToken.get() != token) return
                waitIfPaused(token)
                if (runToken.get() != token) return

                val nowUserMessages = discussionUserMessageCount(discussionId)
                if (nowUserMessages > knownUserMessages) {
                    states.keys.forEach { states[it] = ParticipationState.ACTIVE }
                    knownUserMessages = nowUserMessages
                    soloModelId = null
                    activeAtCycleStart = models
                }

                val state = states[model.id] ?: ParticipationState.ACTIVE
                if (state == ParticipationState.DONE) continue

                val protocolMode = when {
                    cycle == 1 -> DiscussionProtocolMode.NONE
                    state == ParticipationState.DONE_PENDING -> DiscussionProtocolMode.CONFIRM_DONE
                    soloModelId == model.id -> DiscussionProtocolMode.FINAL_SOLO
                    else -> DiscussionProtocolMode.NORMAL
                }

                val chat = _state.value.activeChat ?: return
                val contextChat = discussionContextChat(chat, discussionId, cycle)
                val chatId = chat.id
                val messageId = withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        run = _state.value.run.copy(cycle = cycle, currentModelId = model.id, discussionId = discussionId)
                    )
                    appendProgressMessage(chatId, model, cycle, isResult = false, discussionId = discussionId)
                }
                val result = askModel(model, contextChat, cycle = cycle, protocolMode = protocolMode) { text ->
                    if (runToken.get() == token && protocolMode != DiscussionProtocolMode.CONFIRM_DONE) {
                        val visible = stripProtocolForDisplay(text)
                        viewModelScope.launch(Dispatchers.Main) { updateProgressMessage(chatId, messageId, visible) }
                    }
                }
                if (runToken.get() != token) return

                if (!result.optBoolean("ok")) {
                    val error = result.optString("error", "Ошибка модели")
                    withContext(Dispatchers.Main) {
                        finalizeProgressMessage(chatId, messageId, error, error = true)
                        notice("Цикл остановлен: $error")
                        stopCycle(markFinished = true)
                    }
                    return
                }

                val reply = parseProtocolReply(result.optString("text"), protocolMode)
                when (protocolMode) {
                    DiscussionProtocolMode.NONE -> {
                        withContext(Dispatchers.Main) {
                            if (reply.visibleText.isBlank()) removeProgressMessage(chatId, messageId)
                            else finalizeProgressMessage(chatId, messageId, reply.visibleText, error = false)
                        }
                    }
                    DiscussionProtocolMode.NORMAL -> {
                        val acceptedDone = reply.signal == DiscussionSignal.DONE && reply.visibleText.isNotBlank()
                        states[model.id] = if (acceptedDone) ParticipationState.DONE_PENDING else ParticipationState.ACTIVE
                        withContext(Dispatchers.Main) {
                            if (reply.visibleText.isBlank()) removeProgressMessage(chatId, messageId)
                            else finalizeProgressMessage(chatId, messageId, reply.visibleText, error = false)
                        }
                    }
                    DiscussionProtocolMode.CONFIRM_DONE -> {
                        if (reply.signal == DiscussionSignal.DONE) {
                            states[model.id] = ParticipationState.DONE
                            withContext(Dispatchers.Main) { removeProgressMessage(chatId, messageId) }
                        } else {
                            states[model.id] = ParticipationState.ACTIVE
                            withContext(Dispatchers.Main) {
                                if (reply.visibleText.isBlank()) removeProgressMessage(chatId, messageId)
                                else finalizeProgressMessage(chatId, messageId, reply.visibleText, error = false)
                            }
                        }
                    }
                    DiscussionProtocolMode.FINAL_SOLO -> {
                        states[model.id] = ParticipationState.ACTIVE
                        finalSoloWasRun = true
                        withContext(Dispatchers.Main) {
                            if (reply.visibleText.isBlank()) removeProgressMessage(chatId, messageId)
                            else finalizeProgressMessage(chatId, messageId, reply.visibleText, error = false)
                        }
                    }
                }
            }

            val remaining = states.values.count { it != ParticipationState.DONE }
            if (remaining == 0 || finalSoloWasRun) {
                discussionComplete = true
                break
            }
        }

        if (runToken.get() == token) withContext(Dispatchers.Main) {
            _state.value = _state.value.copy(run = RunProgress())
            updateActiveChat {
                it.copy(
                    discussionFinished = true,
                    updatedAt = System.currentTimeMillis()
                )
            }
            DiscussionForegroundService.stop(getApplication())
            if (discussionComplete) notice("Обсуждение завершено: существенных новых возражений не осталось.")
        }
    }

    private fun discussionContextChat(chat: ChatSession, discussionId: String, cycle: Int): ChatSession {
        val g = _state.value.settings.general
        val scoped = chat.messages.filter {
            !it.inProgress && !it.isResult && it.discussionId == discussionId
        }

        val selected = if (cycle <= 1) {
            scoped.filter { it.authorId == "user" }
        } else {
            val maxCycle = scoped.mapNotNull { it.cycle }.maxOrNull() ?: 1
            val minRecentCycle = if (g.discussionUseAllCycles) {
                2
            } else {
                (maxCycle - g.discussionContextCycles + 1).coerceAtLeast(2)
            }
            scoped.filter { message ->
                when {
                    message.authorId == "user" -> true
                    message.cycle == null -> false
                    message.cycle == 1 -> true
                    g.discussionUseAllCycles -> true
                    else -> message.cycle >= minRecentCycle
                }
            }
        }

        val attachmentIds = selected.flatMap { it.attachmentIds }.toSet()
        return chat.copy(
            messages = selected,
            attachments = chat.attachments.filter { it.id in attachmentIds }
        )
    }

    private fun discussionUserMessageCount(discussionId: String): Int =
        _state.value.activeChat?.messages?.count { it.authorId == "user" && it.discussionId == discussionId } ?: 0

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
        if (!isModelConfigured(model)) {
            notice("Настройте модель результата в настройках чата")
            return
        }
        val chat = _state.value.activeChat ?: return
        if (chat.messages.any { it.isResult && it.inProgress }) return
        val discussionId = chat.messages
            .asReversed()
            .firstOrNull { !it.isResult && !it.error && !it.inProgress && it.discussionId != null }
            ?.discussionId
        val token = resultToken.incrementAndGet()
        DiscussionForegroundService.start(getApplication(), "Формируется итоговый результат")
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
                DiscussionForegroundService.stop(getApplication())
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
            chat.messages.filter { !it.inProgress && it.discussionId == discussionId }
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
        val attachmentIds = selected.flatMap { it.attachmentIds }.toSet()
        val contextChat = chat.copy(
            messages = selected,
            attachments = chat.attachments.filter { it.id in attachmentIds }
        )
        return askModel(model, contextChat, resultMode = true, onProgress = onProgress)
    }

    private fun askModel(
        model: ModelConfig,
        chat: ChatSession,
        resultMode: Boolean = false,
        cycle: Int? = null,
        protocolMode: DiscussionProtocolMode = DiscussionProtocolMode.NONE,
        onProgress: (String) -> Unit = {}
    ): JSONObject {
        val started = System.currentTimeMillis()
        val system = buildSystem(model, chat, resultMode, cycle, protocolMode)
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
        val profile = apiProfileFor(model)
        put("provider", profile?.provider ?: model.provider)
        put("baseUrl", profile?.baseUrl?.trim().orEmpty())
        put("model", model.model.trim())
        put("apiKey", profile?.id?.let(secrets::get).orEmpty())
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

    private fun buildSystem(
        model: ModelConfig,
        chat: ChatSession,
        resultMode: Boolean,
        cycle: Int?,
        protocolMode: DiscussionProtocolMode
    ): String {
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
        val protocol = if (resultMode) "" else discussionProtocolPrompt(protocolMode)
        return buildString {
            append(base)
            if (model.systemPrompt.isNotBlank()) append("\n\n").append(model.systemPrompt.trim())
            if (stage.isNotBlank()) append("\n\n").append(stage)
            if (protocol.isNotBlank()) append("\n\n").append(protocol)
            append(searchRule)
            append(attachmentCatalog(chat))
        }
    }

    private fun discussionProtocolPrompt(mode: DiscussionProtocolMode): String = when (mode) {
        DiscussionProtocolMode.NONE -> ""
        DiscussionProtocolMode.NORMAL ->
            "СЛУЖЕБНЫЙ ПРОТОКОЛ DISPUTEAI. После содержательной части ответа обязательно поставь ровно один маркер: " +
                "[[DISPUTEAI_STATUS:CONTINUE]] — если после внимательной проверки ответов других участников у тебя остаётся существенное возражение, исправление, новая идея или полезное дополнение; " +
                "[[DISPUTEAI_STATUS:DONE]] — если существенных замечаний и дополнений больше нет. " +
                "Не выбирай DONE ради согласия или чтобы закончить быстрее. В первый раз, когда выбираешь DONE, всё равно дай перед маркером нормальную полезную реакцию на текущий круг; не отвечай одним DONE. " +
                "Если сомневаешься — выбирай CONTINUE. Маркер служебный: не объясняй его пользователю."
        DiscussionProtocolMode.CONFIRM_DONE ->
            "СЛУЖЕБНЫЙ ПРОТОКОЛ DISPUTEAI. В предыдущем круге ты уже сообщил, что существенных дополнений больше нет. Сейчас проверь только новые ответы, появившиеся после твоей прошлой реплики. " +
                "Если появился новый аргумент, ошибка, важное упущение или причина изменить позицию — дай только необходимый содержательный ответ и заверши его [[DISPUTEAI_STATUS:CONTINUE]]. " +
                "Если по-прежнему нечего существенно добавить — ничего не повторяй, не пиши пояснений и ответь только [[DISPUTEAI_STATUS:DONE]]. " +
                "Если сомневаешься — выбирай CONTINUE."
        DiscussionProtocolMode.FINAL_SOLO ->
            "Ты остался единственным активным участником обсуждения. Сделай один последний содержательный проход: учти последние полезные реплики выбывших участников, исправь только действительно важное и сформулируй свою финальную позицию без повторов. Служебный статус больше не нужен."
    }

    private fun parseProtocolReply(raw: String, mode: DiscussionProtocolMode): ProtocolReply {
        var signal = DiscussionSignal.CONTINUE
        val markers = statusMarkerRegex.findAll(raw).toList()
        markers.lastOrNull()?.groupValues?.getOrNull(1)?.let { value ->
            signal = if (value.equals("DONE", ignoreCase = true)) DiscussionSignal.DONE else DiscussionSignal.CONTINUE
        }

        if (mode == DiscussionProtocolMode.CONFIRM_DONE && markers.isEmpty()) {
            val compact = raw.trim().uppercase(Locale.ROOT).replace(" ", "")
            if (compact in setOf("DONE", "DONE.", "[[DONE]]", "[DONE]")) signal = DiscussionSignal.DONE
        }

        var visible = stripProtocolForDisplay(raw).trim()
        when (mode) {
            DiscussionProtocolMode.NONE, DiscussionProtocolMode.FINAL_SOLO -> signal = DiscussionSignal.CONTINUE
            DiscussionProtocolMode.NORMAL -> {
                if (signal == DiscussionSignal.DONE && visible.isBlank()) signal = DiscussionSignal.CONTINUE
            }
            DiscussionProtocolMode.CONFIRM_DONE -> {
                if (signal == DiscussionSignal.DONE) visible = ""
            }
        }
        return ProtocolReply(visible, signal)
    }

    private fun stripProtocolForDisplay(text: String): String {
        var cleaned = statusMarkerRegex.replace(text, "")
        val upper = cleaned.uppercase(Locale.ROOT)
        val open = upper.lastIndexOf("[[")
        if (open >= 0) {
            val tail = upper.substring(open)
            val target = "[[DISPUTEAI_STATUS"
            if (target.startsWith(tail) || tail.startsWith(target)) {
                cleaned = cleaned.substring(0, open)
            }
        }
        return cleaned.trimEnd()
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
