package dotki.desktop

import dotki.*
import io.heapy.kinetica.application.WindowSize
import io.heapy.kinetica.desktop.testing.desktopPreviewPng
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.*

/** Uses the real Darwin transport and public server. Creates disposable QA sessions. */
class DesktopIntegrationTest {
    @Test fun nativeClientPlaysBothGamesAgainstAiAndRendersThem() = runBlocking {
        val client=DesktopClient(dispatcher=coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            save("lobby",client)
            for(kind in GameKind.entries) {
                client.level(kind,BotLevel.HARD);client.start(kind,true)
                await { client.canMove() }
                client.move(if(kind==GameKind.DOTS) Point(0,21) else Point(0,0))
                await { client.state.value.room?.game?.stones?.size==2 && client.canMove() }
                assertEquals(BotLevel.HARD,client.state.value.room!!.botLevel)
                if(kind==GameKind.DOTS) {
                    val bot=client.state.value.room!!.game.stones.last().point
                    assertFalse(bot.x<=1 && bot.y in 20..22)
                }
                save(if(kind==GameKind.DOTS) "dots" else "five",client)
                client.send("resign");await { client.state.value.room?.game?.finished==true }
                client.send("rematch");await { client.state.value.room?.game?.stones?.isEmpty()==true && client.canMove() }
                client.leave()
            }
        } finally { val done=CompletableDeferred<Unit>();client.close {done.complete(Unit)};done.await() }
    }
    @Test fun desktopClientsShareRoomAndUndoAcrossTheNetwork() = runBlocking {
        val dispatcher=coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        val red=DesktopClient(dispatcher=dispatcher);val blue=DesktopClient(dispatcher=dispatcher)
        try {
            red.start(GameKind.FIVE,false);await { red.state.value.connected }
            blue.code(red.invite());blue.join();await { red.canMove() && blue.state.value.connected }
            red.move(Point(0,0));await { blue.state.value.room?.game?.revision==1 && red.state.value.room?.game?.revision==1 }
            red.send("undo");await { blue.state.value.room?.game?.revision==2 && red.state.value.room?.game?.revision==2 }
            assertTrue(blue.state.value.room!!.game.stones.isEmpty())
            red.move(Point(1,0));await { blue.canMove() }
            blue.move(Point(0,2));await { red.state.value.room?.game?.stones?.size==2 }
        } finally {
            for(client in listOf(red,blue)){val done=CompletableDeferred<Unit>();client.close{done.complete(Unit)};done.await()}
        }
    }
    private suspend fun await(condition:()->Boolean) { withTimeout(25_000){while(!condition()) delay(30)} }
    private fun save(name:String,client:DesktopClient) {
        SystemFileSystem.createDirectories(Path("/tmp/dotki-desktop-qa"))
        for ((suffix,size) in listOf("" to WindowSize(1100.0,860.0), "-small" to WindowSize(850.0,740.0))) {
            val bytes=desktopPreviewPng(size){DotkiDesktop(client)}
            SystemFileSystem.sink(Path("/tmp/dotki-desktop-qa/$name$suffix.png")).buffered().use {it.write(bytes)}
        }
    }
}
