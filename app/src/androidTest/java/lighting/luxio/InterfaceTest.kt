package lighting.luxio

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking

class InterfaceTest {
    @get:Rule val ui=createComposeRule()
    lateinit var discovery: Discovery
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("luxio",Context.MODE_PRIVATE).edit().clear().commit()
        discovery=Discovery(context)
        ui.setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Accent,background=Ink,surface=Ink,surfaceContainer=Panel)) { Surface { Luxio(discovery) } } }
    }
    private fun openVirtual() {
        ui.onNodeWithTag("add-device").performClick()
        ui.onNodeWithTag("add-virtual").performScrollTo().performClick()
        ui.onNodeWithTag("device-title").assertTextEquals("Luxio Studio")
    }
    @Test fun addSheetContainsGuideManualConnectionAndVirtualDevice() {
        ui.onNodeWithTag("add-device").performClick()
        ui.onNodeWithText("Create your own Luxio").assertIsDisplayed()
        ui.onNodeWithText("View the guide").assertIsDisplayed()
        ui.onNodeWithTag("device-address").performTextInput("not a valid address")
        ui.onNodeWithText("Connect",useUnmergedTree=true).performScrollTo().performClick()
        ui.waitUntil(5000) { ui.onAllNodesWithText("Illegal character",substring=true).fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText("Enter an IP",substring=true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("add-virtual").performScrollTo().performClick()
        ui.onNodeWithTag("device-title").assertIsDisplayed()
    }
    @Test fun colorEditorAppliesWithoutBlockingOverlayAndSavesLibrary() {
        openVirtual(); ui.onNodeWithTag("mode-0").performClick()
        ui.onNodeWithContentDescription("Color wheel").performTouchInput { click(centerRight) }
        ui.onNodeWithContentDescription("Color wheel").assertIsDisplayed()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("save-preset"))
        ui.onNodeWithTag("save-preset").performClick()
        ui.onNodeWithText("Name").performTextInput("My coral")
        ui.onNode(hasText("Save") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-My coral"))
        ui.onNodeWithTag("preset-My coral").performTouchInput { longClick() }
        ui.onNodeWithText("Rename").performClick()
        ui.onNodeWithText("Name").performTextReplacement("Evening")
        ui.onNode(hasText("Save") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        ui.onNodeWithText("Evening").assertIsDisplayed()
        ui.onNodeWithTag("preset-Evening").performTouchInput { longClick() }
        ui.onNodeWithText("Delete").performClick()
        ui.onNodeWithText("Delete",useUnmergedTree=true).performClick()
        ui.onNodeWithText("No saved colors yet.").assertIsDisplayed()
    }
    @Test fun gradientStopsAndSavedGradientWork() {
        openVirtual()
        ui.onNodeWithContentDescription("Add gradient stop").performScrollTo().performClick()
        ui.onNodeWithTag("gradient-stop-3").assertExists()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("save-preset"))
        ui.onNodeWithTag("save-preset").performClick()
        ui.onNodeWithText("Name").performTextInput("Afterglow")
        ui.onNode(hasText("Save") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-Afterglow"))
        ui.onNodeWithTag("preset-Afterglow").performClick()
        assertEquals(1,discovery.clients["luxio-simulator"]!!.state!!.mode)
    }
    @Test fun draggingAnotherGradientHandleOnlyChangesThatStop() {
        openVirtual()
        val client = discovery.clients["luxio-simulator"]!!
        val before = client.state!!.gradient.colors.toList()
        val hsv = FloatArray(3)
        val target = before[2].channels
        android.graphics.Color.RGBToHSV(target[0], target[1], target[2], hsv)
        val angle = (hsv[0] - 120) / 180 * Math.PI
        val inset = 17 * discovery.context.resources.displayMetrics.density
        ui.onNodeWithContentDescription("Color wheel").performTouchInput {
            val radius = width / 2f - inset
            swipe(androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * hsv[1] * radius, center.y + kotlin.math.sin(angle).toFloat() * hsv[1] * radius), center, 300)
        }
        ui.waitUntil(5000) { client.state!!.gradient.colors[2] != before[2] }
        assertEquals(before[0], client.state!!.gradient.colors[0])
        assertEquals(before[1], client.state!!.gradient.colors[1])
        ui.onNodeWithTag("gradient-stop-2").assertIsSelected()
    }
    @Test fun animationsRemainSelectedWithoutSavedIdsAndParametersPersist() {
        openVirtual(); ui.onNodeWithText("Animation").performClick()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-Rainbow"))
        ui.onNodeWithTag("preset-Rainbow").performClick()
        ui.onNodeWithContentDescription("Settings for Rainbow").performClick()
        ui.onNodeWithText("Period").performTextReplacement("12")
        ui.onNodeWithContentDescription("Apply Period").performClick()
        ui.waitUntil { discovery.clients["luxio-simulator"]!!.state!!.parameters.first { it.name=="Period" }.value==12.0 }
        ui.onNodeWithContentDescription("Close").performClick()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-Tidal")); ui.onNodeWithTag("preset-Tidal").performClick()
        ui.waitUntil { discovery.clients["luxio-simulator"]!!.state!!.animationName=="Tidal" }
        assertEquals(2,discovery.clients["luxio-simulator"]!!.state!!.mode)
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-Rainbow")); ui.onNodeWithTag("preset-Rainbow").performClick()
        ui.waitUntil { discovery.clients["luxio-simulator"]!!.state!!.animationName=="Rainbow" }
        assertEquals(12.0,discovery.clients["luxio-simulator"]!!.state!!.parameters.first { it.name=="Period" }.value,0.0)
    }
    @Test fun whiteSelectorUsesDedicatedChannelAndRestoresRgb() {
        openVirtual()
        ui.onNodeWithContentDescription("Device settings").performClick()
        ui.onNodeWithText("Strip type").performScrollTo().performClick()
        ui.onNodeWithText("SK6812").performClick()
        ui.onNodeWithText("Save settings").performClick()
        ui.onNodeWithTag("mode-0").performClick()
        val client = discovery.clients["luxio-simulator"]!!
        val previous = client.state!!.color
        ui.onNodeWithContentDescription("Color wheel").performTouchInput { click(centerRight) }
        ui.waitUntil(5000) { client.state!!.mode == 0 && client.state!!.color != previous }
        val rgb = client.state!!.color
        ui.onNodeWithContentDescription("White channel selector").performClick()
        ui.waitUntil(5000) { client.state!!.color.channels[3] == 255 }
        assertEquals(listOf(0,0,0), client.state!!.color.channels.take(3))
        ui.onNodeWithContentDescription("Color wheel selector").performClick()
        ui.waitUntil(5000) { client.state!!.color == rgb }
    }
    @Test fun virtualDeviceSupportsWarmAndCoolWhite() {
        openVirtual(); ui.onNodeWithTag("mode-0").performClick()
        val client = discovery.clients["luxio-simulator"]!!
        ui.onNodeWithContentDescription("White temperature selector").performClick()
        ui.waitUntil(5000) { client.state!!.color.channels[4] > 0 && client.state!!.color.channels[5] > 0 }
        ui.onNodeWithContentDescription("White temperature wheel").performTouchInput { click(topCenter) }
        ui.waitUntil(5000) { client.state!!.color.channels == listOf(0,0,0,0,255,0) }
        ui.onNodeWithContentDescription("White temperature wheel").performTouchInput { click(bottomCenter) }
        ui.waitUntil(5000) { client.state!!.color.channels[5] >= 254 && client.state!!.color.channels[4] <= 1 }
        assertEquals(listOf(0,0,0,0), client.state!!.color.channels.take(4))
    }
    @Test fun animationPresetsSaveAndRenameInTheInlineLibrary() {
        openVirtual(); ui.onNodeWithTag("mode-2").performClick()
        ui.onNodeWithTag("preset-Rainbow").performTouchInput { longClick() }
        ui.onNodeWithText("Save to device").performClick()
        ui.waitUntil(5000) { runBlocking { discovery.clients["luxio-simulator"]!!.library(2).isNotEmpty() } }
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasText("Saved"))
        ui.onAllNodesWithTag("preset-Rainbow").onLast().performTouchInput { longClick() }
        ui.onNodeWithText("Rename").performClick()
        ui.onNodeWithText("Name").performTextReplacement("My animation")
        ui.onNode(hasText("Save") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        ui.onNodeWithTag("device-scroll").performScrollToNode(hasTestTag("preset-My animation"))
        ui.onNodeWithTag("preset-My animation").performClick()
        ui.waitUntil(5000) { discovery.clients["luxio-simulator"]!!.state!!.animationName == "My animation" }
        val detail = runBlocking { val client = discovery.clients["luxio-simulator"]!!; client.detail(client.library(2).single()) }
        assertEquals(builtInAnimations.first().source, detail.source)
    }
    @Test fun rgbHidesWhiteControlAndHeaderNameStaysVisible() {
        openVirtual(); ui.onNodeWithContentDescription("Device settings").performClick()
        ui.onNodeWithText("Connection").assertIsDisplayed()
        ui.onNodeWithText("Strip type").performScrollTo().performClick()
        ui.onNodeWithText("WS2812").performClick()
        ui.onNodeWithText("Save settings").performClick()
        ui.onNodeWithContentDescription("White channel selector").assertDoesNotExist()
        ui.onNodeWithContentDescription("White temperature selector").assertDoesNotExist()
        ui.onNodeWithTag("device-title").assertTextEquals("Luxio Studio")
        assertEquals("WS2812",discovery.clients["luxio-simulator"]!!.state!!.strip)
    }
    @Test fun relaySettingsAnd1024LedsRoundTrip() {
        openVirtual(); ui.onNodeWithContentDescription("Device settings").performClick()
        ui.onNodeWithTag("setting-LED count").performScrollTo().performTextReplacement("1024")
        ui.onNodeWithText("Enable Relay").performScrollTo()
        ui.onNodeWithContentDescription("Enable Relay").performClick()
        ui.onNodeWithTag("setting-Relay GPIO").performScrollTo().performTextReplacement("18")
        ui.onNodeWithText("Save settings").performClick()
        val state=discovery.clients["luxio-simulator"]!!.state!!
        assertEquals(1024,state.count); assertTrue(state.relayEnabled); assertEquals(18,state.relayGpio)
        val restored=Discovery(discovery.context).clients["luxio-simulator"]!!.state!!
        assertEquals(1024,restored.count); assertTrue(restored.relayEnabled)
    }
}
