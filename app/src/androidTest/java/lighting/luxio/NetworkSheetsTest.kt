package lighting.luxio

import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// Loopback controller fixture: exercises the real HTTP transport and UI without
// mutating any controller discovered on the user's network.
class ControllerFixture : AutoCloseable {
    private val socket=ServerSocket(0)
    private val worker=Executors.newSingleThreadExecutor()
    private val simulator=SimulatorDevice()
    val address="http://127.0.0.1:${socket.localPort}"
    @Volatile var installed=false
    @Volatile var installRequested=false
    @Volatile var ssid="Home network"
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
            16 -> { val network=Proto().text(1,"Home network").uint(2,87).uint(3,1); response.data(12,network.bytes.toByteArray()); response.data(12,Proto().text(1,"Guest").uint(2,109).uint(3,0).bytes.toByteArray()) }
            4 -> { ssid=request.message(6).text(1); wifiPassword=request.message(6).text(2) }
            12 -> response.text(6,"3497-011-2332").text(7,"MT:Y.K9042C00KA0648G00")
            13 -> response.uint(8,36)
            14 -> installRequested=true
            25 -> if(malformedPagination) { response.uint(20,1); response.data(18,colorPresets.first().copy(id="one").proto().bytes.toByteArray()) }
            0 -> Unit
            else -> { val result=simulator.request(payload); for((key,values) in result.fields) if(key!=1 && key!=3) values.forEach { response.data(key,it) } }
        }
        val state=simulator.state.copy(deviceId=identity,name="Studio lights",ip="127.0.0.1",build=if(installed) 36 else 35)
        response.data(3,state.proto().bytes.toByteArray()).data(21,Proto().uint(1,1).text(11,ssid).text(12,"192.168.1.1").data(13,"1.1.1.1".toByteArray()).bytes.toByteArray())
        val bytes=response.bytes.toByteArray()
        peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/x-protobuf\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
    }
    override fun close() { socket.close(); worker.shutdownNow() }
}
class NetworkSheetsTest {
    @get:Rule val ui=createComposeRule()
    lateinit var server: ControllerFixture; lateinit var client: DeviceClient
    @Before fun setup() { server=ControllerFixture(); client=DeviceClient(server.address); runBlocking { client.request(0) } }
    @After fun teardown() { server.close() }
    @Test fun wifiScanPasswordValidationAndConnection() {
        ui.setContent { MaterialTheme { WifiSheet(client) {} } }
        ui.waitUntil(5000) { ui.onAllNodesWithText("Home network").fetchSemanticsNodes().isNotEmpty() }; ui.onNodeWithText("Home network").performClick()
        ui.onNodeWithText("Connect to Wi-Fi").assertIsNotEnabled()
        ui.onNodeWithTag("wifi-password").performTextInput("short")
        ui.onNodeWithText("Connect to Wi-Fi").assertIsNotEnabled()
        ui.onNodeWithTag("wifi-password").performTextReplacement("test-password")
        ui.onNodeWithText("Connect to Wi-Fi").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("You’re connected").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("test-password",server.wifiPassword)
    }
    @Test fun openNetworksSendEmptyPassword() {
        ui.setContent { MaterialTheme { WifiSheet(client) {} } }
        ui.waitUntil(5000) { ui.onAllNodesWithText("Guest").fetchSemanticsNodes().isNotEmpty() }; ui.onNodeWithText("Guest").performClick()
        ui.onNodeWithTag("wifi-password").assertDoesNotExist()
        // This sheet changes height when an open network is selected. Invoke its
        // accessible click action so the test does not tap a moving window.
        ui.onNodeWithText("Connect to Wi-Fi").assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        try { ui.waitUntil(10000) { ui.onAllNodesWithText("You’re connected").fetchSemanticsNodes().isNotEmpty() } }
        catch(failure: Throwable) { throw AssertionError("Open network: sent=${server.wifiPassword != null}, server=${server.ssid}, client=${client.network.text(11)}, ip=${client.state?.ip}, error=${client.error}\n${ui.onAllNodes(isRoot()).onLast().printToString()}", failure) }
        assertEquals("",server.wifiPassword)
    }
    @Test fun matterQrAndCopyAreAvailable() {
        ui.setContent { MaterialTheme { MatterSheet(client) {} } }
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("Matter pairing QR code").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("3497-011-2332").assertIsDisplayed()
        ui.onNodeWithText("Copy setup code").performClick(); ui.onNodeWithText("Copied").assertIsDisplayed()
    }
    @Test fun otaWaitsForNewBuildEvenAfterDownloadCompletes() {
        ui.setContent { MaterialTheme { UpdateSheet(client,36) {} } }
        ui.onNodeWithText("Install").performClick()
        ui.waitUntil(12000) { ui.onAllNodesWithText("100% downloaded").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Done").assertDoesNotExist(); assertTrue(server.installRequested)
        server.installed=true
        ui.waitUntil(12000) { ui.onAllNodesWithText("Update installed").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Done").assertIsDisplayed()
    }
    @Test fun wifiDisableRequiresConfirmationAndPersistsOnlyOnSave() {
        val discovery=Discovery(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
        ui.setContent { MaterialTheme { SettingsSheet(client,discovery,{}, {}) } }
        ui.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Enable Wi-Fi"))
        ui.onNodeWithContentDescription("Enable Wi-Fi").performClick()
        ui.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Enable Matter"))
        ui.onNodeWithContentDescription("Enable Matter").performClick()
        ui.onNodeWithText("Save settings").performClick()
        ui.onNodeWithText("Disable Wi-Fi?").assertIsDisplayed()
        assertTrue(client.state!!.wifiEnabled)
        ui.onNodeWithText("Cancel",useUnmergedTree=true).performClick()
        assertTrue(client.state!!.wifiEnabled)
        ui.onNodeWithText("Save settings").performClick()
        ui.onNodeWithText("Disable Wi-Fi",useUnmergedTree=true).performClick()
        ui.waitUntil(5000) { client.state?.wifiEnabled==false }
        assertFalse(client.state!!.matterEnabled)
    }
    @Test fun transportDoesNotReplayMutationsOrAcceptChangedIdentity() {
        server.failMutation=true
        val before=server.posts
        runBlocking { assertThrows(Exception::class.java) { runBlocking { client.request(15,Proto().uint(10,0)) } } }
        // HttpURLConnection can itself replay POST after an EOF: the transport must disable that.
        assertEquals(before+1,server.posts)
        server.failMutation=false; server.identity="another-controller"
        assertThrows(IllegalStateException::class.java) { runBlocking { client.request(0) } }
        assertEquals("fixture-device",client.state!!.deviceId)
    }
    @Test fun malformedLibraryPaginationIsRejected() {
        server.malformedPagination=true
        assertThrows(IllegalStateException::class.java) { runBlocking { client.library(0) } }
    }
}
