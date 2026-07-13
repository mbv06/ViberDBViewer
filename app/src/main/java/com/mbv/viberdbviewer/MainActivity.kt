package com.mbv.viberdbviewer

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.GlobalSearchResult
import com.mbv.viberdbviewer.model.MessageKind
import com.mbv.viberdbviewer.ui.theme.ViberDBViewerTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ViberDBViewerTheme {
                val viewerViewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(applicationContext))
                val state by viewerViewModel.state.collectAsState()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(viewerViewModel::importDatabase)
                }
                val pickDatabase = remember(picker) { { picker.launch(arrayOf("*/*")) } }

                BackHandler(enabled = state.selectedChat != null) {
                    viewerViewModel.closeChat()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ViewerApp(
                        state = state,
                        onPickDatabase = pickDatabase,
                        onChatQueryChange = viewerViewModel::updateChatQuery,
                        onGlobalSearchVisibilityChange = viewerViewModel::setGlobalSearchVisible,
                        onGlobalSearchQueryChange = viewerViewModel::updateGlobalSearchQuery,
                        onGlobalSearchResultSelected = viewerViewModel::selectGlobalSearchResult,
                        onChatSelected = viewerViewModel::selectChat,
                        onBack = viewerViewModel::closeChat,
                        onSearchVisibilityChange = viewerViewModel::setMessageSearchVisible,
                        onMessageQueryChange = viewerViewModel::updateMessageQuery,
                        onPreviousMatch = viewerViewModel::previousMatch,
                        onNextMatch = viewerViewModel::nextMatch,
                    )
                }

                state.error?.let { message ->
                    AlertDialog(
                        onDismissRequest = viewerViewModel::dismissError,
                        confirmButton = {
                            TextButton(onClick = viewerViewModel::dismissError) { Text(stringResource(R.string.action_ok)) }
                        },
                        title = { Text(stringResource(R.string.database_error_title)) },
                        text = { Text(message) },
                    )
                }
            }
        }
    }
}

@Composable
fun ViewerApp(
    state: ViewerUiState,
    onPickDatabase: () -> Unit,
    onChatQueryChange: (String) -> Unit,
    onGlobalSearchVisibilityChange: (Boolean) -> Unit,
    onGlobalSearchQueryChange: (String) -> Unit,
    onGlobalSearchResultSelected: (GlobalSearchResult) -> Unit,
    onChatSelected: (ChatSummary) -> Unit,
    onBack: () -> Unit,
    onSearchVisibilityChange: (Boolean) -> Unit,
    onMessageQueryChange: (String) -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.isStarting -> LoadingScreen(stringResource(R.string.loading_saved_database))
            !state.databaseReady -> DatabasePickerScreen(onPickDatabase)
            state.selectedChat == null -> ChatListScreen(
                chats = if (state.isGlobalSearchVisible) state.chats else state.filteredChats,
                query = state.chatQuery,
                onQueryChange = onChatQueryChange,
                onChatSelected = onChatSelected,
                onReplaceDatabase = onPickDatabase,
                isGlobalSearchVisible = state.isGlobalSearchVisible,
                globalSearchQuery = state.globalSearchQuery,
                globalSearchResults = state.globalSearchResults,
                isGlobalSearchLoading = state.isGlobalSearchLoading,
                onGlobalSearchVisibilityChange = onGlobalSearchVisibilityChange,
                onGlobalSearchQueryChange = onGlobalSearchQueryChange,
                onGlobalSearchResultSelected = onGlobalSearchResultSelected,
            )
            else -> ConversationScreen(
                chat = state.selectedChat,
                messages = state.messages,
                isLoading = state.isLoadingMessages,
                searchVisible = state.isMessageSearchVisible,
                searchQuery = state.messageQuery,
                matchCount = state.matchIndices.size,
                activeMatchPosition = state.activeMatchPosition,
                activeMessageIndex = state.activeMessageIndex,
                scrollRequest = state.scrollRequest,
                onBack = onBack,
                onSearchVisibilityChange = onSearchVisibilityChange,
                onSearchQueryChange = onMessageQueryChange,
                onPreviousMatch = onPreviousMatch,
                onNextMatch = onNextMatch,
            )
        }

        if (state.isImporting) {
            Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    Card {
                        Row(
                            modifier = Modifier.padding(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(R.string.importing_database))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text)
        }
    }
}

@Composable
private fun DatabasePickerScreen(onPickDatabase: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.database_picker_description),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPickDatabase) { Text(stringResource(R.string.action_select_database)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chats: List<ChatSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    onChatSelected: (ChatSummary) -> Unit,
    onReplaceDatabase: () -> Unit,
    isGlobalSearchVisible: Boolean = false,
    globalSearchQuery: String = "",
    globalSearchResults: List<GlobalSearchResult> = emptyList(),
    isGlobalSearchLoading: Boolean = false,
    onGlobalSearchVisibilityChange: (Boolean) -> Unit = {},
    onGlobalSearchQueryChange: (String) -> Unit = {},
    onGlobalSearchResultSelected: (GlobalSearchResult) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isGlobalSearchVisible) R.string.global_search_title else R.string.chats_title,
                        ),
                    )
                },
                actions = {
                    TextButton(onClick = { onGlobalSearchVisibilityChange(!isGlobalSearchVisible) }) {
                        Text(
                            stringResource(
                                if (isGlobalSearchVisible) R.string.action_close else R.string.action_global_search,
                            ),
                        )
                    }
                    TextButton(onClick = onReplaceDatabase) {
                        Text(stringResource(R.string.action_change_database))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (isGlobalSearchVisible) {
                OutlinedTextField(
                    value = globalSearchQuery,
                    onValueChange = onGlobalSearchQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.global_search_label)) },
                    trailingIcon = {
                        if (globalSearchQuery.isNotEmpty()) {
                            TextButton(onClick = { onGlobalSearchQueryChange("") }) { Text("✕") }
                        }
                    },
                )
                when {
                    isGlobalSearchLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    globalSearchQuery.isBlank() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.global_search_start))
                        }
                    }
                    globalSearchResults.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_search_results))
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(globalSearchResults, key = { it.eventId }) { result ->
                                val chatTitle = chats.firstOrNull { it.chatId == result.chatId }?.title
                                    ?: stringResource(R.string.fallback_chat, result.chatId)
                                GlobalSearchRow(
                                    result = result,
                                    chatTitle = chatTitle,
                                    onClick = { onGlobalSearchResultSelected(result) },
                                )
                                HorizontalDivider()
                            }
                            if (globalSearchResults.size >= 200) {
                                item {
                                    Text(
                                        stringResource(R.string.global_search_limit),
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.chat_search_label)) },
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { onQueryChange("") }) { Text("✕") }
                    },
                )
                if (chats.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(if (query.isBlank()) R.string.no_chats else R.string.no_search_results))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(chats, key = { it.chatId }) { chat ->
                            ChatRow(chat, onClick = { onChatSelected(chat) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchRow(
    result: GlobalSearchResult,
    chatTitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                chatTitle,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(formatChatTime(result.timestamp), style = MaterialTheme.typography.labelMedium)
        }
        if (result.senderName.isNotBlank()) {
            Text(
                result.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            result.displayText,
            style = if (result.kind == MessageKind.DELETED) {
                MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (result.kind == MessageKind.DELETED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Color.Unspecified
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(chat.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chat.subtitle.isNotBlank()) {
                Text("(${chat.subtitle})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(formatChatTime(chat.lastTimestamp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    chat: ChatSummary,
    messages: List<ChatMessage>,
    isLoading: Boolean,
    searchVisible: Boolean,
    searchQuery: String,
    matchCount: Int,
    activeMatchPosition: Int,
    activeMessageIndex: Int?,
    scrollRequest: Long,
    onBack: () -> Unit,
    onSearchVisibilityChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(chat.subtitle, style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    navigationIcon = { TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
                    actions = {
                        TextButton(onClick = { onSearchVisibilityChange(!searchVisible) }) {
                            Text(stringResource(if (searchVisible) R.string.action_close else R.string.action_search))
                        }
                    },
                )
                if (searchVisible) {
                    MessageSearchBar(
                        query = searchQuery,
                        matchCount = matchCount,
                        activePosition = activeMatchPosition,
                        onQueryChange = onSearchQueryChange,
                        onPrevious = onPreviousMatch,
                        onNext = onNextMatch,
                    )
                }
            }
        },
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            MessageList(
                messages = messages,
                isGroup = chat.isGroup,
                query = searchQuery,
                activeMessageIndex = activeMessageIndex,
                scrollRequest = scrollRequest,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun MessageSearchBar(
    query: String,
    matchCount: Int,
    activePosition: Int,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.message_search_hint)) },
        )
        Spacer(Modifier.width(8.dp))
        Text(if (matchCount == 0) "0/0" else "${activePosition + 1}/$matchCount")
        TextButton(onClick = onPrevious, enabled = activePosition > 0) { Text("↑") }
        TextButton(onClick = onNext, enabled = activePosition in 0 until matchCount - 1) { Text("↓") }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isGroup: Boolean,
    query: String,
    activeMessageIndex: Int?,
    scrollRequest: Long,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollRequest, messages.size, activeMessageIndex) {
        val target = activeMessageIndex ?: if (query.isBlank()) messages.lastIndex else null
        if (target != null && target >= 0) listState.scrollToItem(target)
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_messages)) }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(messages, key = { _, message -> message.eventId }) { index, message ->
            val currentDay = messageDay(message.timestamp)
            val previousDay = messages.getOrNull(index - 1)?.let { messageDay(it.timestamp) }
            if (index == 0 || currentDay != previousDay) DateDivider(message.timestamp)
            MessageBubble(
                message = message,
                showSender = isGroup && !message.isOutgoing,
                query = query,
                active = index == activeMessageIndex,
            )
        }
    }
}

@Composable
private fun DateDivider(timestamp: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(formatDay(timestamp), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, showSender: Boolean, query: String, active: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (message.isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
            border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender) {
                    Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                }
                val annotatedText = linkedAndHighlightedText(message.displayText, query)
                if (message.kind == MessageKind.DELETED) {
                    Text(
                        annotatedText,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(annotatedText)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    formatMessageTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun linkedAndHighlightedText(text: String, query: String) = run {
    val linkColor = MaterialTheme.colorScheme.primary
    remember(text, query, linkColor) {
        buildAnnotatedString {
            append(text)

            findWebLinks(text).forEach { link ->
                addLink(
                    LinkAnnotation.Url(
                        url = link.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                    ),
                    start = link.start,
                    end = link.end,
                )
            }

            val needle = query.trim()
            if (needle.isNotEmpty()) {
                var cursor = 0
                while (cursor < text.length) {
                    val match = text.indexOf(needle, cursor, ignoreCase = true)
                    if (match < 0) break
                    addStyle(
                        SpanStyle(background = Color(0xFFFFE082), color = Color.Black),
                        start = match,
                        end = match + needle.length,
                    )
                    cursor = match + needle.length
                }
            }
        }
    }
}

internal data class DetectedWebLink(
    val url: String,
    val start: Int,
    val end: Int,
)

private val webUrlPattern = Regex("https?://[^\\s<>{}\\[\\]\"]+", RegexOption.IGNORE_CASE)
private val trailingUrlPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', '\'', '»')

internal fun findWebLinks(text: String): List<DetectedWebLink> = webUrlPattern.findAll(text).mapNotNull { match ->
    val url = match.value.trimEnd(*trailingUrlPunctuation)
    if (url.length <= match.value.indexOf("://") + 3) {
        null
    } else {
        DetectedWebLink(url = url, start = match.range.first, end = match.range.first + url.length)
    }
}.toList()

private fun zoned(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
private fun messageDay(timestamp: Long): LocalDate = zoned(timestamp).toLocalDate()
@Composable
private fun formatMessageTime(timestamp: Long): String {
    val context = LocalContext.current
    return DateFormat.getTimeFormat(context).format(Date(timestamp))
}

@Composable
private fun formatDay(timestamp: Long): String {
    val context = LocalContext.current
    return DateFormat.getLongDateFormat(context).format(Date(timestamp))
}

@Composable
private fun formatChatTime(timestamp: Long): String {
    val context = LocalContext.current
    val date = Date(timestamp)
    return if (messageDay(timestamp) == LocalDate.now()) {
        DateFormat.getTimeFormat(context).format(date)
    } else {
        DateFormat.getDateFormat(context).format(date)
    }
}
