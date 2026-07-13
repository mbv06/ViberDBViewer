package com.mbv.viberdbviewer

internal object MessageDirection {
    const val INCOMING = 0
    const val OUTGOING = 1
}

internal object DesktopMessageType {
    const val HEART_REACTION = 0
    const val TEXT = 1
    const val IMAGE = 2
    const val VIDEO = 3
    const val STICKER = 4
    const val LOCATION = 5
    const val BUSINESS = 8
    const val LINK = 9
    const val CONTACT = 10
    const val FILE = 11
    const val PINNED = 15
    const val DELETED = 72
}

internal object DesktopClientFlag {
    const val NONE = 0
    const val EDIT_HISTORY = 256
    const val EDIT_HISTORY_VARIANT = 257
    const val ORPHANED_EDIT = 385
}

internal object DesktopContact {
    const val DEFAULT_SELF_ID = 1L
}

internal object AndroidConversationType {
    const val DIRECT = 0
    const val GROUP = 1
    const val COMMUNITY = 5
    const val SELF = 6

    val GROUP_TYPES = setOf(GROUP, COMMUNITY)
}

internal object AndroidParticipantState {
    const val INACTIVE = 0
    const val ACTIVE = 1
}

internal object AndroidMessageState {
    const val VISIBLE = 0
    const val DELETED = 1
}

internal object AndroidMessageType {
    const val TEXT = 0
    const val IMAGE = 1
    const val VIDEO = 3
    const val STICKER = 4
    const val LOCATION = 5
    const val BUSINESS = 7
    const val LINK = 8
    const val CONTACT = 9
    const val FILE = 10
    const val GIF = 1005
    const val REACTION = 1007
    const val DELETED = 1008
    const val AUDIO = 1009
    const val INSTANT_VIDEO = 1010
}
