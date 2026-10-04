package com.ayuemin.disputeai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: DisputeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DisputeApp(vm)
            }
        }
    }
}

private enum class AppScreen { CHAT, SETTINGS }

@Composable
private fun DisputeApp(vm: DisputeViewModel) {
    val state by vm.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(AppScreen.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snack.showSnackbar(it)
            vm.consumeNotice()
        }
    }

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) keyboard?.hide()
    }

    BackHandler(enabled = screen == AppScreen.SETTINGS) { screen = AppScreen.CHAT }

    if (screen == AppScreen.SETTINGS) {
        SettingsScreen(vm = vm, state = state, onBack = { screen = AppScreen.CHAT })
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            DrawerContent(
                state = state,
                onNewChat = { vm.newChat(); scope.launch { drawerState.close() } },
                onSelectChat = { vm.selectChat(it); scope.launch { drawerState.close() } },
                onPin = vm::pinChat,
                onRename = vm::renameChat,
                onDelete = vm::deleteChat,
                onSettings = {
                    keyboard?.hide()
                    scope.launch { drawerState.close() }
                    screen = AppScreen.SETTINGS
                }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .wideDrawerOpenGesture(drawerState, scope)
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snack) }
            ) { padding ->
                ChatScreen(
                    state = state,
                    vm = vm,
                    onMenu = {
                        keyboard?.hide()
                        scope.launch { drawerState.open() }
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerContent(
    state: UiState,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit
) {
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    ModalDrawerSheet(modifier = Modifier.widthIn(max = 380.dp).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (searchMode) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isBlank()) Text("Поиск по чатам", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        }
                    )
                    IconButton(onClick = { searchMode = false; query = "" }) { Icon(Icons.Default.Close, null) }
                } else {
                    Text("DisputeAI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searchMode = true }) { Icon(Icons.Default.Search, "Поиск") }
                }
            }

            FilledTonalButton(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Новый чат")
            }
            Spacer(Modifier.height(6.dp))
            Divider()

            val filtered = state.chats
                .filter { query.isBlank() || it.title.contains(query, true) || it.messages.any { m -> m.text.contains(query, true) } }
                .sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                items(filtered, key = { it.id }) { chat ->
                    var menu by remember { mutableStateOf(false) }
                    val active = chat.id == state.activeChatId
                    Surface(
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onSelectChat(chat.id) }, onLongClick = { menu = true })
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (chat.pinned) {
                                        Icon(Icons.Default.PushPin, null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text(chat.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                }
                                Text(
                                    formatDrawerTime(chat.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню чата") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (chat.pinned) "Открепить" else "Закрепить") },
                                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                        onClick = { menu = false; onPin(chat.id, !chat.pinned) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Переименовать") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = { menu = false; renameTarget = chat; renameText = chat.title }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Удалить") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = { menu = false; deleteTarget = chat }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Divider()
            ListItem(
                headlineContent = { Text("Настройки") },
                leadingContent = { Icon(Icons.Default.Settings, null) },
                modifier = Modifier.combinedClickable(onClick = onSettings, onLongClick = {})
            )
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    renameTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Переименовать чат") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { onRename(chat.id, renameText); renameTarget = null }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
        )
    }

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить чат?") },
            text = { Text("История и вложения этого чата будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = { onDelete(chat.id); deleteTarget = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ChatScreen(state: UiState, vm: DisputeViewModel, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val chat = state.activeChat
    val listState = rememberLazyListState()
    var draft by rememberSaveable(state.activeChatId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.importAttachments(uris) }
    }
    val lastResultTime = chat?.messages?.filter { it.isResult && !it.inProgress }?.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
    val lastDiscussionTime = chat?.messages?.filter { !it.isResult && !it.inProgress }?.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
    val showResult = chat?.discussionFinished == true && lastDiscussionTime > lastResultTime && chat.messages.none { it.isResult && it.inProgress }

    LaunchedEffect(chat?.messages?.size, chat?.messages?.lastOrNull()?.text?.length, showResult) {
        val count = chat?.messages?.size ?: 0
        if (count > 0) {
            val target = if (showResult) count else count - 1
            listState.animateScrollToItem(target.coerceAtLeast(0))
        }
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            IconButton(onClick = onMenu, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                Icon(Icons.Default.Menu, "Меню")
            }
            if (state.run.mode != RunMode.IDLE) {
                Text(
                    if (state.run.mode == RunMode.PAUSED) "Пауза · цикл ${state.run.cycle}" else "Цикл ${state.run.cycle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        if (chat == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Создайте чат", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chat.messages.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxHeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "Задайте вопрос — модели начнут обсуждение",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 20.dp)
                            )
                        }
                    }
                }

                items(chat.messages, key = { it.id }) { msg ->
                    val model = if (msg.isResult) state.settings.resultModel else state.settings.participants.firstOrNull { it.id == msg.authorId }
                    MessageBubble(msg, chat, model)
                }

                if (showResult) {
                    item {
                        val configured = state.settings.resultModel.baseUrl.isNotBlank() && state.settings.resultModel.model.isNotBlank()
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            FilledTonalButton(
                                onClick = {
                                    if (configured) vm.generateResult()
                                    else Toast.makeText(context, "Настройте модель результата в настройках", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.alpha(if (configured) 1f else 0.55f)
                            ) { Text("Результат") }
                        }
                    }
                }
            }
        }

        Composer(
            draft = draft,
            onDraft = { draft = it },
            pending = state.pendingAttachments,
            runMode = state.run.mode,
            onPick = { picker.launch(arrayOf("*/*")) },
            onRemoveAttachment = vm::removePendingAttachment,
            onSend = { vm.sendAndStart(draft); draft = "" },
            onPause = vm::pauseCycle,
            onContinue = { vm.continueCycle(draft); draft = "" },
            onStop = { vm.stopCycle(true) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, chat: ChatSession, model: ModelConfig?) {
    val isUser = message.authorId == "user"
    val align = if (isUser) Alignment.End else Alignment.Start
    val context = LocalContext.current
    var menu by remember(message.id) { mutableStateOf(false) }
    var selectionDialog by remember(message.id) { mutableStateOf(false) }
    val modelColor = Color(model?.responseColor ?: DEFAULT_MODEL_COLOR)
    val bubbleColor = when {
        message.error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.48f)
        isUser -> MaterialTheme.colorScheme.primaryContainer
        message.isResult -> modelColor.copy(alpha = 0.13f)
        else -> modelColor.copy(alpha = 0.085f)
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = bubbleColor,
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (isUser) copyToClipboard(context, message.text)
                            else menu = true
                        }
                    )
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (!isUser) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (message.isResult) "Результат дискуссии" else message.authorName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = modelColor.copy(alpha = 0.95f)
                            )
                            if (message.inProgress) {
                                Spacer(Modifier.width(7.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = modelColor
                                )
                            }
                        }
                        if (message.text.isNotBlank()) Spacer(Modifier.height(4.dp))
                    }

                    if (message.text.isNotBlank()) {
                        if (isUser) {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        } else {
                            MarkdownText(message.text, modifier = Modifier.fillMaxWidth())
                        }
                    } else if (message.inProgress) {
                        Text(
                            "Формирует ответ…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }

                    message.attachmentIds.mapNotNull { id -> chat.attachments.firstOrNull { it.id == id } }.forEach { a ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(a.name, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            if (!isUser) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Копировать ответ") },
                        onClick = {
                            menu = false
                            copyToClipboard(context, message.text)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Выделить текст") },
                        onClick = {
                            menu = false
                            selectionDialog = true
                        }
                    )
                }
            }
        }
    }

    if (selectionDialog) {
        AlertDialog(
            onDismissRequest = { selectionDialog = false },
            title = { Text(if (message.isResult) "Результат дискуссии" else message.authorName) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    MarkdownText(message.text, modifier = Modifier.fillMaxWidth(), selectable = true)
                }
            },
            confirmButton = { TextButton(onClick = { selectionDialog = false }) { Text("Готово") } },
            dismissButton = {
                TextButton(onClick = {
                    copyToClipboard(context, message.text)
                    selectionDialog = false
                }) { Text("Копировать всё") }
            }
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    pending: List<AttachmentMeta>,
    runMode: RunMode,
    onPick: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onPause: () -> Unit,
    onContinue: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (pending.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pending.take(3).forEach { a ->
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(a.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.widthIn(max = 140.dp))
                                IconButton(onClick = { onRemoveAttachment(a.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    if (pending.size > 3) Text("+${pending.size - 3}", style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPick) { Icon(Icons.Default.Add, "Добавить вложение") }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (draft.isBlank()) {
                                Text(
                                    if (runMode == RunMode.PAUSED) "Реплика перед продолжением…" else "Сообщение…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )
                if (runMode != RunMode.IDLE) {
                    IconButton(onClick = if (runMode == RunMode.PAUSED) onContinue else onPause) {
                        Icon(
                            if (runMode == RunMode.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (runMode == RunMode.PAUSED) "Продолжить" else "Пауза"
                        )
                    }
                }
                IconButton(
                    onClick = if (runMode == RunMode.IDLE) onSend else onStop,
                    enabled = runMode != RunMode.IDLE || draft.isNotBlank() || pending.isNotEmpty()
                ) {
                    Icon(
                        if (runMode == RunMode.IDLE) Icons.Default.Send else Icons.Default.Stop,
                        if (runMode == RunMode.IDLE) "Отправить" else "Стоп"
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: DisputeViewModel, state: UiState, onBack: () -> Unit) {
    var confirmReset by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .rightEdgeBackGesture(onBack)
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
            Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Divider()
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionTitle("Участники дискуссии") }
            items(state.settings.participants, key = { it.id }) { model ->
                ModelSettingsCard(
                    config = model,
                    capability = state.capabilities[model.id],
                    testState = state.modelTests[model.id],
                    canDelete = state.settings.participants.size > 2,
                    onChange = vm::updateParticipant,
                    onSaveKey = { vm.saveApiKey(model.id, it) },
                    onClearKey = { vm.clearApiKey(model.id) },
                    onDelete = { vm.removeParticipant(model.id) },
                    onTest = { vm.testModel(model.id) },
                    onProbe = { vm.probeCapabilities(model.id) }
                )
            }
            item {
                OutlinedButton(onClick = vm::addParticipant, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Добавить модель")
                }
            }
            item { Spacer(Modifier.height(4.dp)); SectionTitle("Модель результата") }
            item {
                ModelSettingsCard(
                    config = state.settings.resultModel,
                    capability = state.capabilities[state.settings.resultModel.id],
                    testState = state.modelTests[state.settings.resultModel.id],
                    canDelete = false,
                    onChange = vm::updateResultModel,
                    onSaveKey = { vm.saveApiKey(state.settings.resultModel.id, it) },
                    onClearKey = { vm.clearApiKey(state.settings.resultModel.id) },
                    onDelete = {},
                    onTest = { vm.testModel(state.settings.resultModel.id) },
                    onProbe = { vm.probeCapabilities(state.settings.resultModel.id) }
                )
            }
            item { Spacer(Modifier.height(4.dp)); SectionTitle("Общие") }
            item {
                GeneralSettingsCard(
                    settings = state.settings,
                    onChange = vm::updateGeneral,
                    onReset = { confirmReset = true }
                )
            }
            item {
                Text(
                    "API-ключи хранятся локально в зашифрованном виде с ключом из Android Keystore и не попадают в историю чатов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки?") },
            text = { Text("Будут сброшены настройки всех моделей и общие параметры, а сохранённые API-ключи удалены. История чатов и вложения останутся.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetSettings() }) { Text("Сбросить") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun ModelSettingsCard(
    config: ModelConfig,
    capability: ModelCapability?,
    testState: ModelTestState?,
    canDelete: Boolean,
    onChange: (ModelConfig) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onProbe: () -> Unit
) {
    var expanded by rememberSaveable(config.id) { mutableStateOf(false) }
    var apiKey by remember(config.id) { mutableStateOf("") }
    var providerMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var colorPicker by remember { mutableStateOf(false) }

    LaunchedEffect(config.baseUrl, config.model, config.provider) {
        if (config.model.isNotBlank()) {
            delay(650)
            onProbe()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(config.name.ifBlank { "Модель" }, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (config.baseUrl.isNotBlank() && config.model.isNotBlank()) "Настроена${if (config.hasApiKey) " · ключ сохранён" else ""}" else "Не настроена",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }

            if (expanded) {
                Divider()
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = config.name,
                            onValueChange = { onChange(config.copy(name = it.take(60))) },
                            label = { Text("Название") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
                    }

                    Box {
                        OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                when (config.provider) {
                                    "anthropic" -> "Anthropic"
                                    "gemini" -> "Gemini"
                                    else -> "OpenAI-совместимый API"
                                }
                            )
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("OpenAI-совместимый API") },
                                onClick = { providerMenu = false; onChange(config.copy(provider = "openai")) }
                            )
                            DropdownMenuItem(
                                text = { Text("Anthropic") },
                                onClick = { providerMenu = false; onChange(config.copy(provider = "anthropic")) }
                            )
                            DropdownMenuItem(
                                text = { Text("Gemini") },
                                onClick = { providerMenu = false; onChange(config.copy(provider = "gemini")) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = config.baseUrl,
                        onValueChange = { onChange(config.copy(baseUrl = it)) },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://openrouter.ai/api/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = config.model,
                        onValueChange = { onChange(config.copy(model = it)) },
                        label = { Text("ID модели") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (config.hasApiKey) "Новый API-ключ (необязательно)" else "API-ключ") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (apiKey.isNotBlank()) { onSaveKey(apiKey); apiKey = "" } },
                            enabled = apiKey.isNotBlank()
                        ) { Text("Сохранить ключ") }
                        if (config.hasApiKey) TextButton(onClick = onClearKey) { Text("Удалить ключ") }
                    }

                    OutlinedTextField(
                        value = config.systemPrompt,
                        onValueChange = { onChange(config.copy(systemPrompt = it)) },
                        label = { Text("Промпт модели") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Цвет ответа")
                            Text("Едва заметный фон сообщений этой модели", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            modifier = Modifier
                                .size(38.dp)
                                .combinedClickable(onClick = { colorPicker = true }, onLongClick = { colorPicker = true }),
                            shape = CircleShape,
                            color = Color(config.responseColor),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
                        ) {}
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { colorPicker = true }) { Text("Палитра") }
                    }

                    if (capability?.temperatureSupported == false) {
                        Text("Температура: не поддерживается этой моделью", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Температура")
                                Text(
                                    if (config.temperatureEnabled) String.format(Locale.US, "%.2f", config.temperature) else "По умолчанию модели",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = config.temperatureEnabled, onCheckedChange = { onChange(config.copy(temperatureEnabled = it)) })
                        }
                        if (config.temperatureEnabled) {
                            Slider(
                                value = config.temperature.toFloat(),
                                onValueChange = { onChange(config.copy(temperature = it.toDouble())) },
                                valueRange = 0f..2f
                            )
                        }
                    }

                    OutlinedTextField(
                        value = config.timeoutSec.toString(),
                        onValueChange = { text -> text.toIntOrNull()?.let { onChange(config.copy(timeoutSec = it.coerceIn(10, 600))) } },
                        label = { Text("Таймаут ответа, секунд") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (capability?.reasoningSupported == true) {
                        Divider()
                        if (capability.reasoningAlwaysOn) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Размышление: всегда включено этой моделью")
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Размышление", modifier = Modifier.weight(1f))
                                Switch(checked = config.reasoningEnabled, onCheckedChange = { onChange(config.copy(reasoningEnabled = it)) })
                            }
                        }
                        if (config.reasoningEnabled || capability.reasoningAlwaysOn) {
                            val efforts = capability.supportedEfforts.distinct().filter { it.isNotBlank() }
                            if (efforts.isNotEmpty()) {
                                Box {
                                    OutlinedButton(onClick = { effortMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Уровень: ${if (config.reasoningEffort == "auto") "Авто" else config.reasoningEffort}")
                                    }
                                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Авто") },
                                            onClick = { effortMenu = false; onChange(config.copy(reasoningEffort = "auto")) }
                                        )
                                        efforts.forEach { effort ->
                                            DropdownMenuItem(
                                                text = { Text(effort) },
                                                onClick = { effortMenu = false; onChange(config.copy(reasoningEffort = effort)) }
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = if (config.reasoningBudget == 0) "" else config.reasoningBudget.toString(),
                                onValueChange = { text -> onChange(config.copy(reasoningBudget = text.toIntOrNull()?.coerceAtLeast(0) ?: 0)) },
                                label = { Text("Бюджет reasoning-токенов (необязательно)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f), enabled = testState?.checking != true) {
                            if (testState?.checking == true) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (testState?.checking == true) "Проверяем…" else "Проверить")
                        }
                        if (canDelete) OutlinedButton(onClick = onDelete) { Icon(Icons.Default.Delete, null) }
                    }

                    testState?.let { test ->
                        if (!test.checking && test.success != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (test.success == true) "✓" else "!",
                                    fontWeight = FontWeight.Bold,
                                    color = if (test.success == true) Color(0xFF68D391) else MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    test.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (test.success == true) Color(0xFF8FE3A8) else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (colorPicker) {
        ModelColorPickerDialog(
            initialColor = config.responseColor,
            onDismiss = { colorPicker = false },
            onConfirm = {
                colorPicker = false
                onChange(config.copy(responseColor = it))
            }
        )
    }
}

@Composable
private fun ModelColorPickerDialog(initialColor: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var value by remember(initialColor) { mutableStateOf(initialHsv[2].coerceAtLeast(0.2f)) }
    val preview = AndroidColor.HSVToColor(255, floatArrayOf(hue, saturation, value))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Цвет ответа модели") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(preview)
                ) {}
                Text("Оттенок", style = MaterialTheme.typography.labelMedium)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("Насыщенность", style = MaterialTheme.typography.labelMedium)
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
                Text("Яркость", style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { value = it }, valueRange = 0.2f..1f)
                Text(
                    "В чате этот цвет используется очень прозрачно — только как лёгкий оттенок фона.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(preview) }) { Text("Применить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun GeneralSettingsCard(settings: AppSettings, onChange: (GeneralSettings) -> Unit, onReset: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var firstMenu by remember { mutableStateOf(false) }
    val g = settings.general
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Общие настройки", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }
            if (expanded) {
                Divider()
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberSetting("Количество циклов", g.rounds, 1, 100) { onChange(g.copy(rounds = it)) }

                    Column {
                        Text("Кто отвечает первым", style = MaterialTheme.typography.labelLarge)
                        Box {
                            OutlinedButton(onClick = { firstMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                val name = settings.participants.firstOrNull { it.id == g.firstModelId }?.name ?: settings.participants.first().name
                                Text(name)
                            }
                            DropdownMenu(expanded = firstMenu, onDismissRequest = { firstMenu = false }) {
                                settings.participants.filter { it.enabled }.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.name) },
                                        onClick = { firstMenu = false; onChange(g.copy(firstModelId = m.id)) }
                                    )
                                }
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Контекст для результата")
                            Text(
                                if (g.resultUseAllCycles) "Вся дискуссия" else "Последние ${g.resultContextCycles} циклов + все реплики пользователя",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = g.resultUseAllCycles, onCheckedChange = { onChange(g.copy(resultUseAllCycles = it)) })
                    }
                    if (!g.resultUseAllCycles) {
                        NumberSetting("Последних циклов для результата", g.resultContextCycles, 1, 100) {
                            onChange(g.copy(resultContextCycles = it))
                        }
                    }

                    Divider()
                    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Сбросить настройки")
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { if (value > min) onChange(value - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value.toString(), modifier = Modifier.width(36.dp), style = MaterialTheme.typography.bodyLarge)
        IconButton(onClick = { if (value < max) onChange(value + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DisputeAI", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun formatDrawerTime(ts: Long): String {
    val now = System.currentTimeMillis()
    return if (now - ts < 24 * 60 * 60 * 1000L) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(ts))
    }
}
