package sh.haven.feature.editor

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

private const val MAX_VIEW_SIZE = 50L * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    wordWrap: Boolean,
    onToggleWordWrap: () -> Unit,
    onBack: () -> Unit,
) {
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorColumn by remember { mutableIntStateOf(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (state) {
                        is EditorState.Loaded -> state.fileName
                        is EditorState.Loading -> stringResource(R.string.editor_loading)
                        else -> stringResource(R.string.editor_title)
                    }
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onToggleWordWrap) {
                        Icon(
                            Icons.AutoMirrored.Filled.WrapText,
                            contentDescription = stringResource(R.string.editor_word_wrap),
                            tint = if (wordWrap) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state is EditorState.Loaded) {
                Text(
                    text = stringResource(R.string.editor_line, cursorLine, cursorColumn),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 12.dp,
                        vertical = 4.dp,
                    ),
                )
            }
        },
    ) { innerPadding ->
        AnimatedContent(
            targetState = state,
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            label = "editor-state",
            contentKey = { it::class },
        ) { current ->
            when (current) {
                is EditorState.Idle -> Box(Modifier.fillMaxSize())
                is EditorState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is EditorState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.editor_failed, current.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is EditorState.Loaded -> {
                    EditorContent(
                        content = current.content,
                        wordWrap = wordWrap,
                        onCursorChange = { line, col ->
                            cursorLine = line
                            cursorColumn = col
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorContent(
    content: String,
    wordWrap: Boolean,
    onCursorChange: (line: Int, column: Int) -> Unit,
) {
    val bgColor = MaterialTheme.colorScheme.surface.toArgb()
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val lineNumColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val selectionColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()
    val lineHighlightColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()

    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }

    LaunchedEffect(wordWrap) {
        editorRef?.setWordwrap(wordWrap)
    }

    DisposableEffect(Unit) {
        onDispose { editorRef?.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                val scheme = colorScheme
                scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bgColor)
                scheme.setColor(EditorColorScheme.TEXT_NORMAL, textColor)
                scheme.setColor(EditorColorScheme.LINE_NUMBER, lineNumColor)
                scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bgColor)
                scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selectionColor)
                scheme.setColor(EditorColorScheme.SELECTION_INSERT, cursorColor)
                scheme.setColor(EditorColorScheme.SELECTION_HANDLE, cursorColor)
                scheme.setColor(EditorColorScheme.CURRENT_LINE, lineHighlightColor)
                scheme.setColor(EditorColorScheme.LINE_DIVIDER, lineNumColor)

                setTextSize(14f)
                isEditable = false
                setWordwrap(wordWrap)
                isLineNumberEnabled = true
                setText(content)

                subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    val cursor = event.editor.cursor
                    onCursorChange(cursor.leftLine + 1, cursor.leftColumn + 1)
                }

                editorRef = this
            }
        },
        update = { editor ->
            val currentText = editor.text.toString()
            if (currentText != content) {
                editor.setText(content)
            }
        },
    )
}
