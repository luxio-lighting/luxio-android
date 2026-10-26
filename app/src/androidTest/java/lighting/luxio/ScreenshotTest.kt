package lighting.luxio

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class ScreenshotTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    @Test fun captureVirtualDeviceDesign() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("luxio", Context.MODE_PRIVATE).edit().clear().commit()
        val discovery = Discovery(context)
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        ui.runOnUiThread { ui.activity.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)) }
        ui.setContent { MaterialTheme(colorScheme = LuxioColors) { Surface(Modifier.fillMaxSize()) { Luxio(discovery) } } }
        ui.onNodeWithTag("add-device").performClick()
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        File(directory,"02-add-luxio.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        ui.onNodeWithTag("add-virtual").performScrollTo().performClick()
        ui.onNodeWithTag("device-title").assertIsDisplayed()
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        File(directory,"03-gradient.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        runBlocking {
            val client = discovery.clients["luxio-simulator"]!!
            for(item in colorPresets.take(4) + gradientPresets.take(3) + builtInAnimations.take(3)) client.request(listOf(27,20,7)[item.kind], Proto().data(listOf(14,11,7)[item.kind], item.copy(id = "", name = "My ${item.name}").proto().bytes.toByteArray()))
        }
        for((mode, filename) in listOf(0 to "10-saved-colors.png", 1 to "11-saved-gradients.png", 2 to "12-saved-animations.png")) {
            ui.onNodeWithTag("mode-$mode").performClick()
            ui.onNodeWithTag("device-scroll").performScrollToNode(hasText("Saved"))
            ui.waitForIdle(); android.os.SystemClock.sleep(500)
            File(directory, filename).outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        ui.onNodeWithTag("device-scroll").performScrollToIndex(0)
        ui.onNodeWithTag("mode-2").performClick()
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        File(directory,"04-animations.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        ui.onNodeWithTag("preset-Rainbow").performClick()
        ui.onNodeWithContentDescription("Settings for Rainbow").performClick()
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        File(directory,"05-animation-controls.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithContentDescription("Device settings").performClick()
        ui.waitForIdle()
        android.os.SystemClock.sleep(500)
        File(directory,"06-settings.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun captureNetworkAndUpdateSheetsThroughRealNavigation() {
        ControllerFixture().use { server ->
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("luxio",Context.MODE_PRIVATE).edit().clear().commit()
            val discovery=Discovery(context)
            ui.runOnUiThread { ui.activity.enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)) }
            ui.setContent { MaterialTheme(colorScheme=LuxioColors) { Surface(Modifier.fillMaxSize()) { Luxio(discovery) } } }
            ui.onNodeWithTag("add-device").performClick()
            ui.onNodeWithTag("device-address").performTextInput(server.address)
            ui.onNodeWithText("Connect",useUnmergedTree=true).performScrollTo().performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithTag("device-title").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("Device settings").performClick()
            ui.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Configure Wi-Fi"))
            ui.onNodeWithText("Configure Wi-Fi").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("Home network").fetchSemanticsNodes().isNotEmpty() }
            ui.waitForIdle()
        android.os.SystemClock.sleep(500)
            val directory=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
            File(directory,"07-wifi.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithContentDescription("Close",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Check for updates"))
            ui.onNodeWithText("Check for updates").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("Update available").fetchSemanticsNodes().isNotEmpty() }
            ui.waitForIdle()
        android.os.SystemClock.sleep(500)
            File(directory,"08-update.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            ui.onNodeWithText("Install").performClick()
            ui.waitUntil(12000) { ui.onAllNodesWithText("100% downloaded").fetchSemanticsNodes().isNotEmpty() }
            ui.waitForIdle()
        android.os.SystemClock.sleep(500)
            File(directory,"09-update-progress.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
            server.installed=true
            ui.waitUntil(12000) { ui.onAllNodesWithText("Update installed").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
