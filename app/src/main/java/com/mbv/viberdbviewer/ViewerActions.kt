package com.mbv.viberdbviewer

import androidx.compose.runtime.Immutable
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.GlobalSearchResult

@Immutable
data class ViewerActions(
    val pickDatabase: () -> Unit = {},
    val updateChatQuery: (String) -> Unit = {},
    val setGlobalSearchVisible: (Boolean) -> Unit = {},
    val updateGlobalSearchQuery: (String) -> Unit = {},
    val selectGlobalSearchResult: (GlobalSearchResult) -> Unit = {},
    val selectChat: (ChatSummary) -> Unit = {},
    val closeChat: () -> Unit = {},
    val setMessageSearchVisible: (Boolean) -> Unit = {},
    val updateMessageQuery: (String) -> Unit = {},
    val previousMatch: () -> Unit = {},
    val nextMatch: () -> Unit = {},
)

@Immutable
data class ChatListUiState(
    val chats: List<ChatSummary>,
    val query: String = "",
    val isGlobalSearchVisible: Boolean = false,
    val globalSearchQuery: String = "",
    val globalSearchResults: List<GlobalSearchResult> = emptyList(),
    val isGlobalSearchLoading: Boolean = false,
)

@Immutable
data class ConversationUiState(
    val chat: ChatSummary,
    val messages: List<ChatMessage> = emptyList(),
    val daySeparatorIndices: Set<Int> = emptySet(),
    val isLoading: Boolean = false,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val matchCount: Int = 0,
    val activeMatchPosition: Int = -1,
    val activeMessageIndex: Int? = null,
    val scrollRequest: Long = 0,
)

fun ViewerUiState.toChatListUi(displayedChats: List<ChatSummary>) =
    ChatListUiState(
        chats = displayedChats,
        query = chatQuery,
        isGlobalSearchVisible = isGlobalSearchVisible,
        globalSearchQuery = globalSearchQuery,
        globalSearchResults = globalSearchResults,
        isGlobalSearchLoading = isGlobalSearchLoading,
    )

fun ViewerUiState.toConversationUi(chat: ChatSummary) =
    ConversationUiState(
        chat = chat,
        messages = messages,
        daySeparatorIndices = daySeparatorIndices,
        isLoading = isLoadingMessages,
        searchVisible = isMessageSearchVisible,
        searchQuery = messageQuery,
        matchCount = matchIndices.size,
        activeMatchPosition = activeMatchPosition,
        activeMessageIndex = activeMessageIndex,
        scrollRequest = scrollRequest,
    )
