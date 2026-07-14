package com.mbv.viberdbviewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.filterChats
import com.mbv.viberdbviewer.ui.theme.ViberDBViewerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatListScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun filtersByNumberAndOpensSelectedChat() {
        val allChats =
            listOf(
                ChatSummary(1, "Alice", "+380 67 123", 2, false, "+380 67 123"),
                ChatSummary(2, "Team", "3 participants", 3, true),
            )
        var query by mutableStateOf("")
        var selectedId: Long? = null

        composeRule.setContent {
            ViberDBViewerTheme {
                ChatListScreen(
                    state =
                        ChatListUiState(
                            chats = filterChats(allChats, query),
                            query = query,
                        ),
                    actions =
                        ViewerActions(
                            updateChatQuery = { query = it },
                            selectChat = { selectedId = it.chatId },
                        ),
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAT_SEARCH_FIELD_TAG).performTextInput("067123")
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Team").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Alice").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1L, selectedId)
    }
}
