@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dotki.server

import co.touchlab.sqliter.*
import dotki.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.*
import kotlin.uuid.Uuid

class SqliteSessionJournalTest {
    private fun event(type: String = "create") = SessionEvent("session-id", 1234, type,
        room = RoomView("ROOM", GameState(GameKind.DOTS), listOf(Seat(Player.RED, "О'Брайен 🐈", true))))

    @Test fun survivesReopenAndKeepsUnicodeSnapshotsAndVersion() = runTest {
        val directory = "/tmp/dotki-journal-${Uuid.random()}"
        val path = "$directory/sessions.sqlite"
        try {
            val first = SqliteSessionJournal(path, "test-v1")
            first.record(event())
            assertEquals("wal", first.connection.stringForQuery("PRAGMA journal_mode"))
            assertEquals(2L, first.connection.longForQuery("PRAGMA synchronous"))
            // Simulate an unclean stop: every event is already committed without close().
            first.connection.close()
            val reopened = SqliteSessionJournal(path, "test-v2")
            try {
                assertEquals(1L, reopened.connection.longForQuery("SELECT count(*) FROM sessions"))
                assertEquals(1L, reopened.connection.longForQuery("SELECT count(*) FROM events"))
                assertEquals("test-v1", reopened.connection.stringForQuery("SELECT app_version FROM sessions"))
                assertEquals("server_restart", reopened.connection.stringForQuery("SELECT closed_reason FROM sessions"))
                assertEquals(event(), wireJson.decodeFromString<SessionEvent>(reopened.connection.stringForQuery("SELECT payload_json FROM events")))
                assertEquals("ok", reopened.connection.stringForQuery("PRAGMA integrity_check"))
            } finally { reopened.close() }
        } finally { remove(directory) }
    }

    @Test fun failedEventRollsBackSessionAndSnapshotTogether() = runTest {
        val directory = "/tmp/dotki-journal-${Uuid.random()}"
        val store = SqliteSessionJournal("$directory/sessions.sqlite", "test")
        try {
            store.connection.rawExecSql("CREATE TRIGGER fail_event BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
            assertFails { store.record(event()) }
            assertEquals(0L, store.connection.longForQuery("SELECT count(*) FROM sessions"))
            assertEquals(0L, store.connection.longForQuery("SELECT count(*) FROM events"))
            store.connection.rawExecSql("DROP TRIGGER fail_event")
            store.record(event())
            store.record(event("expire"))
            assertEquals(2L, store.connection.longForQuery("SELECT count(*) FROM events"))
            assertEquals("expired", store.connection.stringForQuery("SELECT closed_reason FROM sessions"))
        } finally { store.close(); remove(directory) }
    }

    private fun remove(directory: String) {
        for (file in SystemFileSystem.list(Path(directory))) SystemFileSystem.delete(file)
        SystemFileSystem.delete(Path(directory))
    }
}
