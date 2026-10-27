package dotki.server

import dotki.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds

fun Application.dotki(journal: SessionJournal = SessionJournal.None, assets: (String) -> ByteArray?) {
    val rooms = Rooms(this, journal = journal)
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
        maxFrameSize = 4096
    }
    launch {
        while (isActive) { delay(60_000); rooms.cleanup() }
    }
    routing {
        get("/health") {
            call.respondText(if (rooms.healthy) "dotki-native: ok" else "dotki-native: archive unavailable",
                status = if (rooms.healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
        }
        webSocket("/ws") {
            // Browsers may only connect from the same host. Non-browser clients have no Origin.
            val origin = call.request.headers[HttpHeaders.Origin]
            val host = call.request.headers[HttpHeaders.Host]
            if (origin != null && origin != "http://$host" && origin != "https://$host") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                return@webSocket
            }
            val peer = Peer()
            var membership: Membership? = null
            val writer = launch {
                var reason = CloseReason(CloseReason.Codes.NORMAL, "Reconnect for current state")
                try {
                    for (message in peer.messages) send(wireJson.encodeToString(message))
                } catch (_: PeerReplaced) {
                    reason = CloseReason(4001, "Player opened another connection")
                } finally {
                    close(reason)
                }
            }
            try {
                val first = withTimeoutOrNull(10_000) { incoming.receive() } as? Frame.Text
                    ?: return@webSocket
                membership = rooms.enter(wireJson.decodeFromString<ClientMessage>(first.readText()), peer)
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    try {
                        rooms.command(membership, wireJson.decodeFromString<ClientMessage>(frame.readText()))
                    } catch (e: InvalidMove) {
                        peer.messages.send(ServerMessage("error", error = e.message))
                    } catch (e: ClientError) {
                        peer.messages.send(ServerMessage("error", error = e.message))
                    } catch (_: SerializationException) {
                        peer.messages.send(ServerMessage("error", error = "Некорректная команда"))
                    }
                }
            } catch (e: ClientError) {
                send(wireJson.encodeToString(ServerMessage("error", error = e.message)))
            } catch (_: SerializationException) {
                send(wireJson.encodeToString(ServerMessage("error", error = "Некорректное сообщение")))
            } finally {
                withContext(NonCancellable) { membership?.let { rooms.leave(it) } }
                peer.messages.close()
                writer.cancelAndJoin()
            }
        }
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifEmpty { "index.html" }
            val type = when (path) {
                "index.html" -> ContentType.Text.Html
                "client.js", "client.js.map" -> if (path.endsWith(".map")) ContentType.Application.Json else ContentType.Application.JavaScript
                "style.css" -> ContentType.Text.CSS
                "favicon.svg" -> ContentType.Image.SVG
                else -> return@get call.respond(HttpStatusCode.NotFound)
            }
            val bytes = withContext(Dispatchers.Default) { assets(path) }
                ?: return@get call.respondText("Web build missing. Run ./gradlew :web:jsBrowserDistribution", status = HttpStatusCode.ServiceUnavailable)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(bytes, type)
        }
    }
}
