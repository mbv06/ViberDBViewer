package com.mbv.viberdbviewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import java.util.Date

@Composable
internal fun ConversationScreen(
    state: ConversationUiState,
    actions: ViewerActions,
) {
    val dateFormats = rememberViewerDateFormats()

    Scaffold(
        topBar = {
            ConversationTopBar(
                chat = state.chat,
                searchVisible = state.searchVisible,
                searchQuery = state.searchQuery,
                matchCount = state.matchCount,
                activeMatchPosition = state.activeMatchPosition,
                onBack = actions.closeChat,
                onSearchVisibilityChange = actions.setMessageSearchVisible,
                onSearchQueryChange = actions.updateMessageQuery,
                onPreviousMatch = actions.previousMatch,
                onNextMatch = actions.nextMatch,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            CenteredProgress(Modifier.padding(padding))
        } else {
            MessageList(
                messages = state.messages,
                daySeparatorIndices = state.daySeparatorIndices,
                isGroup = state.chat.isGroup,
                query = state.searchQuery,
                activeMessageIndex = state.activeMessageIndex,
                scrollRequest = state.scrollRequest,
                dateFormats = dateFormats,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationTopBar(
    chat: ChatSummary,
    searchVisible: Boolean,
    searchQuery: String,
    matchCount: Int,
    activeMatchPosition: Int,
    onBack: () -> Unit,
    onSearchVisibilityChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chat.subtitle, style = MaterialTheme.typography.labelMedium)
                }
            },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("←", style = MaterialTheme.typography.headlineMedium)
                }
            },
            actions = {
                TextButton(onClick = { onSearchVisibilityChange(!searchVisible) }) {
                    Text(
                        stringResource(
                            if (searchVisible) {
                                R.string.action_close
                            } else {
                                R.string.action_search
                            },
                        ),
                    )
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
        val matchPosition =
            if (matchCount == 0) {
                "0/0"
            } else {
                (activePosition + 1).toString() + "/" + matchCount
            }
        Text(matchPosition)
        TextButton(onClick = onPrevious, enabled = activePosition > 0) {
            Text("↑", style = MaterialTheme.typography.headlineMedium)
        }
        TextButton(onClick = onNext, enabled = activePosition in 0 until matchCount - 1) {
            Text("↓", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    daySeparatorIndices: Set<Int>,
    isGroup: Boolean,
    query: String,
    activeMessageIndex: Int?,
    scrollRequest: Long,
    dateFormats: ViewerDateFormats,
    modifier: Modifier = Modifier,
) {
    val initialTarget =
        activeMessageIndex
            ?: messages.lastIndex.takeIf { query.isBlank() }
            ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialTarget.coerceAtLeast(0))
    LaunchedEffect(scrollRequest, messages.size, activeMessageIndex) {
        val target = activeMessageIndex ?: if (query.isBlank()) messages.lastIndex else null
        if (target != null && target >= 0) listState.scrollToItem(target)
    }

    if (messages.isEmpty()) {
        CenteredMessage(stringResource(R.string.no_messages), modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(messages, key = { _, message -> message.eventId }) { index, message ->
            if (index in daySeparatorIndices) DateDivider(message.timestamp, dateFormats)
            MessageBubble(
                message = message,
                showSender = isGroup && !message.isOutgoing,
                query = query,
                active = index == activeMessageIndex,
                dateFormats = dateFormats,
            )
        }
    }
}

@Composable
private fun DateDivider(
    timestamp: Long,
    dateFormats: ViewerDateFormats,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                dateFormats.longDate.format(Date(timestamp)),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    showSender: Boolean,
    query: String,
    active: Boolean,
    dateFormats: ViewerDateFormats,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Surface(
            modifier =
                Modifier
                    .align(if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = maxWidth * 0.75f)
                    .copyOnLongClick(text = { message.displayText }),
            shape = RoundedCornerShape(14.dp),
            color =
                if (message.isOutgoing) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            border =
                if (active) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
        ) {
            MessageBubbleContent(
                message = message,
                showSender = showSender,
                query = query,
                dateFormats = dateFormats,
            )
        }
    }
}

@Composable
private fun MessageBubbleContent(
    message: ChatMessage,
    showSender: Boolean,
    query: String,
    dateFormats: ViewerDateFormats,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (showSender) {
            Text(
                message.senderName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
        }
        DeletedAwareText(
            text = linkedAndHighlightedText(message.displayText, query),
            kind = message.kind,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            dateFormats.time.format(Date(message.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun linkedAndHighlightedText(
    text: String,
    query: String,
) = run {
    val linkColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val onHighlightColor = MaterialTheme.colorScheme.onTertiaryContainer
    val links = remember(text) { findWebLinks(text) }
    remember(text, query, linkColor, highlightColor, onHighlightColor, links) {
        buildAnnotatedString {
            append(text)

            links.forEach { link ->
                addLink(
                    LinkAnnotation.Url(
                        url = link.url,
                        styles =
                            TextLinkStyles(
                                style =
                                    SpanStyle(
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
                        SpanStyle(background = highlightColor, color = onHighlightColor),
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

private const val URL_SCHEME_SEPARATOR = "://"
private val webUrlPattern = Regex("""https?://[^\s<>{}\[\]"]+""", RegexOption.IGNORE_CASE)
private val trailingUrlPunctuation = charArrayOf('.', ',', ';', ':', '!', '?', ')', '\'', '»')

internal fun findWebLinks(text: String): List<DetectedWebLink> =
    webUrlPattern
        .findAll(text)
        .mapNotNull { match ->
            val url = match.value.trimEnd(*trailingUrlPunctuation)
            if (url.length <= match.value.indexOf(URL_SCHEME_SEPARATOR) + URL_SCHEME_SEPARATOR.length) {
                null
            } else {
                DetectedWebLink(
                    url = url,
                    start = match.range.first,
                    end = match.range.first + url.length,
                )
            }
        }.toList()
