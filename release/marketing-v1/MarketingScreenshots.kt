package lighting.luxio
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
class MarketingController(val name: String, val demoIP: String) : AutoCloseable {
    private val socket=ServerSocket(0)
    private val worker=Executors.newSingleThreadExecutor()
    private val simulator=SimulatorDevice()
    val address="http://127.0.0.1:${socket.localPort}"
    @Volatile var installed=false
    @Volatile var installRequested=false
    @Volatile var ssid="Luxio Home"
    @Volatile var wifiPassword: String?=null
    @Volatile var identity="fixture-device"
    @Volatile var posts=0
    @Volatile var failMutation=false
    @Volatile var malformedPagination=false
    init { worker.submit {
        while(!socket.isClosed) try { socket.accept().use { handle(it) } } catch(_: Exception) {}
    } }
    private fun handle(peer: Socket) {
        peer.soTimeout=5000
        val input=peer.getInputStream(); val header=StringBuilder()
        while(!header.endsWith("\r\n\r\n")) { val b=input.read(); if(b<0) return; header.append(b.toChar()); check(header.length<8192) }
        if(header.startsWith("GET /status")) { val body="{\"received\":100,\"size\":100,\"error\":\"\"}".toByteArray(); peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()+body); return }
        posts++
        val length=header.lines().first { it.startsWith("Content-Length:",true) }.substringAfter(':').trim().toInt()
        val payload=input.readNBytes(length); val request=Proto.decode(payload); val operation=request.number(2)
        if(failMutation && operation==15) return
        val response=Proto().uint(1,request.number(1).toLong())
        when(operation) {
            16 -> { val network=Proto().text(1,"Luxio Home").uint(2,87).uint(3,1); response.data(12,network.bytes.toByteArray()); response.data(12,Proto().text(1,"Guest").uint(2,109).uint(3,0).bytes.toByteArray()) }
            4 -> { ssid=request.message(6).text(1); wifiPassword=request.message(6).text(2) }
            12 -> response.text(6,"3497-011-2332").text(7,"MT:MARKETING-DEMO-NOT-A-REAL-DEVICE")
            13 -> response.uint(8,36)
            14 -> installRequested=true
            25 -> if(malformedPagination) { response.uint(20,1); response.data(18,colorPresets.first().copy(id="one").proto().bytes.toByteArray()) }
            0 -> Unit
            else -> { val result=simulator.request(payload); for((key,values) in result.fields) if(key!=1 && key!=3) values.forEach { response.data(key,it) } }
        }
        val state=simulator.state.copy(deviceId=identity,name=name,ip=demoIP,build=if(installed) 36 else 35)
        response.data(3,state.proto().bytes.toByteArray()).data(21,Proto().uint(1,1).text(11,ssid).text(12,"192.168.1.1").data(13,"1.1.1.1".toByteArray()).bytes.toByteArray())
        val bytes=response.bytes.toByteArray()
        peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/x-protobuf\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
    }
    override fun close() { socket.close(); worker.shutdownNow() }
}

class MarketingScreenshots {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun capture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("luxio", Context.MODE_PRIVATE).edit().clear().commit()
        val discovery = Discovery(context)
        val directory = File(context.getExternalFilesDir(null), "marketing").apply { mkdirs() }
        val names = listOf("Living Room", "Bedroom", "Kitchen", "Studio", "Hallway")
        val palettes = listOf(listOf(0xff5c79,0xffb56b),listOf(0x733cff,0xc45fff),listOf(0xffe4a2,0xffca69),listOf(0x00d2ff,0x4fff7f),listOf(0x00d2ff,0x00d2ff))
        val servers = names.mapIndexed { i, name -> MarketingController(name,"192.168.1.${40+i}").apply { identity="marketing-$i" } }
        try {
            runBlocking {
                servers.forEachIndexed { i, server ->
                    val client = DeviceClient(server.address)
                    val gradient=LibraryItem(name="",kind=1,colors=palettes[i].map { LedColor(listOf((it shr 16) and 255,(it shr 8) and 255,it and 255,0,0,0)) })
                    client.request(17,Proto().data(11,gradient.proto().bytes.toByteArray()))
                    client.request(2,Proto().uint(4,listOf(190L,100L,225L,150L,100L)[i]))
                    if(i==4) client.request(15,Proto().uint(10,0))
                    client.request(0)
                    if(i==3) client.usingBluetooth=true
                    discovery.clients["marketing-$i"]=client
                }
            }
            ui.runOnUiThread { ui.activity.enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)) }
            ui.setContent { MaterialTheme(colorScheme=LuxioColors) { Surface(Modifier.fillMaxSize()) { Luxio(discovery) } } }
            ui.waitForIdle(); android.os.SystemClock.sleep(1000)
            File(directory,"home.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithText("Living Room").performClick()
            ui.onNodeWithTag("mode-1").performClick()
            ui.waitForIdle(); android.os.SystemClock.sleep(1000)
            File(directory,"gradient.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithTag("mode-2").performClick()
            ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-Cyberpunk"))
            ui.onNodeWithTag("preset-Cyberpunk").performClick()
            ui.onNodeWithTag("device-scroll").performScrollToIndex(0)
            ui.waitForIdle(); android.os.SystemClock.sleep(1500)
            File(directory,"animation.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithContentDescription("Device settings").performClick()
            ui.waitForIdle(); android.os.SystemClock.sleep(1000)
            File(directory,"settings.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Show pairing code"))
            ui.onNodeWithText("Show pairing code").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Matter pairing QR code").fetchSemanticsNodes().isNotEmpty() }
            ui.waitForIdle(); android.os.SystemClock.sleep(1000)
            File(directory,"matter.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { servers.forEach { it.close() } }
    }
}
