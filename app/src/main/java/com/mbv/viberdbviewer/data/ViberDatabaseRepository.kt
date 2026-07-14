package com.mbv.viberdbviewer.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import com.mbv.viberdbviewer.DesktopClientFlag
import com.mbv.viberdbviewer.DesktopContact
import com.mbv.viberdbviewer.DesktopMessageType
import com.mbv.viberdbviewer.GLOBAL_SEARCH_RESULT_LIMIT
import com.mbv.viberdbviewer.MessageDirection
import com.mbv.viberdbviewer.R
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.ContactRecord
import com.mbv.viberdbviewer.model.GlobalSearchResult
import com.mbv.viberdbviewer.model.MessageLabels
import com.mbv.viberdbviewer.model.clean
import com.mbv.viberdbviewer.model.formatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class DatabaseImportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private enum class ViberDatabaseFormat {
    DESKTOP,
    ANDROID,
}

class ViberDatabaseRepository(
    context: Context,
) : Closeable {
    private val appContext = context.applicationContext
    private val databaseDir = File(appContext.filesDir, "viber_viewer")
    private val activeFile = File(databaseDir, "imported.db")
    private val candidateFile = File(databaseDir, "candidate.db")
    private val backupFile = File(databaseDir, "imported.backup.db")

    @Volatile
    private var database: SQLiteDatabase? = null

    @Volatile
    private var databaseFormat: ViberDatabaseFormat? = null

    private val messageLabels = MessageLabels.from(appContext)

    private val androidReader = AndroidViberDatabaseReader(appContext, messageLabels)

    fun userMessage(error: Exception): String =
        when (error) {
            is DatabaseImportException -> error.message ?: text(R.string.database_error_title)
            else -> text(R.string.error_unexpected, error.message ?: text(R.string.error_unknown_reason))
        }

    suspend fun openExisting(): Boolean =
        withContext(Dispatchers.IO) {
            if (!activeFile.isFile) return@withContext false
            val format = validateDatabase(activeFile)
            replaceOpenDatabase(openReadOnly(activeFile), format)
            true
        }

    suspend fun importDatabase(uri: Uri) =
        withContext(Dispatchers.IO) {
            databaseDir.mkdirs()
            candidateFile.delete()
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(candidateFile).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        output.fd.sync()
                    }
                } ?: throw DatabaseImportException(text(R.string.error_open_selected_file))

                val format = validateDatabase(candidateFile)
                installCandidate(format)
            } catch (error: IOException) {
                throw DatabaseImportException(
                    text(R.string.error_import_database, error.message ?: text(R.string.error_unknown)),
                    error,
                )
            } catch (error: SecurityException) {
                throw DatabaseImportException(
                    text(R.string.error_import_database, error.message ?: text(R.string.error_unknown)),
                    error,
                )
            } finally {
                candidateFile.delete()
            }
        }

    @Suppress("LongMethod")
    suspend fun loadChats(): List<ChatSummary> =
        withContext(Dispatchers.IO) {
            val db = requireDatabase()
            if (requireDatabaseFormat() == ViberDatabaseFormat.ANDROID) {
                return@withContext androidReader.loadChats(db)
            }
            val selfId = findSelfContactId(db)
            val contacts = loadContacts(db)
            val relations = loadRelations(db)
            val chats = mutableListOf<ChatSummary>()

            db
                .rawQuery(
                    """
                    SELECT ci.ChatID, ci.Name, COUNT(e.EventID), MAX(e.TimeStamp)
                    FROM ChatInfo ci
                    INNER JOIN Events e ON e.ChatID = ci.ChatID
                    INNER JOIN Messages m
                        ON m.EventID = e.EventID
                        AND m.Type <> ${DesktopMessageType.HEART_REACTION}
                        AND COALESCE(m.ClientFlag, ${DesktopClientFlag.NONE}) NOT IN (${DesktopClientFlag.EDIT_HISTORY}, ${DesktopClientFlag.EDIT_HISTORY_VARIANT})
                    GROUP BY ci.ChatID, ci.Name
                    ORDER BY MAX(e.TimeStamp) DESC, ci.ChatID DESC
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val chatId = cursor.getLong(0)
                        val chatName = cursor.stringOrNull(1).clean()
                        val participantIds = relations[chatId].orEmpty()
                        val nonSelfParticipants = participantIds.filter { it != selfId }
                        val isGroup = chatName != null || nonSelfParticipants.size > 1
                        val directId = nonSelfParticipants.firstOrNull() ?: participantIds.firstOrNull()
                        val directContact = directId?.let(contacts::get)
                        val title =
                            when {
                                chatName != null -> chatName
                                isGroup -> text(R.string.fallback_group_chat, chatId)
                                else ->
                                    text(R.string.fallback_chat, chatId)
                                        .let { fallback -> directContact?.displayName(fallback) ?: fallback }
                            }
                        val subtitle =
                            if (isGroup) {
                                appContext.resources.getQuantityString(
                                    R.plurals.participant_count,
                                    participantIds.size,
                                    participantIds.size,
                                )
                            } else {
                                directContact?.number.clean().orEmpty()
                            }
                        chats +=
                            ChatSummary(
                                chatId = chatId,
                                title = title,
                                subtitle = subtitle,
                                lastTimestamp = cursor.getLong(DESKTOP_CHAT_LAST_TIMESTAMP_COLUMN),
                                isGroup = isGroup,
                                searchNumber = if (isGroup) "" else directContact?.number.orEmpty(),
                            )
                    }
                }
            chats
        }

    suspend fun loadMessages(chatId: Long): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val db = requireDatabase()
            if (requireDatabaseFormat() == ViberDatabaseFormat.ANDROID) {
                return@withContext androidReader.loadMessages(db, chatId)
            }
            val messages = ArrayList<ChatMessage>()
            db
                .rawQuery(
                    """
                    SELECT e.EventID, e.TimeStamp, e.Direction, e.ContactID, m.Type, m.Body,
                           CASE WHEN m.Type IN (${DesktopMessageType.LINK}, ${DesktopMessageType.FILE}, ${DesktopMessageType.PINNED}) THEN m.Info ELSE NULL END,
                           c.Name, c.ClientName, c.Number
                    FROM Events e
                    INNER JOIN Messages m ON m.EventID = e.EventID
                    LEFT JOIN Contact c ON c.ContactID = e.ContactID
                    WHERE e.ChatID = ?
                      AND m.Type <> ${DesktopMessageType.HEART_REACTION}
                      AND COALESCE(m.ClientFlag, ${DesktopClientFlag.NONE}) NOT IN (${DesktopClientFlag.EDIT_HISTORY}, ${DesktopClientFlag.EDIT_HISTORY_VARIANT})
                    ORDER BY e.TimeStamp ASC, e.SortOrder ASC, e.EventID ASC
                    """.trimIndent(),
                    arrayOf(chatId.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val eventId = cursor.getLong(0)
                        val type = cursor.getInt(4)
                        val formatted =
                            formatMessage(
                                type,
                                cursor.stringOrNull(5),
                                cursor.stringOrNull(6),
                                messageLabels,
                            )
                        val sender =
                            ContactRecord(
                                contactId = cursor.longOrNull(3) ?: -1,
                                name = cursor.stringOrNull(7),
                                clientName = cursor.stringOrNull(8),
                                number = cursor.stringOrNull(9),
                            ).displayName(text(R.string.unknown_sender))
                        messages +=
                            ChatMessage(
                                eventId = eventId,
                                timestamp = cursor.getLong(1),
                                direction = cursor.getInt(2),
                                senderName = sender,
                                kind = formatted.kind,
                                displayText = formatted.displayText,
                            )
                    }
                }
            messages
        }

    suspend fun searchMessages(
        query: String,
        limit: Int = GLOBAL_SEARCH_RESULT_LIMIT,
    ): List<GlobalSearchResult> =
        withContext(Dispatchers.IO) {
            val needle = query.trim()
            if (needle.isEmpty()) return@withContext emptyList()

            val db = requireDatabase()
            val resultLimit = limit.coerceIn(1, 1000)
            if (requireDatabaseFormat() == ViberDatabaseFormat.ANDROID) {
                return@withContext androidReader.searchMessages(db, needle, resultLimit)
            }
            val scanAllText = requiresKotlinCaseFolding(needle).sqliteFlag()
            val deletedMatches = messageLabels.deleted.contains(needle, ignoreCase = true).sqliteFlag()
            val placeholderMatches =
                listOf(
                    messageLabels.image,
                    messageLabels.video,
                    messageLabels.sticker,
                    messageLabels.location,
                    messageLabels.contact,
                    messageLabels.audio,
                    messageLabels.gif,
                    messageLabels.file,
                ).any {
                    it.contains(needle, ignoreCase = true)
                }.sqliteFlag()
            val results =
                ArrayList<GlobalSearchResult>(minOf(resultLimit, GLOBAL_SEARCH_RESULT_LIMIT))
            db
                .rawQuery(
                    DESKTOP_SEARCH_QUERY,
                    arrayOf(scanAllText, sqliteLikePattern(needle), deletedMatches, placeholderMatches),
                ).use { cursor ->
                    while (cursor.moveToNext() && results.size < resultLimit) {
                        coroutineContext.ensureActive()
                        val formatted =
                            formatMessage(
                                cursor.getInt(4),
                                cursor.stringOrNull(5),
                                cursor.stringOrNull(6),
                                messageLabels,
                            )
                        if (!formatted.displayText.contains(needle, ignoreCase = true)) continue

                        val sender =
                            ContactRecord(
                                contactId = cursor.longOrNull(3) ?: -1,
                                name = cursor.stringOrNull(7),
                                clientName = cursor.stringOrNull(8),
                                number = cursor.stringOrNull(9),
                            ).displayName(text(R.string.unknown_sender))
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
            results
        }

    private fun installCandidate(format: ViberDatabaseFormat) {
        val previousFormat = databaseFormat
        closeDatabase()
        backupFile.delete()
        val hadActive = backupActiveDatabase()
        promoteCandidate(hadActive)
        try {
            replaceOpenDatabase(openReadOnly(activeFile), format)
            backupFile.delete()
        } catch (error: Exception) {
            restorePreviousDatabase(hadActive, previousFormat)
            throw DatabaseImportException(text(R.string.error_open_imported_database), error)
        }
    }

    private fun backupActiveDatabase(): Boolean {
        val hadActive = activeFile.isFile
        if (hadActive && !activeFile.renameTo(backupFile)) {
            throw DatabaseImportException(text(R.string.error_prepare_replacement))
        }
        return hadActive
    }

    private fun promoteCandidate(hadActive: Boolean) {
        if (!candidateFile.renameTo(activeFile)) {
            if (hadActive) backupFile.renameTo(activeFile)
            throw DatabaseImportException(text(R.string.error_save_database))
        }
    }

    private fun restorePreviousDatabase(
        hadActive: Boolean,
        previousFormat: ViberDatabaseFormat?,
    ) {
        closeDatabase()
        activeFile.delete()
        if (hadActive) backupFile.renameTo(activeFile)
        if (activeFile.isFile) {
            runCatching {
                val restoredFormat = previousFormat ?: validateDatabase(activeFile)
                replaceOpenDatabase(openReadOnly(activeFile), restoredFormat)
            }
        }
    }

    private fun validateDatabase(file: File): ViberDatabaseFormat {
        if (!hasSqliteHeader(file)) throw DatabaseImportException(text(R.string.error_not_sqlite))
        val db = openDatabaseForValidation(file)
        try {
            verifyDatabaseIntegrity(db)
            return detectDatabaseFormat(db)
        } catch (error: SQLiteException) {
            throw DatabaseImportException(text(R.string.error_check_schema), error)
        } finally {
            db.close()
        }
    }

    private fun openDatabaseForValidation(file: File): SQLiteDatabase =
        try {
            openReadOnly(file)
        } catch (error: SQLiteException) {
            throw DatabaseImportException(text(R.string.error_corrupt_or_encrypted), error)
        }

    private fun verifyDatabaseIntegrity(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA quick_check", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                throw DatabaseImportException(text(R.string.error_integrity_check))
            }
        }
    }

    private fun detectDatabaseFormat(db: SQLiteDatabase): ViberDatabaseFormat {
        REQUIRED_SCHEMAS.forEach { (format, schema) ->
            if (schema.all { (table, columns) -> hasRequiredSchema(db, table, columns) }) {
                return format
            }
        }
        throw DatabaseImportException(text(R.string.error_unsupported_database_format))
    }

    private fun hasRequiredSchema(
        db: SQLiteDatabase,
        table: String,
        requiredColumns: Set<String>,
    ): Boolean {
        val actualColumns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) actualColumns += cursor.getString(1).lowercase()
        }
        return actualColumns.isNotEmpty() && actualColumns.containsAll(requiredColumns)
    }

    private fun hasSqliteHeader(file: File): Boolean {
        val bytes = ByteArray(SQLITE_HEADER.size)
        return file.isFile &&
            file.length() >= SQLITE_HEADER.size &&
            FileInputStream(file).use { input -> input.read(bytes) == bytes.size } &&
            bytes.contentEquals(SQLITE_HEADER)
    }

    private fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also {
            it.execSQL("PRAGMA query_only = ON")
        }

    private fun findSelfContactId(db: SQLiteDatabase): Long? {
        db
            .rawQuery(
                """
                SELECT ContactID
                FROM Events
                WHERE Direction = ${MessageDirection.OUTGOING} AND ContactID IS NOT NULL
                GROUP BY ContactID
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        db
            .rawQuery(
                "SELECT ContactID FROM Contact WHERE ContactID = ${DesktopContact.DEFAULT_SELF_ID}",
                null,
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
    }

    private fun loadContacts(db: SQLiteDatabase): Map<Long, ContactRecord> {
        val contacts = mutableMapOf<Long, ContactRecord>()
        db.rawQuery("SELECT ContactID, Name, ClientName, Number FROM Contact", null).use { cursor ->
            while (cursor.moveToNext()) {
                val contact =
                    ContactRecord(
                        contactId = cursor.getLong(0),
                        name = cursor.stringOrNull(1),
                        clientName = cursor.stringOrNull(2),
                        number = cursor.stringOrNull(3),
                    )
                contacts[contact.contactId] = contact
            }
        }
        return contacts
    }

    private fun loadRelations(db: SQLiteDatabase): Map<Long, List<Long>> {
        val relations = mutableMapOf<Long, MutableList<Long>>()
        db.rawQuery("SELECT ChatID, ContactID FROM ChatRelation", null).use { cursor ->
            while (cursor.moveToNext()) {
                relations.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getLong(1)
            }
        }
        return relations
    }

    private fun text(
        id: Int,
        vararg formatArgs: Any,
    ): String = appContext.getString(id, *formatArgs)

    private fun requireDatabase(): SQLiteDatabase =
        database?.takeIf { it.isOpen }
            ?: throw DatabaseImportException(text(R.string.error_database_not_open))

    private fun requireDatabaseFormat(): ViberDatabaseFormat =
        databaseFormat ?: throw DatabaseImportException(text(R.string.error_database_not_open))

    @Synchronized
    private fun replaceOpenDatabase(
        newDatabase: SQLiteDatabase,
        newFormat: ViberDatabaseFormat,
    ) {
        closeDatabase()
        database = newDatabase
        databaseFormat = newFormat
    }

    @Synchronized
    private fun closeDatabase() {
        database?.close()
        database = null
        databaseFormat = null
    }

    override fun close() = closeDatabase()

    companion object {
        private const val DESKTOP_CHAT_LAST_TIMESTAMP_COLUMN = 3

        private val DESKTOP_SEARCH_QUERY =
            """
            SELECT e.EventID, e.ChatID, e.TimeStamp, e.ContactID, m.Type, m.Body,
                   CASE WHEN m.Type IN (${DesktopMessageType.LINK}, ${DesktopMessageType.PINNED}) THEN m.Info ELSE NULL END,
                   c.Name, c.ClientName, c.Number
            FROM Events e
            INNER JOIN Messages m ON m.EventID = e.EventID
            LEFT JOIN Contact c ON c.ContactID = e.ContactID
            WHERE m.Type <> ${DesktopMessageType.HEART_REACTION}
              AND COALESCE(m.ClientFlag, ${DesktopClientFlag.NONE}) NOT IN (${DesktopClientFlag.EDIT_HISTORY}, ${DesktopClientFlag.EDIT_HISTORY_VARIANT})
              AND (TRIM(COALESCE(m.Body, '')) <> '' OR m.Type IN (${DesktopMessageType.LINK}, ${DesktopMessageType.PINNED}, ${DesktopMessageType.DELETED}))
              AND (
                  CAST(? AS INTEGER) = 1
                  OR (m.Type IN (${DesktopMessageType.TEXT}, ${DesktopMessageType.BUSINESS})
                      AND LOWER(COALESCE(m.Body, '')) LIKE LOWER(?) ESCAPE '\')
                  OR m.Type IN (${DesktopMessageType.LINK}, ${DesktopMessageType.PINNED})
                  OR (CAST(? AS INTEGER) = 1 AND m.Type = ${DesktopMessageType.DELETED})
                  OR (
                      CAST(? AS INTEGER) = 1
                      AND m.Type IN (
                          ${DesktopMessageType.IMAGE},
                          ${DesktopMessageType.VIDEO},
                          ${DesktopMessageType.STICKER},
                          ${DesktopMessageType.LOCATION},
                          ${DesktopMessageType.CONTACT},
                          ${DesktopMessageType.FILE}
                      )
                  )
                  OR m.Type NOT IN (
                      ${DesktopMessageType.HEART_REACTION},
                      ${DesktopMessageType.TEXT},
                      ${DesktopMessageType.IMAGE},
                      ${DesktopMessageType.VIDEO},
                      ${DesktopMessageType.STICKER},
                      ${DesktopMessageType.LOCATION},
                      ${DesktopMessageType.BUSINESS},
                      ${DesktopMessageType.LINK},
                      ${DesktopMessageType.CONTACT},
                      ${DesktopMessageType.FILE},
                      ${DesktopMessageType.PINNED},
                      ${DesktopMessageType.DELETED}
                  )
              )
            ORDER BY e.TimeStamp DESC, e.SortOrder DESC, e.EventID DESC
            """.trimIndent()

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        private val DESKTOP_REQUIRED_SCHEMA =
            mapOf(
                "ChatInfo" to setOf("chatid", "name"),
                "ChatRelation" to setOf("chatid", "contactid"),
                "Contact" to setOf("contactid", "name", "clientname", "number"),
                "Events" to setOf("eventid", "timestamp", "direction", "chatid", "contactid", "sortorder"),
                "Messages" to setOf("eventid", "type", "body", "info", "clientflag"),
            )

        private val ANDROID_REQUIRED_SCHEMA =
            mapOf(
                "conversations" to setOf("_id", "conversation_type", "name", "participant_id_1"),
                "participants" to setOf("_id", "conversation_id", "participant_info_id", "active"),
                "participants_info" to
                    setOf(
                        "_id",
                        "number",
                        "contact_name",
                        "display_name",
                        "viber_name",
                    ),
                "messages" to
                    setOf(
                        "_id",
                        "conversation_id",
                        "participant_id",
                        "msg_date",
                        "send_type",
                        "body",
                        "order_key",
                        "deleted",
                        "extra_mime",
                        "msg_info",
                    ),
            )

        private val REQUIRED_SCHEMAS =
            linkedMapOf(
                ViberDatabaseFormat.DESKTOP to DESKTOP_REQUIRED_SCHEMA,
                ViberDatabaseFormat.ANDROID to ANDROID_REQUIRED_SCHEMA,
            )
    }
}
