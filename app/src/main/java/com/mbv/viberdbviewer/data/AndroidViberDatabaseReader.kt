package com.mbv.viberdbviewer.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.mbv.viberdbviewer.AndroidConversationType
import com.mbv.viberdbviewer.AndroidMessageState
import com.mbv.viberdbviewer.AndroidMessageType
import com.mbv.viberdbviewer.AndroidParticipantState
import com.mbv.viberdbviewer.GLOBAL_SEARCH_RESULT_LIMIT
import com.mbv.viberdbviewer.MessageDirection
import com.mbv.viberdbviewer.R
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.ContactRecord
import com.mbv.viberdbviewer.model.GlobalSearchResult
import com.mbv.viberdbviewer.model.MessageLabels
import com.mbv.viberdbviewer.model.clean
import com.mbv.viberdbviewer.model.formatAndroidMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AndroidViberDatabaseReader(
    private val context: Context,
    private val labels: MessageLabels,
) {
    fun loadChats(db: SQLiteDatabase): List<ChatSummary> {
        val self = loadSelfContact(db)
        val chats = mutableListOf<ChatSummary>()
        db.rawQuery(CHATS_QUERY, null).use { cursor ->
            while (cursor.moveToNext()) {
                chats += cursor.toChatSummary(self)
            }
        }
        return chats
    }

    fun loadMessages(
        db: SQLiteDatabase,
        chatId: Long,
    ): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>()
        db
            .rawQuery(
                """
                SELECT m._id, m.msg_date, m.send_type, m.extra_mime, m.body,
                       CASE WHEN m.extra_mime = ${AndroidMessageType.LINK} THEN m.msg_info ELSE NULL END,
                       pi._id, pi.contact_name, pi.display_name, pi.number, pi.viber_name
                FROM messages m
                LEFT JOIN participants p ON p._id = m.participant_id
                LEFT JOIN participants_info pi ON pi._id = p.participant_info_id
                WHERE m.conversation_id = ?
                  AND m.deleted = ${AndroidMessageState.VISIBLE}
                  AND m.extra_mime <> ${AndroidMessageType.REACTION}
                ORDER BY m.msg_date ASC, m.order_key ASC, m._id ASC
                """.trimIndent(),
                arrayOf(chatId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val formatted =
                        formatAndroidMessage(
                            cursor.getInt(3),
                            cursor.stringOrNull(4),
                            cursor.stringOrNull(5),
                            labels,
                        )
                    val sender =
                        cursor.contact(6)?.displayName(text(R.string.unknown_sender))
                            ?: text(R.string.unknown_sender)
                    messages +=
                        ChatMessage(
                            eventId = cursor.getLong(0),
                            timestamp = cursor.getLong(1),
                            direction = cursor.getInt(2),
                            senderName = sender,
                            kind = formatted.kind,
                            displayText = formatted.displayText,
                        )
                }
            }
        return messages
    }

    suspend fun searchMessages(
        db: SQLiteDatabase,
        query: String,
        limit: Int,
    ): List<GlobalSearchResult> {
        val results = ArrayList<GlobalSearchResult>(minOf(limit, GLOBAL_SEARCH_RESULT_LIMIT))
        val likePattern = sqliteLikePattern(query)
        val scanAllText = requiresKotlinCaseFolding(query).sqliteFlag()
        val deletedMatches = labels.deleted.contains(query, ignoreCase = true).sqliteFlag()
        db
            .rawQuery(
                SEARCH_QUERY,
                arrayOf(scanAllText, likePattern, deletedMatches),
            ).use { cursor ->
                while (cursor.moveToNext() && results.size < limit) {
                    currentCoroutineContext().ensureActive()
                    val formatted =
                        formatAndroidMessage(
                            cursor.getInt(3),
                            cursor.stringOrNull(4),
                            cursor.stringOrNull(5),
                            labels,
                        )
                    if (!formatted.displayText.contains(query, ignoreCase = true)) continue

                    val sender =
                        cursor.contact(6)?.displayName(text(R.string.unknown_sender))
                            ?: text(R.string.unknown_sender)
                    results +=
                        GlobalSearchResult(
                            chatId = cursor.getLong(1),
                            eventId = cursor.getLong(0),
                            timestamp = cursor.getLong(2),
                            senderName = sender,
                            kind = formatted.kind,
                            displayText = formatted.displayText,
                        )
                }
            }
        return results
    }

    private fun Cursor.toChatSummary(self: ContactRecord?): ChatSummary {
        val chatId = getLong(0)
        val chatName = stringOrNull(1).clean()
        val conversationType = getInt(2)
        val direct =
            if (conversationType == AndroidConversationType.SELF) {
                self
            } else {
                contact(4)
            }
        val isGroup =
            conversationType in AndroidConversationType.GROUP_TYPES ||
                (chatName != null && conversationType != AndroidConversationType.SELF)
        val participantCount =
            participantCount(
                isGroup = isGroup,
                activeParticipants = getInt(9),
                direct = direct,
                self = self,
            )
        val fallback =
            text(
                if (isGroup) R.string.fallback_group_chat else R.string.fallback_chat,
                chatId,
            )
        val subtitle =
            if (isGroup) {
                context.resources.getQuantityString(
                    R.plurals.participant_count,
                    participantCount,
                    participantCount,
                )
            } else {
                direct?.number.clean().orEmpty()
            }
        return ChatSummary(
            chatId = chatId,
            title = chatName ?: direct?.displayName(fallback) ?: fallback,
            subtitle = subtitle,
            lastTimestamp = getLong(CHAT_LAST_TIMESTAMP_COLUMN),
            isGroup = isGroup,
            searchNumber = if (isGroup) "" else direct?.number.orEmpty(),
        )
    }

    private fun participantCount(
        isGroup: Boolean,
        activeParticipants: Int,
        direct: ContactRecord?,
        self: ContactRecord?,
    ): Int =
        when {
            isGroup -> activeParticipants + if (self != null) 1 else 0
            direct != null && self != null && direct.contactId != self.contactId -> 2
            else -> 1
        }

    private fun loadSelfContact(db: SQLiteDatabase): ContactRecord? {
        db
            .rawQuery(
                """
                SELECT pi._id, pi.contact_name, pi.display_name, pi.number, pi.viber_name
                FROM messages m
                INNER JOIN participants p ON p._id = m.participant_id
                INNER JOIN participants_info pi ON pi._id = p.participant_info_id
                WHERE m.send_type = ${MessageDirection.OUTGOING}
                GROUP BY pi._id, pi.contact_name, pi.display_name, pi.number, pi.viber_name
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.contact(0) else null
            }
    }

    private fun Cursor.contact(start: Int): ContactRecord? {
        if (isNull(start)) return null
        return ContactRecord(
            contactId = getLong(start),
            name = stringOrNull(start + 1),
            clientName = stringOrNull(start + 2),
            number = stringOrNull(start + CONTACT_NUMBER_OFFSET),
            viberName = stringOrNull(start + CONTACT_VIBER_NAME_OFFSET),
        )
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    private companion object {
        private val SEARCH_QUERY =
            """
            SELECT m._id, m.conversation_id, m.msg_date, m.extra_mime, m.body,
                   CASE WHEN m.extra_mime = ${AndroidMessageType.LINK} THEN m.msg_info ELSE NULL END,
                   pi._id, pi.contact_name, pi.display_name, pi.number, pi.viber_name
            FROM messages m
            LEFT JOIN participants p ON p._id = m.participant_id
            LEFT JOIN participants_info pi ON pi._id = p.participant_info_id
            WHERE m.deleted = ${AndroidMessageState.VISIBLE}
              AND m.extra_mime IN (
                  ${AndroidMessageType.TEXT},
                  ${AndroidMessageType.BUSINESS},
                  ${AndroidMessageType.LINK},
                  ${AndroidMessageType.DELETED}
              )
              AND (
                  TRIM(COALESCE(m.body, '')) <> ''
                  OR m.extra_mime IN (${AndroidMessageType.LINK}, ${AndroidMessageType.DELETED})
              )
              AND (
                  CAST(? AS INTEGER) = 1
                  OR (m.extra_mime = ${AndroidMessageType.TEXT}
                      AND LOWER(COALESCE(m.body, '')) LIKE LOWER(?) ESCAPE '\')
                  OR m.extra_mime IN (${AndroidMessageType.BUSINESS}, ${AndroidMessageType.LINK})
                  OR (CAST(? AS INTEGER) = 1 AND m.extra_mime = ${AndroidMessageType.DELETED})
              )
            ORDER BY m.msg_date DESC, m.order_key DESC, m._id DESC
            """.trimIndent()

        private val CHATS_QUERY =
            """
            SELECT c._id, c.name, c.conversation_type, MAX(m.msg_date),
                   pi._id, pi.contact_name, pi.display_name, pi.number, pi.viber_name,
                   (SELECT COUNT(*) FROM participants p
                    WHERE p.conversation_id = c._id AND p.active = ${AndroidParticipantState.ACTIVE})
            FROM conversations c
            INNER JOIN messages m ON m.conversation_id = c._id
                AND m.deleted = ${AndroidMessageState.VISIBLE}
                AND m.extra_mime <> ${AndroidMessageType.REACTION}
            LEFT JOIN participants_info pi ON pi._id = c.participant_id_1
            GROUP BY c._id, c.name, c.conversation_type, c.participant_id_1
            ORDER BY MAX(m.msg_date) DESC, c._id DESC
            """.trimIndent()

        private const val CHAT_LAST_TIMESTAMP_COLUMN = 3
        private const val CONTACT_NUMBER_OFFSET = 3
        private const val CONTACT_VIBER_NAME_OFFSET = 4
    }
}
