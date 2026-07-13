# Viber database format notes

This document describes the schema mappings and compatibility rules implemented by Viber DB Viewer. 
Viber's SQLite formats are undocumented and may change between releases, so the identifiers and numeric values below describe observed database structures and should not be treated as a stable public API.


## Supported database families

The viewer detects the format from required tables and columns, not from the file name or number of tables:

- Desktop `viber.db`: `ChatInfo`, `ChatRelation`, `Contact`, `Events`, and `Messages`.
- Android `viber_messages`: `conversations`, `participants`, `participants_info`, and `messages`.

## Desktop relationships

- ChatInfo.ChatID identifies a conversation.
- ChatRelation connects conversations to Contact rows.
- Events.ChatID connects an event to a conversation.
- Messages.EventID contains the message payload for an event.
- Events.TimeStamp is a Unix timestamp in milliseconds.
- Events.Direction = 1 is outgoing; 0 is incoming.
- The contact most frequently referenced by outgoing events is treated as the local user.

## Known desktop message types

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

### Desktop message edits

Desktop databases may contain both an updated message row and technical rows representing its edit history. The viewer applies the following compatibility rules so only the final visible message is shown:

- Rows with `Messages.ClientFlag` exactly equal to `256` or `257` are treated as edit-history rows and hidden.
- The updated original row remains visible at its original timestamp.

## Android relationships

- `conversations._id` identifies a conversation; `messages.conversation_id` links messages to it.
- `messages.participant_id` links to `participants._id`, then `participants.participant_info_id` links to `participants_info._id`.
- Contact names use `contact_name`, then `display_name`, then `viber_name`, then `number`.
- `conversations.participant_id_1` identifies the other contact in a direct conversation.
- `messages.msg_date` is a Unix timestamp in milliseconds.
- `messages.send_type = 1` is outgoing; every other value is incoming.
- Rows with `messages.deleted = 1` are excluded from chat summaries, conversations, and search.
- Conversation types `1` and `5` are treated as group/community conversations; type `6` is a self-chat.

### Known Android message types

| extra_mime | Meaning | Viewer behavior |
| ---: | --- | --- |
| 0 | Text | Display `body`. Unlike desktop `Messages.Type = 0`, this is not a reaction row. |
| 1 | Image | Display an image placeholder. |
| 3 | Video | Display a video placeholder. |
| 4 | Sticker | Display a sticker placeholder. |
| 5 | Location | Display a location placeholder. |
| 7 | Business rich message | Extract the first visible `Text` value from the JSON array in `body`. |
| 8 | Rich link | Display `body` or visible `Text`/`Title` and `URL` from `msg_info`. |
| 9 | Contact card | Display a contact placeholder. |
| 10 | File | Display a file placeholder. |
| 1005 | GIF | Display a GIF placeholder. |
| 1007 | Like/reaction helper event | Filter out from chat aggregation, conversations, and search. |
| 1008 | Deleted message | Display a localized “Deleted message” notice like desktop type `72`. |
| 1009 | Voice message | Display an audio placeholder. |
| 1010 | Instant video message | Display a video placeholder. |
| other | Unknown | Display the numeric `extra_mime` value in a generic placeholder. |

## Global message search

- Global search scans only messages that can produce visible text, formats them with the same rules as the conversation view, and compares text case-insensitively in Kotlin so non-ASCII scripts work correctly.
- Input is debounced by 250 milliseconds.
- Results are ordered newest-first and capped at 200 items.
- Selecting a result opens its chat with in-chat search active and scrolls to the selected event.

## Query invariants

- Desktop messages are ordered by `Events.TimeStamp`, then `SortOrder`, then `EventID`.
- Android messages are ordered by `messages.msg_date`, then `order_key`, then `_id`.
- Empty chats and chats containing only type 0 events are hidden.
- Media payload and thumbnail paths are never opened by the viewer.
- The imported database is copied into private app storage and opened read-only.
