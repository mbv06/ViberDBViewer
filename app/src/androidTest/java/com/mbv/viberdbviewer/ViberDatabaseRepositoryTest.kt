package com.mbv.viberdbviewer

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mbv.viberdbviewer.data.DatabaseImportException
import com.mbv.viberdbviewer.data.ViberDatabaseRepository
import com.mbv.viberdbviewer.model.MessageKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ViberDatabaseRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: ViberDatabaseRepository
    private lateinit var source: File

    @Before
    fun setUp() {
        File(context.filesDir, "viber_viewer").deleteRecursively()
        source = File(context.cacheDir, "fixture-${System.nanoTime()}.db")
        createFixture(source)
        repository = ViberDatabaseRepository(context)
    }

    @After
    fun tearDown() {
        repository.close()
        source.delete()
        File(context.filesDir, "viber_viewer").deleteRecursively()
    }

    @Test
    fun importQueriesChatsAndMessages() =
        runBlocking {
            repository.importDatabase(Uri.fromFile(source))

            val chats = repository.loadChats()
            assertEquals(2, chats.size)
            assertEquals("Team", chats[0].title)
            assertEquals(context.resources.getQuantityString(R.plurals.participant_count, 3, 3), chats[0].subtitle)
            assertEquals("Alice", chats[1].title)
            assertEquals("+380 67 123", chats[1].subtitle)

            val messages = repository.loadMessages(chats[1].chatId)
            assertEquals(listOf(100L, 110L, 114L, 103L, 104L, 113L, 101L), messages.map { it.eventId })
            assertEquals(MessageKind.IMAGE, messages[0].kind)
            assertEquals("Final edited text", messages[1].displayText)
            assertEquals(1200L, messages[1].timestamp)
            assertEquals("Orphan edit kept", messages[2].displayText)
            assertEquals("Business notice", messages[3].displayText)
            assertEquals(MessageKind.DELETED, messages[4].kind)
            assertEquals(context.getString(R.string.message_deleted), messages[4].displayText)
            assertEquals(MessageKind.DELETED, messages[5].kind)
            assertEquals(context.getString(R.string.message_deleted), messages[5].displayText)
            assertEquals(MessageKind.TEXT, messages[6].kind)
        }

    @Test
    fun globalSearchFindsBusinessAndDeletedMessagesCaseInsensitively() =
        runBlocking {
            repository.importDatabase(Uri.fromFile(source))

            val business = repository.searchMessages("BUSINESS")
            assertEquals(listOf(103L), business.map { it.eventId })
            assertEquals("Business notice", business.single().displayText)

            val deleted = repository.searchMessages(context.getString(R.string.message_deleted).uppercase())
            assertEquals(listOf(113L, 104L), deleted.map { it.eventId })
            assertEquals(listOf(MessageKind.DELETED, MessageKind.DELETED), deleted.map { it.kind })

            assertEquals(emptyList<Long>(), repository.searchMessages("First edit").map { it.eventId })
            assertEquals(listOf(110L), repository.searchMessages("FINAL EDITED").map { it.eventId })
            assertEquals(listOf(114L), repository.searchMessages("ORPHAN EDIT").map { it.eventId })
            assertEquals(listOf(201L), repository.searchMessages("знижка").map { it.eventId })
            assertEquals(listOf(201L), repository.searchMessages("%_").map { it.eventId })
        }

    @Test
    fun androidPhoneDatabaseIsAutoDetectedAndMappedToTheSameModels() =
        runBlocking {
            val phoneSource = File(context.cacheDir, "phone-fixture-${System.nanoTime()}.db")
            createAndroidFixture(phoneSource)
            try {
                repository.importDatabase(Uri.fromFile(phoneSource))

                val chats = repository.loadChats()
                assertEquals(listOf("Team", "Me", "Alice"), chats.map { it.title })
                assertEquals(
                    context.resources.getQuantityString(R.plurals.participant_count, 3, 3),
                    chats.first().subtitle,
                )
                assertEquals("+380 67 123", chats.last().subtitle)

                val directMessages = repository.loadMessages(10)
                assertEquals(listOf(100L, 103L, 104L, 105L, 107L, 101L), directMessages.map { it.eventId })
                assertEquals(MessageKind.IMAGE, directMessages[0].kind)
                assertEquals("Preview\nhttps://example.com", directMessages[1].displayText)
                assertEquals(MessageKind.AUDIO, directMessages[2].kind)
                assertEquals("Business notice", directMessages[3].displayText)
                assertEquals(MessageKind.DELETED, directMessages[4].kind)
                assertEquals(MessageKind.TEXT, directMessages[5].kind)

                val results = repository.searchMessages("PREVIEW")
                assertEquals(listOf(103L), results.map { it.eventId })
                assertEquals(listOf(105L), repository.searchMessages("BUSINESS").map { it.eventId })
                assertEquals(emptyList<Long>(), repository.searchMessages("Liked message copy").map { it.eventId })
                assertEquals(
                    listOf(107L),
                    repository.searchMessages(context.getString(R.string.message_deleted)).map { it.eventId },
                )
                assertEquals(listOf(200L), repository.searchMessages("групове").map { it.eventId })
                assertEquals(listOf(200L), repository.searchMessages("%_").map { it.eventId })
            } finally {
                phoneSource.delete()
            }
        }

    @Test
    fun failedReplacementKeepsPreviouslyImportedDatabase() =
        runBlocking {
            repository.importDatabase(Uri.fromFile(source))
            val invalid = File(context.cacheDir, "invalid-${System.nanoTime()}.db").apply { writeText("not sqlite") }
            try {
                assertThrows(DatabaseImportException::class.java) {
                    runBlocking { repository.importDatabase(Uri.fromFile(invalid)) }
                }
                assertEquals(2, repository.loadChats().size)
            } finally {
                invalid.delete()
            }
        }

    private fun createFixture(file: File) {
        file.writeSqlite(
            "CREATE TABLE Contact(ContactID INTEGER PRIMARY KEY, Name TEXT, ClientName TEXT, Number TEXT)",
            "CREATE TABLE ChatInfo(ChatID INTEGER PRIMARY KEY, Name TEXT)",
            "CREATE TABLE ChatRelation(ChatID INTEGER, ContactID INTEGER)",
            "CREATE TABLE Events(EventID INTEGER PRIMARY KEY, TimeStamp INTEGER NOT NULL, Direction INTEGER NOT NULL, ChatID INTEGER, ContactID INTEGER, SortOrder INTEGER NOT NULL)",
            "CREATE TABLE Messages(EventID INTEGER PRIMARY KEY, Type INTEGER NOT NULL, Body TEXT, Info TEXT, ClientFlag INTEGER)",
            "INSERT INTO Contact VALUES(1, NULL, 'Me', '+380000')",
            "INSERT INTO Contact VALUES(2, '', 'Alice', '+380 67 123')",
            "INSERT INTO Contact VALUES(3, 'Bob', NULL, '+380999')",
            "INSERT INTO ChatInfo VALUES(10, NULL)",
            "INSERT INTO ChatInfo VALUES(20, 'Team')",
            "INSERT INTO ChatInfo VALUES(30, 'Empty')",
            "INSERT INTO ChatRelation VALUES(10, 1)",
            "INSERT INTO ChatRelation VALUES(10, 2)",
            "INSERT INTO ChatRelation VALUES(20, 1)",
            "INSERT INTO ChatRelation VALUES(20, 2)",
            "INSERT INTO ChatRelation VALUES(20, 3)",
            "INSERT INTO Events VALUES(100, 1000, ${MessageDirection.INCOMING}, 10, 2, 100)",
            "INSERT INTO Events VALUES(101, 2000, ${MessageDirection.OUTGOING}, 10, 1, 101)",
            "INSERT INTO Events VALUES(102, 5000, ${MessageDirection.INCOMING}, 10, 2, 102)",
            "INSERT INTO Events VALUES(103, 1500, ${MessageDirection.INCOMING}, 10, 2, 103)",
            "INSERT INTO Events VALUES(104, 1600, ${MessageDirection.INCOMING}, 10, 2, 104)",
            "INSERT INTO Events VALUES(110, 1200, ${MessageDirection.OUTGOING}, 10, 1, 110)",
            "INSERT INTO Events VALUES(111, 1300, ${MessageDirection.OUTGOING}, 10, 1, 111)",
            "INSERT INTO Events VALUES(112, 6000, ${MessageDirection.OUTGOING}, 10, 1, 112)",
            "INSERT INTO Events VALUES(113, 1700, ${MessageDirection.OUTGOING}, 10, 1, 113)",
            "INSERT INTO Events VALUES(114, 1400, ${MessageDirection.INCOMING}, 10, 2, 114)",
            "INSERT INTO Events VALUES(200, 3000, ${MessageDirection.INCOMING}, 20, 3, 200)",
            "INSERT INTO Events VALUES(201, 2500, ${MessageDirection.INCOMING}, 20, 3, 201)",
            "INSERT INTO Messages VALUES(100, ${DesktopMessageType.IMAGE}, NULL, '{}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(101, ${DesktopMessageType.TEXT}, 'Hello', '{}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(102, ${DesktopMessageType.HEART_REACTION}, NULL, '{}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(103, ${DesktopMessageType.BUSINESS}, 'Business notice', '{}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(104, ${DesktopMessageType.DELETED}, NULL, '{}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(110, ${DesktopMessageType.TEXT}, 'Final edited text', '{\"desktop_info\":{\"edit_token\":10002}}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(111, ${DesktopMessageType.TEXT}, 'First edit', '{\"edit\":{\"token\":10000}}', ${DesktopClientFlag.EDIT_HISTORY})",
            "INSERT INTO Messages VALUES(112, ${DesktopMessageType.TEXT}, 'Final edited text', '{\"rich_media\":{},\"edit\":{\"isSilent\":false,\"token\":10000}}', ${DesktopClientFlag.EDIT_HISTORY_VARIANT})",
            "INSERT INTO Messages VALUES(113, ${DesktopMessageType.DELETED}, NULL, '{\"edit\":{\"token\":104}}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(114, ${DesktopMessageType.TEXT}, 'Orphan edit kept', '{\"desktop_info\":{},\"edit\":{\"token\":99999}}', ${DesktopClientFlag.ORPHANED_EDIT})",
            "INSERT INTO Messages VALUES(200, ${DesktopMessageType.LINK}, 'Website', '{\"URL\":\"https://example.com\"}', ${DesktopClientFlag.NONE})",
            "INSERT INTO Messages VALUES(201, ${DesktopMessageType.TEXT}, 'ЗНИЖКА 100%_DONE', '{}', ${DesktopClientFlag.NONE})",
        )
    }

    private fun createAndroidFixture(file: File) {
        file.writeSqlite(
            "CREATE TABLE conversations(_id INTEGER PRIMARY KEY, conversation_type INTEGER, name TEXT, participant_id_1 INTEGER)",
            "CREATE TABLE participants(_id INTEGER PRIMARY KEY, conversation_id INTEGER, participant_info_id INTEGER, active INTEGER)",
            "CREATE TABLE participants_info(_id INTEGER PRIMARY KEY, number TEXT, contact_name TEXT, display_name TEXT, viber_name TEXT)",
            "CREATE TABLE messages(_id INTEGER PRIMARY KEY, conversation_id INTEGER, participant_id INTEGER, msg_date INTEGER, send_type INTEGER, body TEXT, order_key INTEGER, deleted INTEGER, extra_mime INTEGER, msg_info TEXT)",
            "INSERT INTO participants_info VALUES(1, '+380000', 'Me', NULL, NULL)",
            "INSERT INTO participants_info VALUES(2, '+380 67 123', 'Alice', 'Alice display', 'Alice Viber')",
            "INSERT INTO participants_info VALUES(3, '+380999', 'Bob', NULL, NULL)",
            "INSERT INTO conversations VALUES(10, ${AndroidConversationType.DIRECT}, NULL, 2)",
            "INSERT INTO conversations VALUES(20, ${AndroidConversationType.GROUP}, 'Team', 2)",
            "INSERT INTO conversations VALUES(30, ${AndroidConversationType.DIRECT}, NULL, 3)",
            "INSERT INTO conversations VALUES(40, ${AndroidConversationType.SELF}, NULL, 0)",
            "INSERT INTO participants VALUES(100, 10, 2, ${AndroidParticipantState.ACTIVE})",
            "INSERT INTO participants VALUES(101, 10, 1, ${AndroidParticipantState.INACTIVE})",
            "INSERT INTO participants VALUES(200, 20, 3, ${AndroidParticipantState.ACTIVE})",
            "INSERT INTO participants VALUES(201, 20, 1, ${AndroidParticipantState.INACTIVE})",
            "INSERT INTO participants VALUES(202, 20, 2, ${AndroidParticipantState.ACTIVE})",
            "INSERT INTO participants VALUES(400, 40, 1, ${AndroidParticipantState.INACTIVE})",
            "INSERT INTO messages VALUES(100, 10, 100, 1000, ${MessageDirection.INCOMING}, NULL, 100, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.IMAGE}, '{}')",
            "INSERT INTO messages VALUES(101, 10, 101, 2000, ${MessageDirection.OUTGOING}, 'Hello', 101, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.TEXT}, '{}')",
            "INSERT INTO messages VALUES(102, 10, 100, 1700, ${MessageDirection.INCOMING}, 'Removed row', 102, ${AndroidMessageState.DELETED}, ${AndroidMessageType.TEXT}, '{}')",
            "INSERT INTO messages VALUES(103, 10, 100, 1500, ${MessageDirection.INCOMING}, NULL, 103, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.LINK}, '{\"Text\":\"Preview\",\"URL\":\"https://example.com\"}')",
            "INSERT INTO messages VALUES(104, 10, 100, 1600, ${MessageDirection.INCOMING}, NULL, 104, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.AUDIO}, '{}')",
            "INSERT INTO messages VALUES(105, 10, 100, 1700, ${MessageDirection.INCOMING}, '[{\"Type\":\"txt\",\"Text\":\"Business notice\"}]', 105, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.BUSINESS}, '{}')",
            "INSERT INTO messages VALUES(106, 10, 100, 5000, ${MessageDirection.INCOMING}, 'Liked message copy', 106, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.REACTION}, '{}')",
            "INSERT INTO messages VALUES(107, 10, 100, 1800, ${MessageDirection.INCOMING}, 'message_deleted/id', 107, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.DELETED}, '{}')",
            "INSERT INTO messages VALUES(200, 20, 200, 3000, ${MessageDirection.INCOMING}, 'ГРУПОВЕ 100%_DONE', 200, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.TEXT}, '{}')",
            "INSERT INTO messages VALUES(400, 40, 400, 2500, ${MessageDirection.OUTGOING}, 'Note to self', 400, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.TEXT}, '{}')",
        )
    }

    private fun File.writeSqlite(vararg statements: String) {
        SQLiteDatabase.openOrCreateDatabase(this, null).use { db ->
            statements.forEach(db::execSQL)
        }
    }
}
