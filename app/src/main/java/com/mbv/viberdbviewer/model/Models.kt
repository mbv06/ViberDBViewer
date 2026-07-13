package com.mbv.viberdbviewer.model

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
    val isOutgoing: Boolean get() = direction == 1
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
) {
    fun displayName(fallback: String): String =
        name.clean().orEmpty()
            .ifEmpty { clientName.clean().orEmpty() }
            .ifEmpty { number.clean().orEmpty() }
            .ifEmpty { fallback }
}

fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

fun normalizePhone(value: String): String = value.filter(Char::isDigit)


fun filterChats(chats: List<ChatSummary>, query: String): List<ChatSummary> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return chats
    val digits = normalizePhone(trimmed)
    return chats.filter { chat ->
        chat.title.contains(trimmed, ignoreCase = true) ||
            chat.searchNumber.contains(trimmed, ignoreCase = true) ||
            (digits.isNotEmpty() && normalizePhone(chat.searchNumber).contains(digits))
    }
}

fun findMessageMatches(messages: List<ChatMessage>, query: String): List<Int> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    return messages.indices.filter { index ->
        messages[index].searchableText.contains(trimmed, ignoreCase = true)
    }
}

fun formatMessage(
    type: Int,
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage = when (type) {
    1 -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
    2 -> formatted(MessageKind.IMAGE, labels.image)
    3 -> formatted(MessageKind.VIDEO, labels.video)
    4 -> formatted(MessageKind.STICKER, labels.sticker)
    5 -> formatted(MessageKind.LOCATION, labels.location)
    8 -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
    9 -> formatLink(body, info, labels)
    10 -> formatted(MessageKind.CONTACT, labels.contact)
    11 -> formatFile(info, labels)
    15 -> {
        val text = body.clean() ?: extractJsonString(info, "text").clean()
        formatted(MessageKind.PINNED, text?.let(labels.pinned) ?: labels.pinnedEmpty)
    }
    72 -> formatted(MessageKind.DELETED, labels.deleted)
    else -> formatted(MessageKind.UNKNOWN, labels.unknownType(type))
}

private fun formatted(kind: MessageKind, text: String) = FormattedMessage(kind, text, text)

private fun formatLink(body: String?, info: String?, labels: MessageLabels): FormattedMessage {
    val text = body.clean() ?: extractJsonString(info, "Text").clean()
    val url = extractJsonString(info, "URL").clean()
    val visible = when {
        text != null && url != null && !text.contains(url, ignoreCase = true) -> "$text\n$url"
        text != null -> text
        url != null -> url
        else -> labels.link
    }
    return formatted(MessageKind.LINK, visible)
}

private fun formatFile(info: String?, labels: MessageLabels): FormattedMessage {
    val source = info.orEmpty()
    return when {
        Regex("\"audio_ptt\"\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(source) ->
            formatted(MessageKind.AUDIO, labels.audio)
        Regex("\"MediaType\"\\s*:\\s*\"GIF\"", RegexOption.IGNORE_CASE)
            .containsMatchIn(source) -> formatted(MessageKind.GIF, labels.gif)
        else -> formatted(MessageKind.FILE, labels.file)
    }
}

internal fun extractJsonString(json: String?, key: String): String? {
    if (json.isNullOrEmpty()) return null
    val escapedKey = Regex.escape(key)
    val match = Regex("\"$escapedKey\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
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
