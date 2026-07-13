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

        assertEquals(MessageKind.TEXT, formatMessage(DesktopMessageType.TEXT, "Hello", null, labels).kind)
        assertEquals(
            "Business notice",
            formatMessage(DesktopMessageType.BUSINESS, "Business notice", null, labels).displayText,
        )
        assertEquals(MessageKind.DELETED, formatMessage(DesktopMessageType.DELETED, "ignored", null, labels).kind)
        assertEquals("Deleted message", formatMessage(DesktopMessageType.DELETED, "ignored", null, labels).displayText)
        assertEquals("<image>", formatMessage(DesktopMessageType.IMAGE, null, null, labels).displayText)
        assertEquals("<video>", formatMessage(DesktopMessageType.VIDEO, null, null, labels).displayText)
        assertEquals(
            "Title\nhttps://example.com/a/b",
            formatMessage(
                DesktopMessageType.LINK,
                "Title",
                "{\"URL\":\"https:\\/\\/example.com\\/a\\/b\"}",
                labels,
            ).displayText,
        )
        assertEquals(MessageKind.AUDIO, formatMessage(DesktopMessageType.FILE, null, "{\"audio_ptt\":{}}", labels).kind)
        assertEquals(
            MessageKind.GIF,
            formatMessage(DesktopMessageType.FILE, null, "{\"MediaType\": \"GIF\"}", labels).kind,
        )
        assertEquals(MessageKind.FILE, formatMessage(DesktopMessageType.FILE, null, "{}", labels).kind)
        assertEquals(
            "Pinned: Important",
            formatMessage(DesktopMessageType.PINNED, null, "{\"pin\":{\"text\":\"Important\"}}", labels).displayText,
        )
    }

    @Test
    fun androidMessageFormatterMapsObservedExtraMimeValues() {
        val labels = labels()

        assertEquals("Hello", formatAndroidMessage(AndroidMessageType.TEXT, "Hello", null, labels).displayText)
        assertEquals(MessageKind.IMAGE, formatAndroidMessage(AndroidMessageType.IMAGE, null, null, labels).kind)
        assertEquals(MessageKind.VIDEO, formatAndroidMessage(AndroidMessageType.VIDEO, null, null, labels).kind)
        assertEquals(MessageKind.STICKER, formatAndroidMessage(AndroidMessageType.STICKER, null, null, labels).kind)
        assertEquals(MessageKind.LOCATION, formatAndroidMessage(AndroidMessageType.LOCATION, null, null, labels).kind)
        assertEquals(
            "Business notice",
            formatAndroidMessage(
                AndroidMessageType.BUSINESS,
                "[{\"Type\":\"txt\",\"Text\":\"Business notice\",\"TextSpans\":\"_\"}]",
                null,
                labels,
            ).displayText,
        )
        assertEquals(
            "Preview\nhttps://example.com",
            formatAndroidMessage(
                AndroidMessageType.LINK,
                null,
                "{\"Text\":\"Preview\",\"URL\":\"https://example.com\"}",
                labels,
            ).displayText,
        )
        assertEquals(MessageKind.CONTACT, formatAndroidMessage(AndroidMessageType.CONTACT, null, null, labels).kind)
        assertEquals(MessageKind.FILE, formatAndroidMessage(AndroidMessageType.FILE, null, null, labels).kind)
        assertEquals(MessageKind.GIF, formatAndroidMessage(AndroidMessageType.GIF, null, null, labels).kind)
        assertEquals(
            MessageKind.DELETED,
            formatAndroidMessage(AndroidMessageType.DELETED, "message_deleted/id", null, labels).kind,
        )
        assertEquals(MessageKind.AUDIO, formatAndroidMessage(AndroidMessageType.AUDIO, null, null, labels).kind)
        assertEquals(MessageKind.VIDEO, formatAndroidMessage(AndroidMessageType.INSTANT_VIDEO, null, null, labels).kind)
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
        direction = MessageDirection.INCOMING,
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
