package com.mbv.viberdbviewer

import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.ContactRecord
import com.mbv.viberdbviewer.model.MessageKind
import com.mbv.viberdbviewer.model.MessageLabels
import com.mbv.viberdbviewer.model.filterChats
import com.mbv.viberdbviewer.model.findDaySeparatorIndices
import com.mbv.viberdbviewer.model.findMessageMatches
import com.mbv.viberdbviewer.model.formatAndroidMessage
import com.mbv.viberdbviewer.model.formatMessage
import com.mbv.viberdbviewer.model.normalizePhone
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ModelLogicTest {
    @Test
    fun contactNameUsesRequiredFallbackOrder() {
        assertEquals("Name", ContactRecord(1, " Name ", "Viber", "+380").displayName("fallback"))
        assertEquals("Viber", ContactRecord(1, "", "Viber", "+380").displayName("fallback"))
        assertEquals("+380", ContactRecord(1, null, null, "+380").displayName("fallback"))
        assertEquals(
            "Viber name",
            ContactRecord(1, null, null, "+380", "Viber name").displayName("fallback"),
        )
        assertEquals("fallback", ContactRecord(1, null, null, null).displayName("fallback"))
    }

    @Test
    fun chatSearchMatchesNameAndNormalizedNumber() {
        val chats =
            listOf(
                ChatSummary(1, "Alex", "+380 67 123", 1, 2, false, "+380 67 123"),
                ChatSummary(2, "Work", "5 participants", 2, 5, true),
            )
        assertEquals(listOf(1L), filterChats(chats, "alex").map { it.chatId })
        assertEquals(listOf(1L), filterChats(chats, "067123").map { it.chatId })
        assertEquals("38067123", normalizePhone("+380 (67) 123"))
    }

    @Test
    fun messageFormatterMapsTextLinksAndPlaceholders() {
        val labels =
            MessageLabels(
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
    fun androidMessageFormatterMapsObservedExtraMimeValues() {
        val labels = labels()

        assertEquals("Hello", formatAndroidMessage(0, "Hello", null, labels).displayText)
        assertEquals(MessageKind.IMAGE, formatAndroidMessage(1, null, null, labels).kind)
        assertEquals(MessageKind.VIDEO, formatAndroidMessage(3, null, null, labels).kind)
        assertEquals(MessageKind.STICKER, formatAndroidMessage(4, null, null, labels).kind)
        assertEquals(MessageKind.LOCATION, formatAndroidMessage(5, null, null, labels).kind)
        assertEquals(
            "Business notice",
            formatAndroidMessage(
                7,
                "[{\"Type\":\"txt\",\"Text\":\"Business notice\",\"TextSpans\":\"_\"}]",
                null,
                labels,
            ).displayText,
        )
        assertEquals(
            "Preview\nhttps://example.com",
            formatAndroidMessage(
                8,
                null,
                "{\"Text\":\"Preview\",\"URL\":\"https://example.com\"}",
                labels,
            ).displayText,
        )
        assertEquals(MessageKind.CONTACT, formatAndroidMessage(9, null, null, labels).kind)
        assertEquals(MessageKind.FILE, formatAndroidMessage(10, null, null, labels).kind)
        assertEquals(MessageKind.GIF, formatAndroidMessage(1005, null, null, labels).kind)
        assertEquals(
            MessageKind.DELETED,
            formatAndroidMessage(1008, "message_deleted/id", null, labels).kind,
        )
        assertEquals(MessageKind.AUDIO, formatAndroidMessage(1009, null, null, labels).kind)
        assertEquals(MessageKind.VIDEO, formatAndroidMessage(1010, null, null, labels).kind)
        assertEquals(MessageKind.UNKNOWN, formatAndroidMessage(777, null, null, labels).kind)
    }

    @Test
    fun messageSearchReturnsIndicesWithoutFilteringMessages() {
        val messages =
            listOf(
                message(1, "First"),
                message(2, "Searchable text"),
                message(3, "Another SEARCHABLE"),
            )
        assertEquals(listOf(1, 2), findMessageMatches(messages, "searchable"))
        assertEquals(emptyList<Int>(), findMessageMatches(messages, ""))
    }

    @Test
    fun daySeparatorsAreCalculatedOncePerLocalDay() {
        val zone = ZoneId.of("Europe/Kyiv")
        val firstDay = ZonedDateTime.of(2026, 7, 13, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val secondDay = ZonedDateTime.of(2026, 7, 14, 0, 1, 0, 0, zone).toInstant().toEpochMilli()
        val messages =
            listOf(
                message(1, "First", firstDay),
                message(2, "Second", firstDay + 30_000),
                message(3, "Third", secondDay),
            )

        assertEquals(setOf(0, 2), findDaySeparatorIndices(messages, zone))
    }

    @Test
    fun webLinksDetectHttpAndHttpsWithoutTrailingPunctuation() {
        val text = "Open https://example.com/a?q=1, then http://example.org/test. Ignore ftp://example.net"

        assertEquals(
            listOf("https://example.com/a?q=1", "http://example.org/test"),
            findWebLinks(text).map { it.url },
        )
    }

    @Test
    fun webLinksStopAtWhitespaceBracketsAndQuotes() {
        val text = "https://example.com/one next <https://example.com/two> [https://example.com/three] \"https://example.com/four\""

        assertEquals(
            listOf(
                "https://example.com/one",
                "https://example.com/two",
                "https://example.com/three",
                "https://example.com/four",
            ),
            findWebLinks(text).map { it.url },
        )
    }

    private fun message(
        id: Long,
        text: String,
        timestamp: Long = id,
    ) = ChatMessage(
        eventId = id,
        timestamp = timestamp,
        direction = 0,
        senderName = "",
        kind = MessageKind.TEXT,
        displayText = text,
        searchableText = text,
    )

    private fun labels() =
        MessageLabels(
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
}
