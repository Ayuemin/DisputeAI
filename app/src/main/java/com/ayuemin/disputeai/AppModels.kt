package com.ayuemin.disputeai

import java.util.UUID

data class ModelConfig(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val provider: String = "openai",
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val systemPrompt: String = DEFAULT_PARTICIPANT_PROMPT,
    val temperatureEnabled: Boolean = false,
    val temperature: Double = 0.8,
    val timeoutSec: Int = 180,
    val reasoningEnabled: Boolean = false,
    val reasoningEffort: String = "auto",
    val reasoningBudget: Int = 0,
    val responseColor: Int = DEFAULT_MODEL_COLOR
)

data class GeneralSettings(
    val rounds: Int = 3,
    val firstModelId: String = "m1",
    val resultContextCycles: Int = 3,
    val resultUseAllCycles: Boolean = false
)

data class AppSettings(
    val participants: List<ModelConfig> = defaultParticipants(),
    val resultModel: ModelConfig = defaultResultModel(),
    val general: GeneralSettings = GeneralSettings()
)

data class ModelCapability(
    val known: Boolean = false,
    val reasoningSupported: Boolean = false,
    val reasoningAlwaysOn: Boolean = false,
    val supportedEfforts: List<String> = emptyList(),
    val temperatureSupported: Boolean = true
)

data class ModelTestState(
    val checking: Boolean = false,
    val success: Boolean? = null,
    val message: String = ""
)

data class AttachmentMeta(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val path: String
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val authorName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentIds: List<String> = emptyList(),
    val cycle: Int? = null,
    val isResult: Boolean = false,
    val error: Boolean = false,
    val inProgress: Boolean = false
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый чат",
    val titleIsManual: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val attachments: List<AttachmentMeta> = emptyList(),
    val discussionFinished: Boolean = false
)

enum class RunMode { IDLE, RUNNING, PAUSED }

data class RunProgress(
    val mode: RunMode = RunMode.IDLE,
    val cycle: Int = 0,
    val currentModelId: String? = null,
    val stopRequested: Boolean = false
)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val chats: List<ChatSession> = emptyList(),
    val activeChatId: String? = null,
    val pendingAttachments: List<AttachmentMeta> = emptyList(),
    val run: RunProgress = RunProgress(),
    val capabilities: Map<String, ModelCapability> = emptyMap(),
    val modelTests: Map<String, ModelTestState> = emptyMap(),
    val notice: String? = null
) {
    val activeChat: ChatSession?
        get() = chats.firstOrNull { it.id == activeChatId }
}

const val DEFAULT_PARTICIPANT_PROMPT =
    "Ты участвуешь в беседе. Отвечай по существу, на языке собеседника. " +
    "Анализируй аргументы других участников, исправляй ошибки и не соглашайся только ради согласия."

const val DEFAULT_RESULT_PROMPT =
    "Ты — независимая модель результата. Ты не участник предыдущего спора и не должна защищать позицию какой-либо из моделей. " +
    "Определи, что именно хотел получить пользователь, внимательно оцени состоявшуюся дискуссию и выдай конечный полезный результат. " +
    "Формат результата выбирай по задаче: если нужен промпт — дай готовый промпт; если текст — готовый текст; если стих — стих; " +
    "если решение или объяснение — итоговое решение или объяснение. Не пересказывай спор и не используй фиксированные рубрики, если они не нужны. " +
    "Если существенная неопределённость осталась, кратко укажи её."

const val DEFAULT_MODEL_COLOR: Int = -10847750
const val DEFAULT_MODEL_2_COLOR: Int = -5411882
const val DEFAULT_RESULT_COLOR: Int = -14568085

fun participantDefaultColor(index: Int): Int = when (index % 6) {
    0 -> DEFAULT_MODEL_COLOR
    1 -> DEFAULT_MODEL_2_COLOR
    2 -> -2614432
    3 -> -12213325
    4 -> -10395316
    else -> -5723992
}

fun defaultParticipants(): List<ModelConfig> = listOf(
    ModelConfig(id = "m1", name = "Модель 1", responseColor = participantDefaultColor(0)),
    ModelConfig(id = "m2", name = "Модель 2", responseColor = participantDefaultColor(1))
)

fun defaultResultModel(): ModelConfig = ModelConfig(
    id = "result",
    name = "Модель результата",
    enabled = true,
    systemPrompt = DEFAULT_RESULT_PROMPT,
    responseColor = DEFAULT_RESULT_COLOR
)
