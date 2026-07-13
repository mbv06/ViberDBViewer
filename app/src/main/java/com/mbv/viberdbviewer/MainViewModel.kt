package com.mbv.viberdbviewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mbv.viberdbviewer.data.ViberDatabaseRepository
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.GlobalSearchResult
import com.mbv.viberdbviewer.model.filterChats
import com.mbv.viberdbviewer.model.findMessageMatches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class ViewerUiState(
    val isStarting: Boolean = true,
    val isImporting: Boolean = false,
    val databaseReady: Boolean = false,
    val chats: List<ChatSummary> = emptyList(),
    val chatQuery: String = "",
    val isGlobalSearchVisible: Boolean = false,
    val globalSearchQuery: String = "",
    val globalSearchResults: List<GlobalSearchResult> = emptyList(),
    val isGlobalSearchLoading: Boolean = false,
    val selectedChat: ChatSummary? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoadingMessages: Boolean = false,
    val isMessageSearchVisible: Boolean = false,
    val messageQuery: String = "",
    val matchIndices: List<Int> = emptyList(),
    val activeMatchPosition: Int = -1,
    val scrollRequest: Long = 0,
    val error: String? = null,
) {
    val filteredChats: List<ChatSummary> get() = filterChats(chats, chatQuery)
    val activeMessageIndex: Int?
        get() = matchIndices.getOrNull(activeMatchPosition)
}

class MainViewModel(
    private val repository: ViberDatabaseRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var messageLoadJob: Job? = null
    private var globalSearchJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val ready = repository.openExisting()
                if (ready) loadChatsAfterOpen() else _state.update { it.copy(isStarting = false) }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        isStarting = false,
                        databaseReady = false,
                        error = repository.userMessage(error),
                    )
                }
            }
        }
    }

    fun importDatabase(uri: Uri) {
        if (_state.value.isImporting) return
        globalSearchJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, error = null) }
            try {
                repository.importDatabase(uri)
                loadChatsAfterOpen()
            } catch (error: Exception) {
                _state.update { it.copy(isImporting = false, isStarting = false, error = repository.userMessage(error)) }
            }
        }
    }

    fun updateChatQuery(query: String) {
        _state.update { it.copy(chatQuery = query) }
    }

    fun setGlobalSearchVisible(visible: Boolean) {
        globalSearchJob?.cancel()
        _state.update {
            if (visible) {
                it.copy(isGlobalSearchVisible = true, chatQuery = "")
            } else {
                it.copy(
                    isGlobalSearchVisible = false,
                    globalSearchQuery = "",
                    globalSearchResults = emptyList(),
                    isGlobalSearchLoading = false,
                )
            }
        }
    }

    fun updateGlobalSearchQuery(query: String) {
        globalSearchJob?.cancel()
        val needle = query.trim()
        _state.update {
            it.copy(
                globalSearchQuery = query,
                globalSearchResults = emptyList(),
                isGlobalSearchLoading = needle.isNotEmpty(),
            )
        }
        if (needle.isEmpty()) return

        globalSearchJob = viewModelScope.launch {
            try {
                delay(250.milliseconds)
                val results = repository.searchMessages(needle)
                if (_state.value.globalSearchQuery == query) {
                    _state.update {
                        it.copy(globalSearchResults = results, isGlobalSearchLoading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(isGlobalSearchLoading = false, error = repository.userMessage(error))
                }
            }
        }
    }

    fun selectChat(chat: ChatSummary) {
        openChat(chat)
    }

    fun selectGlobalSearchResult(result: GlobalSearchResult) {
        val current = _state.value
        val chat = current.chats.firstOrNull { it.chatId == result.chatId } ?: return
        openChat(chat, current.globalSearchQuery.trim(), result.eventId)
    }

    private fun openChat(chat: ChatSummary, initialQuery: String = "", targetEventId: Long? = null) {
        messageLoadJob?.cancel()
        _state.update {
            it.copy(
                selectedChat = chat,
                messages = emptyList(),
                isLoadingMessages = true,
                isMessageSearchVisible = initialQuery.isNotEmpty(),
                messageQuery = initialQuery,
                matchIndices = emptyList(),
                activeMatchPosition = -1,
                error = null,
            )
        }
        messageLoadJob = viewModelScope.launch {
            try {
                val messages = repository.loadMessages(chat.chatId)
                if (_state.value.selectedChat?.chatId == chat.chatId) {
                    val matches = findMessageMatches(messages, initialQuery)
                    val targetIndex = targetEventId?.let { id ->
                        messages.indexOfFirst { it.eventId == id }.takeIf { it >= 0 }
                    }
                    val targetMatchPosition = targetIndex?.let(matches::indexOf)?.takeIf { it >= 0 }
                        ?: matches.lastIndex
                    _state.update {
                        it.copy(
                            messages = messages,
                            isLoadingMessages = false,
                            matchIndices = matches,
                            activeMatchPosition = targetMatchPosition,
                            scrollRequest = it.scrollRequest + 1,
                        )
                    }
                }
            } catch (error: Exception) {
                _state.update { it.copy(isLoadingMessages = false, error = repository.userMessage(error)) }
            }
        }
    }

    fun closeChat() {
        messageLoadJob?.cancel()
        _state.update {
            it.copy(
                selectedChat = null,
                messages = emptyList(),
                isLoadingMessages = false,
                isMessageSearchVisible = false,
                messageQuery = "",
                matchIndices = emptyList(),
                activeMatchPosition = -1,
            )
        }
    }

    fun setMessageSearchVisible(visible: Boolean) {
        _state.update {
            if (visible) {
                it.copy(isMessageSearchVisible = true)
            } else {
                it.copy(
                    isMessageSearchVisible = false,
                    messageQuery = "",
                    matchIndices = emptyList(),
                    activeMatchPosition = -1,
                )
            }
        }
    }

    fun updateMessageQuery(query: String) {
        _state.update { current ->
            val matches = findMessageMatches(current.messages, query)
            current.copy(
                messageQuery = query,
                matchIndices = matches,
                activeMatchPosition = matches.lastIndex,
                scrollRequest = current.scrollRequest + 1,
            )
        }
    }

    fun previousMatch() {
        _state.update { current ->
            if (current.activeMatchPosition <= 0) current
            else current.copy(
                activeMatchPosition = current.activeMatchPosition - 1,
                scrollRequest = current.scrollRequest + 1,
            )
        }
    }

    fun nextMatch() {
        _state.update { current ->
            if (current.activeMatchPosition < 0 || current.activeMatchPosition >= current.matchIndices.lastIndex) current
            else current.copy(
                activeMatchPosition = current.activeMatchPosition + 1,
                scrollRequest = current.scrollRequest + 1,
            )
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun loadChatsAfterOpen() {
        val chats = repository.loadChats()
        _state.value = ViewerUiState(
            isStarting = false,
            databaseReady = true,
            chats = chats,
        )
    }


    override fun onCleared() {
        repository.close()
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(ViberDatabaseRepository(appContext)) as T
            }
            throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
        }
    }
}
