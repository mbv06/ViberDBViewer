package com.mbv.viberdbviewer.model

import android.content.Context
import com.mbv.viberdbviewer.MessageDirection
import com.mbv.viberdbviewer.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ChatSummary(
    val chatId: Long,
    val title: String,
    val subtitle: String,
    val lastTimestamp: Long,
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
) {
    companion object {
        fun from(context: Context) =
            MessageLabels(
                empty = context.getString(R.string.message_empty),
                image = context.getString(R.string.message_image),
                video = context.getString(R.string.message_video),
                sticker = context.getString(R.string.message_sticker),
                location = context.getString(R.string.message_location),
                contact = context.getString(R.string.message_contact),
                pinned = { context.getString(R.string.message_pinned, it) },
                pinnedEmpty = context.getString(R.string.message_pinned_empty),
                unknownType = { context.getString(R.string.message_unknown_type, it) },
                link = context.getString(R.string.message_link),
                audio = context.getString(R.string.message_audio),
                gif = context.getString(R.string.message_gif),
                file = context.getString(R.string.message_file),
                deleted = context.getString(R.string.message_deleted),
            )
    }
}

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
        messages[index].displayText.contains(trimmed, ignoreCase = true)
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
