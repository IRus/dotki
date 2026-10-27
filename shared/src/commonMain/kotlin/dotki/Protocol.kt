package dotki

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val wireJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable enum class GameKind { DOTS, FIVE }
@Serializable enum class GameMode { MULTIPLAYER, AI }
@Serializable enum class BotLevel { MEDIUM, HARD }
@Serializable enum class Player {
    RED, BLUE;
    fun other() = if (this == RED) BLUE else RED
}
@Serializable data class Point(val x: Int, val y: Int)
@Serializable data class Stone(val point: Point, val player: Player)
@Serializable data class Territory(val point: Point, val player: Player)
@Serializable data class GameState(
    val kind: GameKind,
    val stones: List<Stone> = emptyList(),
    val territories: List<Territory> = emptyList(),
    val turn: Player = Player.RED,
    val winner: Player? = null,
    val finished: Boolean = false,
    val winningLine: List<Point> = emptyList(),
    val redScore: Int = 0,
    val blueScore: Int = 0,
    val passes: Int = 0,
    val lastMove: Point? = null,
    val revision: Int = 0,
)
@Serializable data class Seat(val player: Player, val name: String, val online: Boolean, val bot: Boolean = false)
@Serializable data class UndoOffer(val player: Player, val remainingMillis: Long)
@Serializable data class RoomView(
    val code: String,
    val game: GameState,
    val seats: List<Seat>,
    val rematch: List<Player> = emptyList(),
    val mode: GameMode = GameMode.MULTIPLAYER,
    val botLevel: BotLevel = BotLevel.MEDIUM,
    val undo: UndoOffer? = null,
)
@Serializable data class ClientMessage(
    val type: String,
    val room: String? = null,
    val token: String? = null,
    val name: String = "Игрок",
    val kind: GameKind = GameKind.DOTS,
    val x: Int? = null,
    val y: Int? = null,
    val revision: Int? = null,
    val mode: GameMode = GameMode.MULTIPLAYER,
    val botLevel: BotLevel = BotLevel.MEDIUM,
)
@Serializable data class ServerMessage(
    val type: String,
    val room: RoomView? = null,
    val player: Player? = null,
    val token: String? = null,
    val error: String? = null,
)
