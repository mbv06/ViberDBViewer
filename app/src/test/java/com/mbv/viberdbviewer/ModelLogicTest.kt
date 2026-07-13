package com.mbv.viberdbviewer

import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.ContactRecord
import com.mbv.viberdbviewer.model.MessageKind
import com.mbv.viberdbviewer.model.MessageLabels
import com.mbv.viberdbviewer.model.filterChats
import com.mbv.viberdbviewer.model.findMessageMatches
import com.mbv.viberdbviewer.model.formatMessage
import com.mbv.viberdbviewer.model.normalizePhone
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLogicTest {
    @Test
    fun contactNameUsesRequiredFallbackOrder() {
        assertEquals("Name", ContactRecord(1, " Name ", "Viber", "+380").displayName("fallback"))
        assertEquals("Viber", ContactRecord(1, "", "Viber", "+380").displayName("fallback"))
        assertEquals("+380", ContactRecord(1, null, null, "+380").displayName("fallback"))
        assertEquals("fallback", ContactRecord(1, null, null, null).displayName("fallback"))
    }


    @Test
    fun chatSearchMatchesNameAndNormalizedNumber() {
        val chats = listOf(
            ChatSummary(1, "Alex", "+380 67 123", 1, 2, false, "+380 67 123"),
            ChatSummary(2, "Work", "5 participants", 2, 5, true),
        )
        assertEquals(listOf(1L), filterChats(chats, "alex").map { it.chatId })
        assertEquals(listOf(1L), filterChats(chats, "067123").map { it.chatId })
        assertEquals("38067123", normalizePhone("+380 (67) 123"))
    }

    @Test
    fun messageFormatterMapsTextLinksAndPlaceholders() {
        val labels = MessageLabels(
            empty = "<empty message>",
            image = "<image>",
            video = "<video>",
            sticker = "<sticker>",
            location = "<location>",
            contact = "<contact>",
            pinned = { "Pinned: $it" },
            pinnedEmpty = "<pinned message>",
            unknownType = { "<message type $it>" },
            link = "<link>",
            audio = "<audio>",
            gif = "<GIF>",
            file = "<file>",
            deleted = "Deleted message",
        )

        assertEquals(MessageKind.TEXT, formatMessage(1, "Hello", null, labels).kind)
        assertEquals("Business notice", formatMessage(8, "Business notice", null, labels).displayText)
        assertEquals(MessageKind.DELETED, formatMessage(72, "ignored", null, labels).kind)
        assertEquals("Deleted message", formatMessage(72, "ignored", null, labels).displayText)
        assertEquals("<image>", formatMessage(2, null, null, labels).displayText)
        assertEquals("<video>", formatMessage(3, null, null, labels).displayText)
        assertEquals(
            "Title\nhttps://example.com/a/b",
            formatMessage(9, "Title", "{\"URL\":\"https:\\/\\/example.com\\/a\\/b\"}", labels).displayText,
        )
        assertEquals(MessageKind.AUDIO, formatMessage(11, null, "{\"audio_ptt\":{}}", labels).kind)
        assertEquals(MessageKind.GIF, formatMessage(11, null, "{\"MediaType\": \"GIF\"}", labels).kind)
        assertEquals(MessageKind.FILE, formatMessage(11, null, "{}", labels).kind)
        assertEquals(
            "Pinned: Important",
            formatMessage(15, null, "{\"pin\":{\"text\":\"Important\"}}", labels).displayText,
        )
    }

    @Test
    fun messageSearchReturnsIndicesWithoutFilteringMessages() {
        val messages = listOf(
            message(1, "First"),
            message(2, "Searchable text"),
            message(3, "Another SEARCHABLE"),
        )
        assertEquals(listOf(1, 2), findMessageMatches(messages, "searchable"))
        assertEquals(emptyList<Int>(), findMessageMatches(messages, ""))
    }

    @Test
    fun webLinksDetectHttpAndHttpsWithoutTrailingPunctuation() {
        val text = "Open https://example.com/a?q=1, then http://example.org/test. Ignore ftp://example.net"

        assertEquals(
            listOf("https://example.com/a?q=1", "http://example.org/test"),
            findWebLinks(text).map { it.url },
        )
    }

    private fun message(id: Long, text: String) = ChatMessage(
        eventId = id,
        timestamp = id,
        direction = 0,
        senderName = "",
        kind = MessageKind.TEXT,
        displayText = text,
        searchableText = text,
    )
}
