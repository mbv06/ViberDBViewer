package com.mbv.viberdbviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mbv.viberdbviewer.model.filterChats
import com.mbv.viberdbviewer.ui.theme.ViberDBViewerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ViberDBViewerTheme {
                ViewerRoute()
            }
        }
    }
}

@Composable
private fun ViewerRoute(viewerViewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val state by viewerViewModel.state.collectAsState()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewerViewModel::importDatabase)
        }
    val actions =
        remember(viewerViewModel, picker) {
            ViewerActions(
                pickDatabase = {
                    picker.launch(
                        arrayOf(
                            "application/x-sqlite3",
                            "application/vnd.sqlite3",
                            "application/octet-stream",
                        ),
                    )
                },
                updateChatQuery = viewerViewModel::updateChatQuery,
                setGlobalSearchVisible = viewerViewModel::setGlobalSearchVisible,
                updateGlobalSearchQuery = viewerViewModel::updateGlobalSearchQuery,
                selectGlobalSearchResult = viewerViewModel::selectGlobalSearchResult,
                selectChat = viewerViewModel::selectChat,
                closeChat = viewerViewModel::closeChat,
                setMessageSearchVisible = viewerViewModel::setMessageSearchVisible,
                updateMessageQuery = viewerViewModel::updateMessageQuery,
                previousMatch = viewerViewModel::previousMatch,
                nextMatch = viewerViewModel::nextMatch,
            )
        }

    BackHandler(enabled = state.selectedChat != null || state.isGlobalSearchVisible) {
        if (state.selectedChat != null) {
            viewerViewModel.closeChat()
        } else {
            viewerViewModel.setGlobalSearchVisible(false)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        ViewerApp(state = state, actions = actions)
    }

    state.error?.let { message ->
        DatabaseErrorDialog(message = message, onDismiss = viewerViewModel::dismissError)
    }
}

@Composable
private fun DatabaseErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
        title = { Text(stringResource(R.string.database_error_title)) },
        text = { Text(message) },
    )
}

@Composable
fun ViewerApp(
    state: ViewerUiState,
    actions: ViewerActions,
) {
    val chatListState = rememberLazyListState()
    val globalSearchListState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        when {
            state.isStarting -> CenteredProgressWithText(stringResource(R.string.loading_saved_database))
            !state.databaseReady -> DatabasePickerScreen(onPickDatabase = actions.pickDatabase)
            state.selectedChat == null -> {
                val displayedChats =
                    if (state.isGlobalSearchVisible) {
                        state.chats
                    } else {
                        remember(state.chats, state.chatQuery) {
                            filterChats(state.chats, state.chatQuery)
                        }
                    }
                ChatListScreen(
                    state = state.toChatListUi(displayedChats),
                    actions = actions,
                    chatListState = chatListState,
                    globalSearchListState = globalSearchListState,
                )
            }
            else ->
                ConversationScreen(
                    state = state.toConversationUi(state.selectedChat),
                    actions = actions,
                )
        }
    }

    if (state.isImporting) ImportingDialog()
}

@Composable
private fun ImportingDialog() {
    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
    ) {
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
            Button(onClick = onPickDatabase) {
                Text(stringResource(R.string.action_select_database))
            }
        }
    }
}
