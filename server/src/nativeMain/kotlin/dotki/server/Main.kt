@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dotki.server

import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.posix.getenv

fun main() {
    val port = getenv("PORT")?.toKString()?.toIntOrNull() ?: 8080
    val host = getenv("HOST")?.toKString() ?: "0.0.0.0"
    val root = getenv("WEB_ROOT")?.toKString() ?: "web/build/dist/js/productionExecutable"
    val journal = SqliteSessionJournal(getenv("DOTKI_DB_PATH")?.toKString() ?: ".local/dotki.sqlite",
        getenv("DOTKI_VERSION")?.toKString() ?: "dev-edge-safe-v1")
    println("Dotki • Kotlin/Native • http://localhost:$port")
    try { embeddedServer(CIO, host = host, port = port) {
        dotki(journal) { asset ->
            val path = Path(root, asset)
            if (SystemFileSystem.exists(path)) SystemFileSystem.source(path).buffered().use { it.readByteArray() }
            else null
        }
    }.start(wait = true) } finally { runBlocking { journal.close() } }
}
