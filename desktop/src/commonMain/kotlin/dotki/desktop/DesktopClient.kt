package dotki.desktop

import dotki.*
import io.heapy.kinetica.store
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.time.TimeSource

data class DesktopState(
    val name: String = "", val code: String = "", val room: RoomView? = null, val player: Player? = null,
    val connected: Boolean = false, val connecting: Boolean = false, val error: String? = null,
    val notice: String? = null, val undoSeconds: Int = 0, val confirmResign: Boolean = false,
    val dotsLevel: BotLevel = BotLevel.MEDIUM, val fiveLevel: BotLevel = BotLevel.MEDIUM,
)

class DesktopClient(val server: String = "https://dotki.heapyhop.com", private val http: HttpClient = HttpClient { install(WebSockets) }, dispatcher: CoroutineDispatcher = Dispatchers.Main) {
    val state = store(DesktopState())
    val board = DesktopBoard()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val tokens = mutableMapOf<String,String>()
    private var generation = 0
    private var connection: Job? = null
    private var commands: Channel<ClientMessage>? = null
    private var undoStarted = TimeSource.Monotonic.markNow()
    private var undoMillis = 0L
    init {
        scope.launch { while (isActive) {
            delay(100)
            val ui = state.value
            val left = if (ui.connected && ui.room?.undo?.player == ui.player)
                ((undoMillis - undoStarted.elapsedNow().inWholeMilliseconds + 999) / 1000).coerceAtLeast(0).toInt() else 0
            if (left != ui.undoSeconds) state.value = ui.copy(undoSeconds = left)
        } }
    }
    fun name(value: String) { state.value = state.value.copy(name = value.take(24)) }
    fun code(value: String) { state.value = state.value.copy(code = value.substringAfter("room=",value).substringBefore('&').trim().uppercase().take(8)) }
    fun level(kind: GameKind, value: BotLevel) { state.value = if (kind == GameKind.DOTS) state.value.copy(dotsLevel=value) else state.value.copy(fiveLevel=value) }
    fun start(kind: GameKind, ai: Boolean) = connect(ClientMessage("create", name=state.value.name, kind=kind,
        mode=if(ai) GameMode.AI else GameMode.MULTIPLAYER, botLevel=if(kind==GameKind.DOTS) state.value.dotsLevel else state.value.fiveLevel))
    fun join() {
        val ui=state.value
        if(!Regex("[A-F0-9]{8}").matches(ui.code)) { state.value=ui.copy(error="Введи код комнаты из 8 символов или ссылку приглашения"); return }
        connect(ClientMessage("join",room=ui.code,name=ui.name,token=tokens[ui.code]))
    }
    private fun receive(room: RoomView) {
        undoStarted=TimeSource.Monotonic.markNow(); undoMillis=room.undo?.remainingMillis ?: 0
        board.update(room.game)
        state.value=state.value.copy(room=room,undoSeconds=if(room.undo?.player==state.value.player) ((undoMillis+999)/1000).toInt() else 0)
    }
    private fun connect(hello: ClientMessage) {
        val current=++generation
        connection?.cancel(); commands?.close(); commands=null
        state.value=state.value.copy(room=null,player=null,connecting=true,connected=false,error=null,confirmResign=false)
        connection=scope.launch {
            var request=hello; var retry=0
            while(isActive && current==generation) {
                var rejected=false
                try {
                    http.webSocket(urlString=server.trimEnd('/').replaceFirst("https://","wss://").replaceFirst("http://","ws://")+"/ws") {
                        val channel=Channel<ClientMessage>(32); commands=channel
                        send(Frame.Text(wireJson.encodeToString(request)))
                        val writer=launch { for(message in channel) send(Frame.Text(wireJson.encodeToString(message))) }
                        try {
                            for(frame in incoming) {
                                if(frame !is Frame.Text || current!=generation) continue
                                val message=wireJson.decodeFromString<ServerMessage>(frame.readText())
                                when(message.type) {
                                    "welcome" -> {
                                        val room=message.room ?: continue
                                        tokens[room.code]=message.token ?: continue
                                        retry=0; state.value=state.value.copy(code=room.code,player=message.player,connected=true,connecting=false,error=null)
                                        receive(room)
                                    }
                                    "state" -> message.room?.let(::receive)
                                    "error" -> {
                                        if(!state.value.connected) { rejected=true; close() }
                                        state.value=state.value.copy(error=message.error,connecting=false)
                                    }
                                }
                            }
                            if(closeReason.await()?.code?.toInt()==4001) {
                                rejected=true; state.value=state.value.copy(error="Игра открыта в другом окне. Вернись в комнату вручную")
                            }
                        } finally { channel.close(); writer.cancelAndJoin(); if(commands===channel) commands=null }
                    }
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { if(current==generation) state.value=state.value.copy(error="Соединение потеряно. Проверяю подключение…") }
                if(current!=generation) break
                val room=state.value.room
                state.value=state.value.copy(connected=false,connecting=!rejected && room!=null)
                if(rejected || room==null) {
                    if(!rejected) state.value=state.value.copy(error="Не удалось подключиться к серверу. Попробуй ещё раз")
                    break
                }
                delay(minOf(15000L,750L*(1 shl minOf(retry++,4))))
                request=ClientMessage("join",room=room.code,token=tokens[room.code],name=state.value.name)
            }
        }
    }
    fun canMove(): Boolean = state.value.let { ui -> ui.connected && ui.room?.let { !it.game.finished && it.game.turn==ui.player && it.seats.size==2 && it.seats.all(Seat::online) }==true }
    fun move(point: Point) { if(canMove()) send("move",point) }
    fun send(type: String, point: Point?=null) {
        val ui=state.value
        if(!ui.connected) return
        if(commands?.trySend(ClientMessage(type,x=point?.x,y=point?.y,revision=ui.room?.game?.revision))?.isSuccess!=true)
            state.value=ui.copy(error="Не удалось отправить ход. Дождись подключения")
        else state.value=ui.copy(error=null,notice=null,confirmResign=false)
    }
    fun confirmResign(value: Boolean) { state.value=state.value.copy(confirmResign=value) }
    fun notice(text: String) { state.value=state.value.copy(notice=text) }
    fun invite(): String = server.trimEnd('/')+"/?room="+state.value.code
    fun leave() {
        generation++; connection?.cancel(); commands?.close(); commands=null
        val ui=state.value
        state.value=DesktopState(name=ui.name,dotsLevel=ui.dotsLevel,fiveLevel=ui.fiveLevel)
        board.fit()
    }
    fun close(completed: () -> Unit) {
        generation++; connection?.cancel(); commands?.close()
        scope.launch {
            try { connection?.join(); http.close(); http.coroutineContext[Job]?.join() }
            finally { completed(); scope.cancel() }
        }
    }
}
