package com.mbv.viberdbviewer.model

import com.mbv.viberdbviewer.AndroidMessageType
import com.mbv.viberdbviewer.DesktopMessageType

fun formatMessage(
    type: Int,
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage =
    when (type) {
        DesktopMessageType.TEXT,
        DesktopMessageType.BUSINESS,
        -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
        DesktopMessageType.LINK -> formatLink(body, info, labels)
        DesktopMessageType.FILE -> formatFile(info, labels)
        DesktopMessageType.PINNED -> formatPinned(body, info, labels)
        DesktopMessageType.DELETED -> formatted(MessageKind.DELETED, labels.deleted)
        else -> formatDesktopPlaceholder(type, labels)
    }

fun formatAndroidMessage(
    extraMime: Int,
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage =
    when (extraMime) {
        AndroidMessageType.TEXT -> formatted(MessageKind.TEXT, body.clean() ?: labels.empty)
        AndroidMessageType.BUSINESS ->
            formatted(
                MessageKind.TEXT,
                extractJsonString(body, "Text").clean() ?: labels.empty,
            )
        AndroidMessageType.LINK -> formatLink(body, info, labels)
        else -> formatAndroidPlaceholder(extraMime, labels)
    }

private fun formatDesktopPlaceholder(
    type: Int,
    labels: MessageLabels,
): FormattedMessage =
    when (type) {
        DesktopMessageType.IMAGE -> formatted(MessageKind.IMAGE, labels.image)
        DesktopMessageType.VIDEO -> formatted(MessageKind.VIDEO, labels.video)
        DesktopMessageType.STICKER -> formatted(MessageKind.STICKER, labels.sticker)
        DesktopMessageType.LOCATION -> formatted(MessageKind.LOCATION, labels.location)
        DesktopMessageType.CONTACT -> formatted(MessageKind.CONTACT, labels.contact)
        else -> formatted(MessageKind.UNKNOWN, labels.unknownType(type))
    }

private fun formatAndroidPlaceholder(
    extraMime: Int,
    labels: MessageLabels,
): FormattedMessage =
    when (extraMime) {
        AndroidMessageType.IMAGE -> formatted(MessageKind.IMAGE, labels.image)
        AndroidMessageType.VIDEO,
        AndroidMessageType.INSTANT_VIDEO,
        -> formatted(MessageKind.VIDEO, labels.video)
        AndroidMessageType.STICKER -> formatted(MessageKind.STICKER, labels.sticker)
        AndroidMessageType.LOCATION -> formatted(MessageKind.LOCATION, labels.location)
        AndroidMessageType.CONTACT -> formatted(MessageKind.CONTACT, labels.contact)
        AndroidMessageType.FILE -> formatted(MessageKind.FILE, labels.file)
        AndroidMessageType.GIF -> formatted(MessageKind.GIF, labels.gif)
        AndroidMessageType.DELETED -> formatted(MessageKind.DELETED, labels.deleted)
        AndroidMessageType.AUDIO -> formatted(MessageKind.AUDIO, labels.audio)
        else -> formatted(MessageKind.UNKNOWN, labels.unknownType(extraMime))
    }

private fun formatted(
    kind: MessageKind,
    text: String,
) = FormattedMessage(kind, text)

private fun formatPinned(
    body: String?,
    info: String?,
    labels: MessageLabels,
): FormattedMessage {
    val text = body.clean() ?: extractJsonString(info, "text").clean()
    return formatted(MessageKind.PINNED, text?.let(labels.pinned) ?: labels.pinnedEmpty)
}

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
        audioPttPattern.containsMatchIn(source) -> formatted(MessageKind.AUDIO, labels.audio)
        gifMediaTypePattern.containsMatchIn(source) -> formatted(MessageKind.GIF, labels.gif)
        else -> formatted(MessageKind.FILE, labels.file)
    }
}

private val audioPttPattern = Regex("\"audio_ptt\"\\s*:", RegexOption.IGNORE_CASE)
private val gifMediaTypePattern = Regex("\"MediaType\"\\s*:\\s*\"GIF\"", RegexOption.IGNORE_CASE)
