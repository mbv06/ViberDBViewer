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
    fun importQueriesChatsAndMessages() = runBlocking {
        repository.importDatabase(Uri.fromFile(source))

        val chats = repository.loadChats()
        assertEquals(2, chats.size)
        assertEquals("Team", chats[0].title)
        assertEquals(context.resources.getQuantityString(R.plurals.participant_count, 3, 3), chats[0].subtitle)
        assertEquals("Alice", chats[1].title)
        assertEquals("+380 67 123", chats[1].subtitle)

        val messages = repository.loadMessages(chats[1].chatId)
        assertEquals(listOf(100L, 103L, 104L, 101L), messages.map { it.eventId })
        assertEquals(MessageKind.IMAGE, messages[0].kind)
        assertEquals("Business notice", messages[1].displayText)
        assertEquals(MessageKind.DELETED, messages[2].kind)
        assertEquals(context.getString(R.string.message_deleted), messages[2].displayText)
        assertEquals(MessageKind.TEXT, messages[3].kind)
    }

    @Test
    fun globalSearchFindsBusinessAndDeletedMessagesCaseInsensitively() = runBlocking {
        repository.importDatabase(Uri.fromFile(source))

        val business = repository.searchMessages("BUSINESS")
        assertEquals(listOf(103L), business.map { it.eventId })
        assertEquals("Business notice", business.single().displayText)

        val deleted = repository.searchMessages(context.getString(R.string.message_deleted).uppercase())
        assertEquals(listOf(104L), deleted.map { it.eventId })
        assertEquals(MessageKind.DELETED, deleted.single().kind)
    }

    @Test
    fun failedReplacementKeepsPreviouslyImportedDatabase() = runBlocking {
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
            db.execSQL("CREATE TABLE Events(EventID INTEGER PRIMARY KEY, TimeStamp INTEGER NOT NULL, Direction INTEGER NOT NULL, ChatID INTEGER, ContactID INTEGER, SortOrder INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE Messages(EventID INTEGER PRIMARY KEY, Type INTEGER NOT NULL, Body TEXT, Info TEXT)")

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
            db.execSQL("INSERT INTO Events VALUES(100, 1000, 0, 10, 2, 100)")
            db.execSQL("INSERT INTO Events VALUES(101, 2000, 1, 10, 1, 101)")
            db.execSQL("INSERT INTO Events VALUES(102, 5000, 0, 10, 2, 102)")
            db.execSQL("INSERT INTO Events VALUES(103, 1500, 0, 10, 2, 103)")
            db.execSQL("INSERT INTO Events VALUES(104, 1600, 0, 10, 2, 104)")
            db.execSQL("INSERT INTO Events VALUES(200, 3000, 0, 20, 3, 200)")
            db.execSQL("INSERT INTO Messages VALUES(100, 2, NULL, '{}')")
            db.execSQL("INSERT INTO Messages VALUES(101, 1, 'Hello', '{}')")
            db.execSQL("INSERT INTO Messages VALUES(102, 0, NULL, '{}')")
            db.execSQL("INSERT INTO Messages VALUES(103, 8, 'Business notice', '{}')")
            db.execSQL("INSERT INTO Messages VALUES(104, 72, NULL, '{}')")
            db.execSQL("INSERT INTO Messages VALUES(200, 9, 'Website', '{\"URL\":\"https://example.com\"}')")
        }
    }
}
