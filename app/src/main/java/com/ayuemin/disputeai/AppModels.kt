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
    val responseColor: Int = DEFAULT_MODEL_COLOR,
    val webSearchEnabled: Boolean = false,
    val webSearchEngine: String = "auto",
    val webSearchMaxCalls: Int = 2
)

data class GeneralSettings(
    val rounds: Int = 10,
    val firstModelId: String = "m1",
    val resultContextCycles: Int = 3,
    val resultUseAllCycles: Boolean = true,
    val firstCyclePrompt: String = DEFAULT_FIRST_CYCLE_PROMPT,
    val laterCyclesPrompt: String = DEFAULT_LATER_CYCLES_PROMPT
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
    val inProgress: Boolean = false,
    val discussionId: String? = null
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
    val stopRequested: Boolean = false,
    val discussionId: String? = null
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
    "Ты один из независимых участников совместного решения задачи. Отвечай по существу и на языке пользователя. " +
    "Точно соблюдай исходный запрос, ограничения и новые указания пользователя. Не выдумывай факты и не соглашайся с другими участниками только ради консенсуса. " +
    "Твоя роль и стиль сохраняются во всех циклах; правила конкретного этапа дискуссии приложение добавляет отдельно."

const val DEFAULT_FIRST_CYCLE_PROMPT =
    "Это первый, независимый цикл. Сформируй собственное решение задачи пользователя, не ориентируясь на ответы других моделей и не пытаясь заранее прийти к общему мнению. " +
    "Внимательно учитывай исходный запрос, ограничения, вложения и все сообщения пользователя. Дай максимально сильный вариант решения со своей точки зрения. " +
    "Если задача допускает несколько подходов, выбери тот, который считаешь лучшим, и при необходимости кратко обоснуй выбор. Не выдумывай мнения других моделей."

const val DEFAULT_LATER_CYCLES_PROMPT =
    "Это последующий цикл общей дискуссии. Тебе доступны завершённые ответы предыдущих циклов. Критически оцени их вместе со своим предыдущим решением. " +
    "Не соглашайся ради консенсуса: находи ошибки, упущения, слабые места и полезные идеи. Сохраняй собственную позицию, если она сильнее, но используй удачные элементы других решений. " +
    "Цель — не спор ради спора, а приблизиться к наиболее точному, полному и полезному результату для исходной задачи пользователя. Всегда сохраняй фокус на первоначальном запросе и новых указаниях пользователя."

const val DEFAULT_RESULT_PROMPT =
    "Ты — независимая модель результата. Ты не участник предыдущей дискуссии и не должна защищать позицию какой-либо модели. " +
    "Определи, что именно хотел получить пользователь, сопоставь независимые варианты первого цикла с критикой и улучшениями последующих циклов и выдай лучший конечный результат. " +
    "Не голосуй за большинство и не считай согласие моделей доказательством правильности: проверяй аргументы по существу. " +
    "Формат выбирай по исходной задаче: если нужен промпт — дай готовый промпт; документ — готовый документ; текст — готовый текст; код — готовый код; план — готовый план; объяснение или решение — итоговое объяснение или решение. " +
    "Не пересказывай дискуссию и не используй фиксированные рубрики, если они не помогают задаче. Если существенная неопределённость осталась и влияет на результат, кратко укажи её."

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
