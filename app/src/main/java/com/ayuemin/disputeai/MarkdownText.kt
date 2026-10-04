package com.ayuemin.disputeai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    selectable: Boolean = false
) {
    val context = LocalContext.current
    var actionDialog by remember(markdown, selectable) { mutableStateOf(false) }
    var selectionDialog by remember(markdown, selectable) { mutableStateOf(false) }
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .build()
    }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(selectable)
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                textSize = 16.sp.value
                setLineSpacing(0f, 1.08f)
                includeFontPadding = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.setTextIsSelectable(selectable)
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            if (selectable) {
                view.setOnLongClickListener(null)
            } else {
                view.setOnLongClickListener {
                    actionDialog = true
                    true
                }
            }
            markwon.setMarkdown(view, markdown)
        }
    )

    if (!selectable && actionDialog) {
        AlertDialog(
            onDismissRequest = { actionDialog = false },
            title = { Text("Действия с ответом") },
            text = { Text("Можно скопировать ответ целиком или открыть его для выделения нужного фрагмента.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        actionDialog = false
                        copyMarkdown(context, markdown)
                    }
                ) { Text("Копировать ответ") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        actionDialog = false
                        selectionDialog = true
                    }
                ) { Text("Выделить текст") }
            }
        )
    }

    if (!selectable && selectionDialog) {
        AlertDialog(
            onDismissRequest = { selectionDialog = false },
            title = { Text("Выделить текст") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    MarkdownText(markdown, modifier = Modifier.fillMaxWidth(), selectable = true)
                }
            },
            confirmButton = { TextButton(onClick = { selectionDialog = false }) { Text("Готово") } },
            dismissButton = {
                TextButton(
                    onClick = {
                        copyMarkdown(context, markdown)
                        selectionDialog = false
                    }
                ) { Text("Копировать всё") }
            }
        )
    }
}

private fun copyMarkdown(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("DisputeAI", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}
