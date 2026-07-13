package com.mbv.viberdbviewer.model

import com.mbv.viberdbviewer.AndroidMessageType
import com.mbv.viberdbviewer.DesktopMessageType
import com.mbv.viberdbviewer.MessageDirection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ChatSummary(
    val chatId: Long,
    val title: String,
    val subtitle: String,
    val lastTimestamp: Long,
    val participantCount: Int,
    val isGroup: Boolean,
    val searchNumber: String = "",
)

data class ChatMessage(
    val eventId: Long,
    val timestamp: Long,
    val direction: Int,
    val senderName: String,
    val kind: MessageKind,
    val displayText: String,
    val searchableText: String,
) {
    val isOutgoing: Boolean get() = direction == MessageDirection.OUTGOING
}

data class GlobalSearchResult(
    val chatId: Long,
    val eventId: Long,
    val timestamp: Long,
    val senderName: String,
    val kind: MessageKind,
    val displayText: String,
)

enum class MessageKind {
    TEXT,
    LINK,
    IMAGE,
    VIDEO,
    STICKER,
    LOCATION,
    CONTACT,
    AUDIO,
    GIF,
    FILE,
    PINNED,
    DELETED,
    UNKNOWN,
}

data class FormattedMessage(
    val kind: MessageKind,
    val displayText: String,
    val searchableText: String = displayText,
)

class MessageLabels(
    val empty: String,
    val image: String,
    val video: String,
    val sticker: String,
    val location: String,
    val contact: String,
    val pinned: (String) -> String,
    val pinnedEmpty: String,
    val unknownType: (Int) -> String,
    val link: String,
    val audio: String,
    val gif: String,
    val file: String,
    val deleted: String,
)

data class ContactRecord(
    val contactId: Long,
    val name: String?,
    val clientName: String?,
    val number: String?,
    val viberName: String? = null,
) {
    fun displayName(fallback: String): String =
        name
            .clean()
            .orEmpty()
            .ifEmpty { clientName.clean().orEmpty() }
            .ifEmpty { viberName.clean().orEmpty() }
            .ifEmpty { number.clean().orEmpty() }
            .ifEmpty { fallback }
}

fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun normalizePhone(value: String): String = value.filter(Char::isDigit)

fun filterChats(
    chats: List<ChatSummary>,
    query: String,
): List<ChatSummary> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return chats
    val digits = normalizePhone(trimmed)
    return chats.filter { chat ->
        chat.title.contains(trimmed, ignoreCase = true) ||
            chat.searchNumber.contains(trimmed, ignoreCase = true) ||
            (digits.isNotEmpty() && normalizePhone(chat.searchNumber).contains(digits))
    }
}

fun findMessageMatches(
    messages: List<ChatMessage>,
    query: String,
): List<Int> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    return messages.indices.filter { index ->
        messages[index].searchableText.contains(trimmed, ignoreCase = true)
    }
}

fun findDaySeparatorIndices(
    messages: List<ChatMessage>,
    zoneId: ZoneId,
): Set<Int> {
    val separators = HashSet<Int>()
    var previousDay: LocalDate? = null
    messages.forEachIndexed { index, message ->
        val day = Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
        if (index == 0 || day != previousDay) separators += index
        previousDay = day
    }
    return separators
}

fun formatMessage(
    type: Int,
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage =
    when (type) {
        DesktopMessageType.TEXT -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
        DesktopMessageType.IMAGE -> formatted(MessageKind.IMAGE, labels.image)
        DesktopMessageType.VIDEO -> formatted(MessageKind.VIDEO, labels.video)
        DesktopMessageType.STICKER -> formatted(MessageKind.STICKER, labels.sticker)
        DesktopMessageType.LOCATION -> formatted(MessageKind.LOCATION, labels.location)
        DesktopMessageType.BUSINESS -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
        DesktopMessageType.LINK -> formatLink(body, info, labels)
        DesktopMessageType.CONTACT -> formatted(MessageKind.CONTACT, labels.contact)
        DesktopMessageType.FILE -> formatFile(info, labels)
        DesktopMessageType.PINNED -> {
            val text = body.clean() ?: extractJsonString(info, "text").clean()
            formatted(MessageKind.PINNED, text?.let(labels.pinned) ?: labels.pinnedEmpty)
        }
        DesktopMessageType.DELETED -> formatted(MessageKind.DELETED, labels.deleted)
        else -> formatted(MessageKind.UNKNOWN, labels.unknownType(type))
    }

fun formatAndroidMessage(
    extraMime: Int,
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage =
    when (extraMime) {
        AndroidMessageType.TEXT -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
        AndroidMessageType.IMAGE -> formatted(MessageKind.IMAGE, labels.image)
        AndroidMessageType.VIDEO -> formatted(MessageKind.VIDEO, labels.video)
        AndroidMessageType.STICKER -> formatted(MessageKind.STICKER, labels.sticker)
        AndroidMessageType.LOCATION -> formatted(MessageKind.LOCATION, labels.location)
        AndroidMessageType.BUSINESS ->
            formatted(
                MessageKind.TEXT,
                extractJsonString(body, "Text").clean() ?: labels.empty,
            )
        AndroidMessageType.LINK -> formatLink(body, info, labels)
        AndroidMessageType.CONTACT -> formatted(MessageKind.CONTACT, labels.contact)
        AndroidMessageType.FILE -> formatted(MessageKind.FILE, labels.file)
        AndroidMessageType.GIF -> formatted(MessageKind.GIF, labels.gif)
        AndroidMessageType.DELETED -> formatted(MessageKind.DELETED, labels.deleted)
        AndroidMessageType.AUDIO -> formatted(MessageKind.AUDIO, labels.audio)
        AndroidMessageType.INSTANT_VIDEO -> formatted(MessageKind.VIDEO, labels.video)
        else -> formatted(MessageKind.UNKNOWN, labels.unknownType(extraMime))
    }

private fun formatted(
    kind: MessageKind,
    text: String,
) = FormattedMessage(kind, text, text)

private fun formatLink(
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage {
    val text =
        body.clean()
            ?: extractJsonString(info, "Text").clean()
            ?: extractJsonString(info, "Title").clean()
    val url = extractJsonString(info, "URL").clean()
    val visible =
        when {
            text != null && url != null && !text.contains(url, ignoreCase = true) -> "$text\n$url"
            text != null -> text
            url != null -> url
            else -> labels.link
        }
    return formatted(MessageKind.LINK, visible)
}

private fun formatFile(
    info: String?,
    labels: MessageLabels,
): FormattedMessage {
    val source = info.orEmpty()
    return when {
        audioPttPattern.containsMatchIn(source) ->
            formatted(MessageKind.AUDIO, labels.audio)
        gifMediaTypePattern.containsMatchIn(source) -> formatted(MessageKind.GIF, labels.gif)
        else -> formatted(MessageKind.FILE, labels.file)
    }
}

private val audioPttPattern = Regex("\"audio_ptt\"\\s*:", RegexOption.IGNORE_CASE)
private val gifMediaTypePattern = Regex("\"MediaType\"\\s*:\\s*\"GIF\"", RegexOption.IGNORE_CASE)

internal fun extractJsonString(
    json: String?,
    key: String,
): String? {
    if (json.isNullOrEmpty()) return null
    val escapedKey = Regex.escape(key)
    val match =
        Regex("\"$escapedKey\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json) ?: return null
    return decodeJsonString(match.groupValues[1])
}

private fun decodeJsonString(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '\\' || index + 1 >= value.length) {
            result.append(char)
            index++
            continue
        }
        when (val escaped = value[index + 1]) {
            '"', '\\', '/' -> result.append(escaped)
            'b' -> result.append('\b')
            'f' -> result.append('\u000C')
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            'u' -> {
                val end = index + 6
                val code = if (end <= value.length) value.substring(index + 2, end).toIntOrNull(16) else null
                if (code != null) {
                    result.append(code.toChar())
                    index += 6
                    continue
                } else {
                    result.append('u')
                }
            }
            else -> result.append(escaped)
        }
        index += 2
    }
    return result.toString()
}
