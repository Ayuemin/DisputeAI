package com.ayuemin.disputeai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** High-contrast dark palette tuned for readability on phone displays. */
val DisputeDarkColors = darkColorScheme(
    primary = Color(0xFFC9BAFF),
    onPrimary = Color(0xFF24183C),
    primaryContainer = Color(0xFF3A2F55),
    onPrimaryContainer = Color(0xFFF3EEFF),
    secondary = Color(0xFFDCC9F0),
    onSecondary = Color(0xFF271D31),
    secondaryContainer = Color(0xFF332B3E),
    onSecondaryContainer = Color(0xFFF4EEFA),
    tertiary = Color(0xFFB8D8FF),
    onTertiary = Color(0xFF12253A),
    background = Color(0xFF0E0D12),
    onBackground = Color(0xFFF8F5FA),
    surface = Color(0xFF15131A),
    onSurface = Color(0xFFF8F5FA),
    surfaceVariant = Color(0xFF25212C),
    onSurfaceVariant = Color(0xFFD9D3DF),
    outline = Color(0xFFA59DAC),
    outlineVariant = Color(0xFF514A59),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * Shared accordion state for the settings screen.
 * Root sections, discussion subsections and model cards each have their own level,
 * so opening a child never collapses its parent.
 */
private object SettingsAccordionState {
    var rootOpen by mutableStateOf<String?>(null)
    var discussionOpen by mutableStateOf<String?>(null)
    var modelOpen by mutableStateOf<String?>(null)

    private fun isDiscussionChild(key: String): Boolean =
        key.startsWith("discussion-") && key != "discussion-parameters"

    fun isExpanded(key: String): Boolean =
        if (isDiscussionChild(key)) discussionOpen == key else rootOpen == key

    fun toggle(key: String) {
        if (isDiscussionChild(key)) {
            discussionOpen = if (discussionOpen == key) null else key
            return
        }

        val next = if (rootOpen == key) null else key
        if (rootOpen != next) {
            discussionOpen = null
            modelOpen = null
        }
        rootOpen = next
    }

    fun isModelExpanded(id: String): Boolean = modelOpen == id

    fun toggleModel(id: String) {
        modelOpen = if (modelOpen == id) null else id
    }

    fun reset() {
        rootOpen = null
        discussionOpen = null
        modelOpen = null
    }
}

fun resetSettingsAccordion() = SettingsAccordionState.reset()
fun isSettingsModelExpanded(id: String): Boolean = SettingsAccordionState.isModelExpanded(id)
fun toggleSettingsModel(id: String) = SettingsAccordionState.toggleModel(id)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PendingAttachmentsSummary(
    pending: List<AttachmentMeta>,
    onRemoveAttachment: (String) -> Unit
) {
    if (pending.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { expanded = !expanded }
            )
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    "Вложения ${pending.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    if (expanded) "Свернуть вложения" else "Показать вложения",
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
            }

            if (expanded) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 230.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    pending.forEach { attachment ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                attachment.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveAttachment(attachment.id) },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Default.Close, "Удалить ${attachment.name}", Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModernGeneralSettings(
    settings: AppSettings,
    onChange: (GeneralSettings) -> Unit
) {
    val g = settings.general
    val firstName = settings.participants.firstOrNull { it.id == g.firstModelId }?.name
        ?: settings.participants.firstOrNull()?.name.orEmpty()

    SettingsExpandableCard(
        title = "Параметры дискуссии",
        subtitle = "Циклы, промпты и контекст",
        initiallyExpanded = false,
        stateKey = "discussion-parameters"
    ) {
        SettingsExpandableCard(
            title = "Циклы и порядок участников",
            subtitle = "До ${g.rounds} циклов · первым: $firstName",
            initiallyExpanded = false,
            stateKey = "discussion-cycles"
        ) {
            DiscussionCycleSettings(settings = settings, onChange = onChange)
        }

        SettingsExpandableCard(
            title = "Промпты циклов",
            subtitle = "Правила первого и последующих циклов",
            initiallyExpanded = false,
            stateKey = "discussion-prompts"
        ) {
            Text(
                "В первом цикле модели отвечают независимо. Со второго они видят завершённые ответы и переходят к критике и улучшению.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ModernPromptSetting(
                label = "Промпт первого цикла",
                value = g.firstCyclePrompt,
                onValueChange = { onChange(g.copy(firstCyclePrompt = it)) },
                onReset = { onChange(g.copy(firstCyclePrompt = DEFAULT_FIRST_CYCLE_PROMPT)) }
            )
            ModernPromptSetting(
                label = "Промпт последующих циклов",
                value = g.laterCyclesPrompt,
                onValueChange = { onChange(g.copy(laterCyclesPrompt = it)) },
                onReset = { onChange(g.copy(laterCyclesPrompt = DEFAULT_LATER_CYCLES_PROMPT)) }
            )
        }

        SettingsExpandableCard(
            title = "Контекст участников",
            subtitle = if (g.discussionUseAllCycles) "Все циклы текущей дискуссии" else "Первый цикл + последние ${g.discussionContextCycles}",
            initiallyExpanded = false,
            stateKey = "discussion-participant-context"
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Использовать все циклы")
                    Text(
                        "Относится только к моделям-участникам, не к модели результата.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = g.discussionUseAllCycles,
                    onCheckedChange = { onChange(g.copy(discussionUseAllCycles = it)) }
                )
            }

            if (g.discussionUseAllCycles) {
                Text(
                    "Участники видят все завершённые циклы только текущей дискуссии и все реплики пользователя в ней.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ModernNumberSetting("Последних циклов для участников", g.discussionContextCycles, 1, 100) {
                    onChange(g.copy(discussionContextCycles = it))
                }
                Text(
                    "Первый независимый цикл сохраняется всегда. К нему добавляются последние выбранные циклы и все реплики пользователя текущей дискуссии. Другие дискуссии этого чата в контекст не попадают.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsExpandableCard(
            title = "Контекст результата",
            subtitle = if (g.resultUseAllCycles) "Весь контекст дискуссии" else "Первый цикл + последние ${g.resultContextCycles}",
            initiallyExpanded = false,
            stateKey = "discussion-result-context"
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Использовать весь контекст дискуссии")
                    Text(
                        "Реплики пользователя всегда включаются в контекст результата.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = g.resultUseAllCycles,
                    onCheckedChange = { onChange(g.copy(resultUseAllCycles = it)) }
                )
            }

            if (g.resultUseAllCycles) {
                Text(
                    "Модель результата получит весь доступный контекст дискуссии.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ModernNumberSetting("Последних циклов для результата", g.resultContextCycles, 1, 100) {
                    onChange(g.copy(resultContextCycles = it))
                }
                Text(
                    "Независимый первый цикл сохраняется отдельно, к нему добавляются последние выбранные циклы и реплики пользователя.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DiscussionCycleSettings(
    settings: AppSettings,
    onChange: (GeneralSettings) -> Unit
) {
    val g = settings.general
    var firstMenu by remember { mutableStateOf(false) }

    ModernNumberSetting("Максимальное количество циклов", g.rounds, 1, 100) {
        onChange(g.copy(rounds = it))
    }
    Text(
        "Это верхний предел: дискуссия может завершиться раньше, если модели закончили спор.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Column {
        Text("Кто отвечает первым", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { firstMenu = true }, modifier = Modifier.fillMaxWidth()) {
                val selected = settings.participants.firstOrNull { it.id == g.firstModelId }
                    ?: settings.participants.first()
                Text(selected.name)
            }
            DropdownMenu(expanded = firstMenu, onDismissRequest = { firstMenu = false }) {
                settings.participants.filter { it.enabled }.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                            firstMenu = false
                            onChange(g.copy(firstModelId = model.id))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AboutAppSettings(onReset: () -> Unit) {
    val context = LocalContext.current
    val repoUrl = "https://github.com/Ayuemin/DisputeAI"

    SettingsExpandableCard(
        title = "О приложении",
        subtitle = "DisputeAI ${BuildConfig.VERSION_NAME}",
        initiallyExpanded = false,
        stateKey = "about-app"
    ) {
        Text(
            "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Репозиторий на GitHub")
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Защита API-ключей", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "API-ключи хранятся только на устройстве в зашифрованном виде. Ключ шифрования защищён Android Keystore; API-ключи не записываются в историю чатов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        BackgroundWorkSettings(showHeading = true)

        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Сброс настроек", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "История чатов и вложения останутся, но настройки моделей, общие параметры и сохранённые API-ключи будут сброшены.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text("Сбросить настройки")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun SettingsExpandableCard(
    title: String,
    subtitle: String,
    initiallyExpanded: Boolean,
    stateKey: String = title,
    content: @Composable () -> Unit
) {
    val expanded = SettingsAccordionState.isExpanded(stateKey)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { SettingsAccordionState.toggle(stateKey) },
                        onLongClick = { SettingsAccordionState.toggle(stateKey) }
                    )
                    .padding(start = 16.dp, end = 12.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun ModernPromptSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.End)) {
            Text("Вернуть по умолчанию")
        }
    }
}

@Composable
private fun ModernNumberSetting(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
            Text(
                value.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.width(144.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(
                onClick = { if (value > min) onChange(value - 1) },
                modifier = Modifier.size(48.dp)
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                value.toString(),
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = { if (value < max) onChange(value + 1) },
                modifier = Modifier.size(48.dp)
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
