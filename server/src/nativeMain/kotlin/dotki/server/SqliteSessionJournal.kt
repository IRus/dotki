package dotki.server

import co.touchlab.sqliter.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dotki.wireJson
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One durable transaction per event; the lock also serializes shutdown with writes. */
@OptIn(ExperimentalUuidApi::class)
internal class SqliteSessionJournal(path: String, private val appVersion: String) : SessionJournal {
    private val lock = Mutex()
    private val runtimeId = Uuid.random().toString()
    internal val connection: DatabaseConnection

    init {
        val directory = path.substringBeforeLast('/', ".").ifEmpty { "/" }
        SystemFileSystem.createDirectories(Path(directory))
        connection = createDatabaseManager(DatabaseConfiguration(
            name = path.substringAfterLast('/'), version = 1,
            extendedConfig = DatabaseConfiguration.Extended(basePath = directory, foreignKeyConstraints = true,
                busyTimeout = 2_000, synchronousFlag = SynchronousFlag.FULL),
            create = { db ->
                db.rawExecSql("""CREATE TABLE sessions (
                    id TEXT PRIMARY KEY, room_code TEXT NOT NULL, kind TEXT NOT NULL, mode TEXT NOT NULL,
                    bot_level TEXT NOT NULL, app_version TEXT NOT NULL, runtime_id TEXT NOT NULL,
                    started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_revision INTEGER NOT NULL,
                    last_snapshot_json TEXT NOT NULL, closed_at INTEGER, closed_reason TEXT
                )""")
                db.rawExecSql("""CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id),
                    occurred_at INTEGER NOT NULL, type TEXT NOT NULL, actor TEXT, revision INTEGER NOT NULL,
                    ai_duration_ms INTEGER, payload_json TEXT NOT NULL
                )""")
                db.rawExecSql("CREATE INDEX events_session_id ON events(session_id, id)")
                db.rawExecSql("CREATE INDEX sessions_room_code ON sessions(room_code, started_at)")
            },
        )).createMultiThreadedConnection()
        // Live rooms are not resumed. Mark their archived sessions explicitly after a crash/restart.
        connection.withStatement("UPDATE sessions SET closed_at = ?, closed_reason = 'server_restart' WHERE closed_at IS NULL") {
            bindLong(1, Clock.System.now().toEpochMilliseconds()); execute()
        }
    }

    override suspend fun record(event: SessionEvent) = lock.withLock {
        val room = event.room
        val snapshot = wireJson.encodeToString(room)
        val payload = wireJson.encodeToString(event)
        connection.withTransaction { db ->
            db.withStatement("""INSERT OR IGNORE INTO sessions
                (id, room_code, kind, mode, bot_level, app_version, runtime_id, started_at, updated_at, last_revision, last_snapshot_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""") {
                bindString(1, event.sessionId); bindString(2, room.code); bindString(3, room.game.kind.name)
                bindString(4, room.mode.name); bindString(5, room.botLevel.name); bindString(6, appVersion)
                bindString(7, runtimeId); bindLong(8, event.occurredAt); bindLong(9, event.occurredAt)
                bindLong(10, room.game.revision.toLong()); bindString(11, snapshot); execute()
            }
            db.withStatement("""INSERT INTO events
                (session_id, occurred_at, type, actor, revision, ai_duration_ms, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)""") {
                bindString(1, event.sessionId); bindLong(2, event.occurredAt); bindString(3, event.type)
                bindString(4, event.actor?.name); bindLong(5, room.game.revision.toLong())
                bindLong(6, event.aiDurationMillis); bindString(7, payload); execute()
            }
            db.withStatement("UPDATE sessions SET updated_at = ?, last_revision = ?, last_snapshot_json = ? WHERE id = ?") {
                bindLong(1, event.occurredAt); bindLong(2, room.game.revision.toLong())
                bindString(3, snapshot); bindString(4, event.sessionId); execute()
            }
            if (event.type == "expire") db.withStatement("UPDATE sessions SET closed_at = ?, closed_reason = 'expired' WHERE id = ?") {
                bindLong(1, event.occurredAt); bindString(2, event.sessionId); execute()
            }
        }
    }

    suspend fun close() = lock.withLock {
        if (!connection.closed) {
            try {
                connection.withStatement("UPDATE sessions SET closed_at = ?, closed_reason = 'server_shutdown' WHERE runtime_id = ? AND closed_at IS NULL") {
                    bindLong(1, Clock.System.now().toEpochMilliseconds()); bindString(2, runtimeId); execute()
                }
            } finally { connection.close() }
        }
    }
}
