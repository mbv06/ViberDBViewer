package com.mbv.viberdbviewer.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.mbv.viberdbviewer.R
import com.mbv.viberdbviewer.model.ChatMessage
import com.mbv.viberdbviewer.model.ChatSummary
import com.mbv.viberdbviewer.model.ContactRecord
import com.mbv.viberdbviewer.model.MessageLabels
import com.mbv.viberdbviewer.model.clean
import com.mbv.viberdbviewer.model.formatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DatabaseImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ViberDatabaseRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val databaseDir = File(appContext.filesDir, "viber_viewer")
    private val activeFile = File(databaseDir, "imported.db")
    private val candidateFile = File(databaseDir, "candidate.db")
    private val backupFile = File(databaseDir, "imported.backup.db")

    @Volatile
    private var database: SQLiteDatabase? = null

    private val messageLabels = MessageLabels(
        empty = text(R.string.message_empty),
        image = text(R.string.message_image),
        video = text(R.string.message_video),
        sticker = text(R.string.message_sticker),
        location = text(R.string.message_location),
        contact = text(R.string.message_contact),
        pinned = { text(R.string.message_pinned, it) },
        pinnedEmpty = text(R.string.message_pinned_empty),
        unknownType = { text(R.string.message_unknown_type, it) },
        link = text(R.string.message_link),
        audio = text(R.string.message_audio),
        gif = text(R.string.message_gif),
        file = text(R.string.message_file),
    )

    fun userMessage(error: Exception): String = when (error) {
        is DatabaseImportException -> error.message ?: text(R.string.database_error_title)
        else -> text(R.string.error_unexpected, error.message ?: text(R.string.error_unknown_reason))
    }

    suspend fun openExisting(): Boolean = withContext(Dispatchers.IO) {
        if (!activeFile.isFile) return@withContext false
        validateDatabase(activeFile).close()
        replaceOpenDatabase(openReadOnly(activeFile))
        true
    }

    suspend fun importDatabase(uri: Uri) = withContext(Dispatchers.IO) {
        databaseDir.mkdirs()
        candidateFile.delete()
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(candidateFile).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            } ?: throw DatabaseImportException(text(R.string.error_open_selected_file))

            validateDatabase(candidateFile).close()
            installCandidate()
        } catch (error: DatabaseImportException) {
            candidateFile.delete()
            throw error
        } catch (error: Exception) {
            candidateFile.delete()
            throw DatabaseImportException(
                text(R.string.error_import_database, error.message ?: text(R.string.error_unknown)),
                error,
            )
        }
    }

    suspend fun loadChats(): List<ChatSummary> = withContext(Dispatchers.IO) {
        val db = requireDatabase()
        val selfId = findSelfContactId(db)
        val contacts = loadContacts(db)
        val relations = loadRelations(db)
        val chats = mutableListOf<ChatSummary>()

        db.rawQuery(
            """
            SELECT ci.ChatID, ci.Name, COUNT(e.EventID), MAX(e.TimeStamp)
            FROM ChatInfo ci
            INNER JOIN Events e ON e.ChatID = ci.ChatID
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
                val title = when {
                    chatName != null -> chatName
                    isGroup -> text(R.string.fallback_group_chat, chatId)
                    else -> text(R.string.fallback_chat, chatId)
                        .let { fallback -> directContact?.displayName(fallback) ?: fallback }
                }
                val subtitle = if (isGroup) {
                    appContext.resources.getQuantityString(
                        R.plurals.participant_count,
                        participantIds.size,
                        participantIds.size,
                    )
                } else {
                    directContact?.number.clean().orEmpty()
                }
                chats += ChatSummary(
                    chatId = chatId,
                    title = title,
                    subtitle = subtitle,
                    lastTimestamp = cursor.getLong(3),
                    participantCount = participantIds.size,
                    isGroup = isGroup,
                    searchNumber = if (isGroup) "" else directContact?.number.orEmpty(),
                )
            }
        }
        chats
    }

    suspend fun loadMessages(chatId: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        val db = requireDatabase()
        val messages = ArrayList<ChatMessage>()
        db.rawQuery(
            """
            SELECT e.EventID, e.TimeStamp, e.Direction, e.ContactID, m.Type, m.Body,
                   CASE WHEN m.Type IN (9, 11, 15) THEN m.Info ELSE NULL END,
                   c.Name, c.ClientName, c.Number
            FROM Events e
            INNER JOIN Messages m ON m.EventID = e.EventID
            LEFT JOIN Contact c ON c.ContactID = e.ContactID
            WHERE e.ChatID = ?
            ORDER BY e.TimeStamp ASC, e.SortOrder ASC, e.EventID ASC
            """.trimIndent(),
            arrayOf(chatId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val type = cursor.getInt(4)
                val formatted = formatMessage(
                    type,
                    cursor.stringOrNull(5),
                    cursor.stringOrNull(6),
                    messageLabels,
                )
                val sender = ContactRecord(
                    contactId = cursor.longOrNull(3) ?: -1,
                    name = cursor.stringOrNull(7),
                    clientName = cursor.stringOrNull(8),
                    number = cursor.stringOrNull(9),
                ).displayName(text(R.string.unknown_sender))
                messages += ChatMessage(
                    eventId = eventId,
                    timestamp = cursor.getLong(1),
                    direction = cursor.getInt(2),
                    senderName = sender,
                    kind = formatted.kind,
                    displayText = formatted.displayText,
                    searchableText = formatted.searchableText,
                )
            }
        }
        messages
    }

    private fun installCandidate() {
        closeDatabase()
        backupFile.delete()
        val hadActive = activeFile.isFile
        if (hadActive && !activeFile.renameTo(backupFile)) {
            throw DatabaseImportException(text(R.string.error_prepare_replacement))
        }
        if (!candidateFile.renameTo(activeFile)) {
            if (hadActive) backupFile.renameTo(activeFile)
            throw DatabaseImportException(text(R.string.error_save_database))
        }
        try {
            replaceOpenDatabase(openReadOnly(activeFile))
            backupFile.delete()
        } catch (error: Exception) {
            closeDatabase()
            activeFile.delete()
            if (hadActive) backupFile.renameTo(activeFile)
            if (activeFile.isFile) runCatching { replaceOpenDatabase(openReadOnly(activeFile)) }
            throw DatabaseImportException(text(R.string.error_open_imported_database), error)
        }
    }

    private fun validateDatabase(file: File): SQLiteDatabase {
        if (!hasSqliteHeader(file)) throw DatabaseImportException(text(R.string.error_not_sqlite))
        val db = try {
            openReadOnly(file)
        } catch (error: Exception) {
            throw DatabaseImportException(text(R.string.error_corrupt_or_encrypted), error)
        }
        try {
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw DatabaseImportException(text(R.string.error_integrity_check))
                }
            }
            REQUIRED_SCHEMA.forEach { (table, columns) ->
                val actual = mutableSetOf<String>()
                db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    while (cursor.moveToNext()) actual += cursor.getString(1)
                }
                val missing = columns - actual
                if (actual.isEmpty() || missing.isNotEmpty()) {
                    val missingColumns = if (missing.isEmpty()) {
                        ""
                    } else {
                        " (${missing.joinToString()})"
                    }
                    throw DatabaseImportException(text(R.string.error_unsupported_schema, table, missingColumns))
                }
            }
            return db
        } catch (error: Exception) {
            db.close()
            if (error is DatabaseImportException) throw error
            throw DatabaseImportException(text(R.string.error_check_schema), error)
        }
    }

    private fun hasSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        val bytes = ByteArray(SQLITE_HEADER.size)
        FileInputStream(file).use { input ->
            if (input.read(bytes) != bytes.size) return false
        }
        return bytes.contentEquals(SQLITE_HEADER)
    }

    private fun openReadOnly(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).also {
            it.execSQL("PRAGMA query_only = ON")
        }

    private fun findSelfContactId(db: SQLiteDatabase): Long? {
        db.rawQuery(
            """
            SELECT ContactID
            FROM Events
            WHERE Direction = 1 AND ContactID IS NOT NULL
            GROUP BY ContactID
            ORDER BY COUNT(*) DESC
            LIMIT 1
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        db.rawQuery("SELECT ContactID FROM Contact WHERE ContactID = 1", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun loadContacts(db: SQLiteDatabase): Map<Long, ContactRecord> {
        val contacts = mutableMapOf<Long, ContactRecord>()
        db.rawQuery("SELECT ContactID, Name, ClientName, Number FROM Contact", null).use { cursor ->
            while (cursor.moveToNext()) {
                val contact = ContactRecord(
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

    private fun text(id: Int, vararg formatArgs: Any): String = appContext.getString(id, *formatArgs)

    private fun requireDatabase(): SQLiteDatabase =
        database?.takeIf { it.isOpen }
            ?: throw DatabaseImportException(text(R.string.error_database_not_open))

    @Synchronized
    private fun replaceOpenDatabase(newDatabase: SQLiteDatabase) {
        closeDatabase()
        database = newDatabase
    }

    @Synchronized
    private fun closeDatabase() {
        database?.close()
        database = null
    }

    override fun close() = closeDatabase()

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

    companion object {
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        private val REQUIRED_SCHEMA = mapOf(
            "ChatInfo" to setOf("ChatID", "Name"),
            "ChatRelation" to setOf("ChatID", "ContactID"),
            "Contact" to setOf("ContactID", "Name", "ClientName", "Number"),
            "Events" to setOf("EventID", "TimeStamp", "Direction", "ChatID", "ContactID", "SortOrder"),
            "Messages" to setOf("EventID", "Type", "Body", "Info"),
        )
    }
}
