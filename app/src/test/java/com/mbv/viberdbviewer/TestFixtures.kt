package com.mbv.viberdbviewer

import com.mbv.viberdbviewer.model.MessageLabels

internal fun testMessageLabels() =
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
