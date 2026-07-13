# Viber database knowledge base

This document records behavior verified against Viber databases used by this project. Treat numeric enums as observations rather than a stable public Viber API, and update this file whenever another database version reveals different behavior.

## Core relationships

- ChatInfo.ChatID identifies a conversation.
- ChatRelation connects conversations to Contact rows.
- Events.ChatID connects an event to a conversation.
- Messages.EventID contains the message payload for an event.
- Events.TimeStamp is a Unix timestamp in milliseconds.
- Events.Direction = 1 is outgoing; 0 is incoming.
- The contact most frequently referenced by outgoing events is treated as the local user.

## Observed Messages.Type values

| Type | Meaning | Viewer behavior |
| ---: | --- | --- |
| 0 | Heart/reaction helper event | Filter out in both chat aggregation and conversation queries. It must not affect the last-message timestamp. |
| 1 | Text message | Display Body. |
| 2 | Image | Display an image placeholder. |
| 3 | Video | Display a video placeholder. |
| 4 | Sticker | Display a sticker placeholder. |
| 5 | Location | Display a location placeholder. |
| 8 | Business message | Display Body as normal searchable text. |
| 9 | Rich link message | Display its visible text and the URL from JSON Info; HTTP(S) URLs are clickable. |
| 10 | Contact card | Display a contact placeholder. |
| 11 | File/audio/GIF | Derive the subtype from JSON Info and display a matching placeholder. |
| 15 | Pinned/service message | Display Body, or pin.text from JSON Info when Body is empty. |
| 72 | Deleted message | Display a localized “Deleted message” notice using subdued, small italic text. |
| other | Unknown | Display the numeric type in a generic placeholder so new values remain visible. |

## Global message search

- Global search scans only messages that can produce visible text, formats them with the same rules as the conversation view, and compares text case-insensitively in Kotlin so non-ASCII scripts work correctly.
- Input is debounced by 250 milliseconds.
- Results are ordered newest-first and capped at 200 items.
- Selecting a result opens its chat with in-chat search active and scrolls to the selected event.

## Query invariants

- Conversation messages are ordered by Events.TimeStamp, then SortOrder, then EventID.
- Empty chats and chats containing only type 0 events are hidden.
- Media payload and thumbnail paths are never opened by the viewer.
- The imported database is copied into private app storage and opened read-only.
