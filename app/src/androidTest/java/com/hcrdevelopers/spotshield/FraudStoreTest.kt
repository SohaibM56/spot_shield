package com.hcrdevelopers.spotshield

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs on a device against real SQLite. Every test uses its own throw-away database file and
 * SharedPreferences file, so the app's real Fraud list and settings are never touched.
 */
@RunWith(AndroidJUnit4::class)
class FraudStoreTest {

    private lateinit var context: Context
    private val dbName = "fraud_test_${System.nanoTime()}.db"
    private val prefsName = "spot_test_legacy_${System.nanoTime()}"
    private var clock = 1_000_000_000_000L
    private val opened = mutableListOf<FraudStore>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        SQLiteDatabase.deleteDatabase(File(context.noBackupFilesDir, dbName))
        context.deleteSharedPreferences(prefsName)
    }

    private fun newStore(withLegacy: Boolean = false) =
        FraudStore(context, dbName, if (withLegacy) prefsName else null) { clock }.also { opened.add(it) }

    private fun message(
        n: Int,
        sender: String = "+1555000$n",
        date: Long = clock + n,
        read: Boolean = false,
        keyword: String? = null
    ) = FilteredSmsMessage("id_$n", sender, "body $n", date, read, keyword)

    @Test
    fun insertsRowsIgnoresDuplicatesAndReturnsNewestFirst() {
        val store = newStore()

        assertEquals(3, store.insertAll(listOf(message(1), message(2), message(3))))
        assertEquals(0, store.insertAll(listOf(message(2))))

        val page = store.page(10)
        assertEquals(listOf("id_3", "id_2", "id_1"), page.map { it.id })
        assertEquals(2, store.page(2).size)
    }

    @Test
    fun savesEveryFieldIncludingKeyword() {
        val store = newStore()
        store.insertAll(listOf(message(1, read = true, keyword = "donate")))

        val saved = store.page(1).single()

        assertEquals("+15550001", saved.sender)
        assertEquals("body 1", saved.body)
        assertTrue(saved.isRead)
        assertEquals("donate", saved.matchedKeyword)
    }

    @Test
    fun findsASendersMessagesAcrossNumberFormats() {
        val store = newStore()
        store.insertAll(
            listOf(
                message(1, sender = "+92 300 1234567"),
                message(2, sender = "03001234567"),
                message(3, sender = "+1 555 000 9999")
            )
        )

        val found = store.bySenderKey(PhoneNormalizer.key("0092-300-1234567"))

        assertEquals(setOf("id_1", "id_2"), found.map { it.id }.toSet())
    }

    @Test
    fun groupsMessagesBySenderAcrossNumberFormats() {
        val store = newStore()
        store.insertAll(
            listOf(
                message(1, sender = "+92 300 1234567", date = clock + 10, read = false, keyword = "donate"),
                message(2, sender = "03001234567", date = clock + 30, read = true),
                message(3, sender = "+92-300-1234567", date = clock + 20, read = false),
                message(4, sender = "AMAZON", date = clock + 5, read = true)
            )
        )

        val groups = store.groups()

        assertEquals(2, groups.size)
        val phone = groups[0]                       // newest conversation first
        assertEquals("03001234567", phone.sender)   // shown as on its latest message
        assertEquals("body 2", phone.latestBody)
        assertEquals(3, phone.count)
        assertEquals(2, phone.unread)
        assertTrue(phone.flagged)
        assertEquals("AMAZON", groups[1].sender)
        assertEquals(1, groups[1].count)
        assertFalse(groups[1].flagged)
    }

    @Test
    fun markSenderReadAndDeleteSenderOnlyTouchThatSender() {
        val store = newStore()
        store.insertAll(
            listOf(
                message(1, sender = "+92 300 1234567"),
                message(2, sender = "00923001234567"),
                message(3, sender = "+92 301 7654321")
            )
        )

        store.markSenderRead(PhoneNormalizer.key("+923001234567"))
        assertEquals(1, store.unreadCount())

        assertEquals(2, store.deleteSender(PhoneNormalizer.key("0300 1234567")))
        assertEquals(listOf("id_3"), store.page(10).map { it.id })
        assertEquals(1, store.groups().size)
    }

    @Test
    fun tracksUnreadAndMarkRead() {
        val store = newStore()
        store.insertAll(listOf(message(1), message(2), message(3, read = true)))
        assertEquals(2, store.unreadCount())

        store.markRead("id_1")

        assertEquals(1, store.unreadCount())
        assertTrue(store.page(10).first { it.id == "id_1" }.isRead)
    }

    @Test
    fun deletesOnlyTheGivenIds() {
        val store = newStore()
        store.insertAll((1..5).map { message(it) })

        store.delete(listOf("id_2", "id_4", "missing"))

        assertEquals(listOf("id_5", "id_3", "id_1"), store.page(10).map { it.id })
    }

    @Test
    fun dataSurvivesClosingAndReopeningTheDatabase() {
        val first = newStore()
        first.insertAll(listOf(message(1), message(2)))
        first.close()

        val second = newStore()

        assertEquals(2, second.count())
    }

    @Test
    fun purgesMessagesThatEnteredFraudMoreThan30DaysAgo() {
        val store = newStore()
        store.insertAll(listOf(message(1)))

        clock += FraudStore.MAX_AGE_MS + 1
        store.insertAll(listOf(message(2)))

        assertEquals(listOf("id_2"), store.page(10).map { it.id })
    }

    @Test
    fun oldMessagesMovedIntoFraudNowAreKept() {
        // A conversation from last year, moved to Fraud today, must not vanish immediately.
        val store = newStore()
        val lastYear = clock - 365L * 24 * 60 * 60 * 1000

        store.insertAll(listOf(message(1, date = lastYear)))

        assertEquals(1, store.count())
    }

    @Test
    fun capsTheTableAtTheSafetyLimit() {
        val store = newStore()

        store.insertAll((1..FraudStore.MAX_COUNT + 100).map { message(it) })

        assertEquals(FraudStore.MAX_COUNT, store.count())
    }

    @Test
    fun migratesTheOldSharedPreferencesListAndRemovesIt() {
        val legacy = """
            [{"id":"old_1","sender":"+15551110000","body":"old one","date":10,"read":false,"keyword":"donate"},
             {"id":"old_2","sender":"AMAZON","body":"old two","date":20}]
        """.trimIndent()
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString("filtered_messages", legacy).commit()

        val store = newStore(withLegacy = true)
        store.migrateLegacy()

        assertEquals(listOf("old_2", "old_1"), store.page(10).map { it.id })
        assertTrue(store.page(10).first { it.id == "old_2" }.isRead)
        assertEquals("donate", store.page(10).first { it.id == "old_1" }.matchedKeyword)
        assertNull(prefs.getString("filtered_messages", null))
    }

    @Test
    fun migrationTwiceDoesNotDuplicate() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val json = """[{"id":"old_1","sender":"a","body":"b","date":1}]"""
        prefs.edit().putString("filtered_messages", json).commit()
        val store = newStore(withLegacy = true)

        store.migrateLegacy()
        prefs.edit().putString("filtered_messages", json).commit()
        store.migrateLegacy()

        assertEquals(1, store.count())
    }

    @Test
    fun unreadableLegacyDataIsKeptInsteadOfDiscarded() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString("filtered_messages", "{this is corrupt but not tiny}").commit()
        val store = newStore(withLegacy = true)

        store.migrateLegacy()

        assertEquals(0, store.count())
        assertNotNull(prefs.getString("filtered_messages", null))
    }

    @Test
    fun handlesSeveralThreadsWritingAtOnce() {
        val store = newStore()
        val failures = java.util.concurrent.atomic.AtomicInteger()

        val workers = (0 until 4).map { worker ->
            thread {
                try {
                    repeat(100) { i -> store.insertAll(listOf(message(worker * 1000 + i))) }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                }
            }
        }
        workers.forEach { it.join() }

        assertEquals(0, failures.get())
        assertEquals(400, store.count())
    }

    @Test
    fun readingAPageStaysFastWithAFullTable() {
        val store = newStore()
        store.insertAll((1..FraudStore.MAX_COUNT).map { message(it) })

        val start = System.nanoTime()
        repeat(10) { store.page(100) }
        val perPageMs = (System.nanoTime() - start) / 10 / 1_000_000.0

        val insertStart = System.nanoTime()
        store.insertAll(listOf(message(FraudStore.MAX_COUNT + 1)))
        val insertMs = (System.nanoTime() - insertStart) / 1_000_000.0

        Log.i("FraudStoreTest", "page(100) of ${FraudStore.MAX_COUNT} rows: $perPageMs ms, single insert: $insertMs ms")
        assertTrue("page took $perPageMs ms", perPageMs < 250)
        assertTrue("insert took $insertMs ms", insertMs < 500)
        assertFalse(store.page(100).isEmpty())
    }
}
