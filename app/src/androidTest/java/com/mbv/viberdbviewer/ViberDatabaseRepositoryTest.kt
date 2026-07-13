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
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE Contact(ContactID INTEGER PRIMARY KEY, Name TEXT, ClientName TEXT, Number TEXT)")
            db.execSQL("CREATE TABLE ChatInfo(ChatID INTEGER PRIMARY KEY, Name TEXT)")
            db.execSQL("CREATE TABLE ChatRelation(ChatID INTEGER, ContactID INTEGER)")
            db.execSQL(
                "CREATE TABLE Events(EventID INTEGER PRIMARY KEY, TimeStamp INTEGER NOT NULL, Direction INTEGER NOT NULL, ChatID INTEGER, ContactID INTEGER, SortOrder INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE Messages(EventID INTEGER PRIMARY KEY, Type INTEGER NOT NULL, Body TEXT, Info TEXT, ClientFlag INTEGER)",
            )

            db.execSQL("INSERT INTO Contact VALUES(1, NULL, 'Me', '+380000')")
            db.execSQL("INSERT INTO Contact VALUES(2, '', 'Alice', '+380 67 123')")
            db.execSQL("INSERT INTO Contact VALUES(3, 'Bob', NULL, '+380999')")
            db.execSQL("INSERT INTO ChatInfo VALUES(10, NULL)")
            db.execSQL("INSERT INTO ChatInfo VALUES(20, 'Team')")
            db.execSQL("INSERT INTO ChatInfo VALUES(30, 'Empty')")
            db.execSQL("INSERT INTO ChatRelation VALUES(10, 1)")
            db.execSQL("INSERT INTO ChatRelation VALUES(10, 2)")
            db.execSQL("INSERT INTO ChatRelation VALUES(20, 1)")
            db.execSQL("INSERT INTO ChatRelation VALUES(20, 2)")
            db.execSQL("INSERT INTO ChatRelation VALUES(20, 3)")
            db.execSQL("INSERT INTO Events VALUES(100, 1000, ${MessageDirection.INCOMING}, 10, 2, 100)")
            db.execSQL("INSERT INTO Events VALUES(101, 2000, ${MessageDirection.OUTGOING}, 10, 1, 101)")
            db.execSQL("INSERT INTO Events VALUES(102, 5000, ${MessageDirection.INCOMING}, 10, 2, 102)")
            db.execSQL("INSERT INTO Events VALUES(103, 1500, ${MessageDirection.INCOMING}, 10, 2, 103)")
            db.execSQL("INSERT INTO Events VALUES(104, 1600, ${MessageDirection.INCOMING}, 10, 2, 104)")
            db.execSQL("INSERT INTO Events VALUES(110, 1200, ${MessageDirection.OUTGOING}, 10, 1, 110)")
            db.execSQL("INSERT INTO Events VALUES(111, 1300, ${MessageDirection.OUTGOING}, 10, 1, 111)")
            db.execSQL("INSERT INTO Events VALUES(112, 6000, ${MessageDirection.OUTGOING}, 10, 1, 112)")
            db.execSQL("INSERT INTO Events VALUES(113, 1700, ${MessageDirection.OUTGOING}, 10, 1, 113)")
            db.execSQL("INSERT INTO Events VALUES(114, 1400, ${MessageDirection.INCOMING}, 10, 2, 114)")
            db.execSQL("INSERT INTO Events VALUES(200, 3000, ${MessageDirection.INCOMING}, 20, 3, 200)")
            db.execSQL("INSERT INTO Messages VALUES(100, ${DesktopMessageType.IMAGE}, NULL, '{}', ${DesktopClientFlag.NONE})")
            db.execSQL("INSERT INTO Messages VALUES(101, ${DesktopMessageType.TEXT}, 'Hello', '{}', ${DesktopClientFlag.NONE})")
            db.execSQL("INSERT INTO Messages VALUES(102, ${DesktopMessageType.HEART_REACTION}, NULL, '{}', ${DesktopClientFlag.NONE})")
            db.execSQL(
                "INSERT INTO Messages VALUES(103, ${DesktopMessageType.BUSINESS}, 'Business notice', '{}', ${DesktopClientFlag.NONE})",
            )
            db.execSQL("INSERT INTO Messages VALUES(104, ${DesktopMessageType.DELETED}, NULL, '{}', ${DesktopClientFlag.NONE})")
            db.execSQL(
                "INSERT INTO Messages VALUES(110, ${DesktopMessageType.TEXT}, 'Final edited text', '{\"desktop_info\":{\"edit_token\":10002}}', ${DesktopClientFlag.NONE})",
            )
            db.execSQL(
                "INSERT INTO Messages VALUES(111, ${DesktopMessageType.TEXT}, 'First edit', '{\"edit\":{\"token\":10000}}', ${DesktopClientFlag.EDIT_HISTORY})",
            )
            db.execSQL(
                "INSERT INTO Messages VALUES(112, ${DesktopMessageType.TEXT}, 'Final edited text', '{\"rich_media\":{},\"edit\":{\"isSilent\":false,\"token\":10000}}', ${DesktopClientFlag.EDIT_HISTORY_VARIANT})",
            )
            db.execSQL(
                "INSERT INTO Messages VALUES(113, ${DesktopMessageType.DELETED}, NULL, '{\"edit\":{\"token\":104}}', ${DesktopClientFlag.NONE})",
            )
            db.execSQL(
                "INSERT INTO Messages VALUES(114, ${DesktopMessageType.TEXT}, 'Orphan edit kept', '{\"desktop_info\":{},\"edit\":{\"token\":99999}}', ${DesktopClientFlag.ORPHANED_EDIT})",
            )
            db.execSQL(
                "INSERT INTO Messages VALUES(200, ${DesktopMessageType.LINK}, 'Website', '{\"URL\":\"https://example.com\"}', ${DesktopClientFlag.NONE})",
            )
        }
    }

    private fun createAndroidFixture(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE conversations(_id INTEGER PRIMARY KEY, conversation_type INTEGER, name TEXT, participant_id_1 INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE participants(_id INTEGER PRIMARY KEY, conversation_id INTEGER, participant_info_id INTEGER, active INTEGER)",
            )
            db.execSQL(
                "CREATE TABLE participants_info(_id INTEGER PRIMARY KEY, number TEXT, contact_name TEXT, display_name TEXT, viber_name TEXT)",
            )
            db.execSQL(
                "CREATE TABLE messages(_id INTEGER PRIMARY KEY, conversation_id INTEGER, participant_id INTEGER, msg_date INTEGER, send_type INTEGER, body TEXT, order_key INTEGER, deleted INTEGER, extra_mime INTEGER, msg_info TEXT)",
            )

            db.execSQL("INSERT INTO participants_info VALUES(1, '+380000', 'Me', NULL, NULL)")
            db.execSQL("INSERT INTO participants_info VALUES(2, '+380 67 123', 'Alice', 'Alice display', 'Alice Viber')")
            db.execSQL("INSERT INTO participants_info VALUES(3, '+380999', 'Bob', NULL, NULL)")
            db.execSQL("INSERT INTO conversations VALUES(10, ${AndroidConversationType.DIRECT}, NULL, 2)")
            db.execSQL("INSERT INTO conversations VALUES(20, ${AndroidConversationType.GROUP}, 'Team', 2)")
            db.execSQL("INSERT INTO conversations VALUES(30, ${AndroidConversationType.DIRECT}, NULL, 3)")
            db.execSQL("INSERT INTO conversations VALUES(40, ${AndroidConversationType.SELF}, NULL, 0)")
            db.execSQL("INSERT INTO participants VALUES(100, 10, 2, ${AndroidParticipantState.ACTIVE})")
            db.execSQL("INSERT INTO participants VALUES(101, 10, 1, ${AndroidParticipantState.INACTIVE})")
            db.execSQL("INSERT INTO participants VALUES(200, 20, 3, ${AndroidParticipantState.ACTIVE})")
            db.execSQL("INSERT INTO participants VALUES(201, 20, 1, ${AndroidParticipantState.INACTIVE})")
            db.execSQL("INSERT INTO participants VALUES(202, 20, 2, ${AndroidParticipantState.ACTIVE})")
            db.execSQL("INSERT INTO participants VALUES(400, 40, 1, ${AndroidParticipantState.INACTIVE})")
            db.execSQL(
                "INSERT INTO messages VALUES(100, 10, 100, 1000, ${MessageDirection.INCOMING}, NULL, 100, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.IMAGE}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(101, 10, 101, 2000, ${MessageDirection.OUTGOING}, 'Hello', 101, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.TEXT}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(102, 10, 100, 1700, ${MessageDirection.INCOMING}, 'Removed row', 102, ${AndroidMessageState.DELETED}, ${AndroidMessageType.TEXT}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(103, 10, 100, 1500, ${MessageDirection.INCOMING}, NULL, 103, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.LINK}, '{\"Text\":\"Preview\",\"URL\":\"https://example.com\"}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(104, 10, 100, 1600, ${MessageDirection.INCOMING}, NULL, 104, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.AUDIO}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(105, 10, 100, 1700, ${MessageDirection.INCOMING}, '[{\"Type\":\"txt\",\"Text\":\"Business notice\"}]', 105, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.BUSINESS}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(106, 10, 100, 5000, ${MessageDirection.INCOMING}, 'Liked message copy', 106, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.REACTION}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(107, 10, 100, 1800, ${MessageDirection.INCOMING}, 'message_deleted/id', 107, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.DELETED}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(200, 20, 200, 3000, ${MessageDirection.INCOMING}, 'Group message', 200, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.TEXT}, '{}')",
            )
            db.execSQL(
                "INSERT INTO messages VALUES(400, 40, 400, 2500, ${MessageDirection.OUTGOING}, 'Note to self', 400, ${AndroidMessageState.VISIBLE}, ${AndroidMessageType.TEXT}, '{}')",
            )
        }
    }
}
