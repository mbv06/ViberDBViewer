package com.mbv.viberdbviewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.GlobalSearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    state: ChatListUiState,
    actions: ViewerActions,
    chatListState: LazyListState = rememberLazyListState(),
    globalSearchListState: LazyListState = rememberLazyListState(),
) {
    val dateFormats = rememberViewerDateFormats()

    Scaffold(
        topBar = {
            ChatListTopBar(
                isGlobalSearchVisible = state.isGlobalSearchVisible,
                onGlobalSearchVisibilityChange = actions.setGlobalSearchVisible,
                onReplaceDatabase = actions.pickDatabase,
            )
        },
    ) { padding ->
        if (state.isGlobalSearchVisible) {
            GlobalSearchContent(
                chats = state.chats,
                query = state.globalSearchQuery,
                results = state.globalSearchResults,
                isLoading = state.isGlobalSearchLoading,
                onQueryChange = actions.updateGlobalSearchQuery,
                onResultSelected = actions.selectGlobalSearchResult,
                listState = globalSearchListState,
                dateFormats = dateFormats,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            ChatSearchContent(
                chats = state.chats,
                query = state.query,
                onQueryChange = actions.updateChatQuery,
                onChatSelected = actions.selectChat,
                listState = chatListState,
                dateFormats = dateFormats,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListTopBar(
    isGlobalSearchVisible: Boolean,
    onGlobalSearchVisibilityChange: (Boolean) -> Unit,
    onReplaceDatabase: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                stringResource(
                    if (isGlobalSearchVisible) {
                        R.string.global_search_title
                    } else {
                        R.string.chats_title
                    },
                ),
            )
        },
        actions = {
            TextButton(onClick = { onGlobalSearchVisibilityChange(!isGlobalSearchVisible) }) {
                Text(
                    stringResource(
                        if (isGlobalSearchVisible) {
                            R.string.action_close
                        } else {
                            R.string.action_global_search
                        },
                    ),
                )
            }
            TextButton(onClick = onReplaceDatabase) {
                Text(stringResource(R.string.action_change_database))
            }
        },
    )
}

@Composable
private fun GlobalSearchContent(
    chats: List<ChatSummary>,
    query: String,
    results: List<GlobalSearchResult>,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onResultSelected: (GlobalSearchResult) -> Unit,
    listState: LazyListState,
    dateFormats: ViewerDateFormats,
    modifier: Modifier,
) {
    Column(modifier) {
        SearchTextField(
            value = query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.global_search_label),
        )
        GlobalSearchResults(
            chats = chats,
            query = query,
            results = results,
            isLoading = isLoading,
            onResultSelected = onResultSelected,
            listState = listState,
            dateFormats = dateFormats,
        )
    }
}

@Composable
private fun GlobalSearchResults(
    chats: List<ChatSummary>,
    query: String,
    results: List<GlobalSearchResult>,
    isLoading: Boolean,
    onResultSelected: (GlobalSearchResult) -> Unit,
    listState: LazyListState,
    dateFormats: ViewerDateFormats,
) {
    when {
        isLoading -> CenteredProgress()
        query.isBlank() -> CenteredMessage(stringResource(R.string.global_search_start))
        results.isEmpty() -> CenteredMessage(stringResource(R.string.no_search_results))
        else ->
            GlobalSearchList(
                chats = chats,
                results = results,
                onResultSelected = onResultSelected,
                listState = listState,
                dateFormats = dateFormats,
            )
    }
}

@Composable
private fun GlobalSearchList(
    chats: List<ChatSummary>,
    results: List<GlobalSearchResult>,
    onResultSelected: (GlobalSearchResult) -> Unit,
    listState: LazyListState,
    dateFormats: ViewerDateFormats,
) {
    val chatTitlesById = remember(chats) { chats.associate { it.chatId to it.title } }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(results, key = { it.eventId }) { result ->
            val chatTitle =
                chatTitlesById[result.chatId]
                    ?: stringResource(R.string.fallback_chat, result.chatId)
            GlobalSearchRow(
                result = result,
                chatTitle = chatTitle,
                dateFormats = dateFormats,
                onClick = { onResultSelected(result) },
            )
            HorizontalDivider()
        }
        if (results.size >= GLOBAL_SEARCH_RESULT_LIMIT) {
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

@Composable
private fun ChatSearchContent(
    chats: List<ChatSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    onChatSelected: (ChatSummary) -> Unit,
    listState: LazyListState,
    dateFormats: ViewerDateFormats,
    modifier: Modifier,
) {
    Column(modifier) {
        SearchTextField(
            value = query,
            onValueChange = onQueryChange,
            label = stringResource(R.string.chat_search_label),
        )
        if (chats.isEmpty()) {
            val message =
                stringResource(
                    if (query.isBlank()) R.string.no_chats else R.string.no_search_results,
                )
            CenteredMessage(message)
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(chats, key = { it.chatId }) { chat ->
                    ChatRow(chat, dateFormats, onClick = { onChatSelected(chat) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GlobalSearchRow(
    result: GlobalSearchResult,
    chatTitle: String,
    dateFormats: ViewerDateFormats,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
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
            Text(
                dateFormats.formatChatTime(result.timestamp),
                style = MaterialTheme.typography.labelMedium,
            )
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
        DeletedAwareText(
            text = result.displayText,
            kind = result.kind,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatRow(
    chat: ChatSummary,
    dateFormats: ViewerDateFormats,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .copyOnLongClick(
                    text = {
                        if (chat.subtitle.isNotBlank()) {
                            chat.title + " " + chat.subtitle
                        } else {
                            chat.title
                        }
                    },
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chat.subtitle.isNotBlank()) {
                Text(
                    "(" + chat.subtitle + ")",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            dateFormats.formatChatTime(chat.lastTimestamp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
