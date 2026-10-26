package lighting.luxio

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch

val Accent = Color(0xFF267AFF)
val Ink = Color(0xFF18181C)
val Panel = Color(0xFF403439)
val Muted = Color(0xFFB0A6AA)
val Backdrop = Brush.verticalGradient(listOf(Color(0xFF301B21), Ink))
val LuxioColors = darkColorScheme(primary = Accent, onPrimary = Color.White, primaryContainer = Color(0xFF244878), secondary = Accent, onSurface = Color.White, onBackground = Color.White, background = Ink, surface = Ink, surfaceContainer = Panel, surfaceContainerHigh = Color(0xFF352F33), outline = Color(0xFF756C70))
class MainActivity : ComponentActivity() {
    lateinit var discovery: Discovery
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        discovery = Discovery(applicationContext)
        setContent {
            MaterialTheme(colorScheme = LuxioColors) {
                Surface(Modifier.fillMaxSize()) { Luxio(discovery) }
            }
        }
    }
    override fun onStart() { super.onStart(); discovery.start() }
    override fun onStop() { discovery.stop(); super.onStop() }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Luxio(discovery: Discovery) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { discovery.startBluetooth() }
    val selected = discovery.clients[selectedId]
    BackHandler(selected != null) { selectedId = null }
    if(selected != null) DeviceScreen(selected, discovery, onBack = { selectedId = null })
    else PullToRefreshBox(isRefreshing = discovery.searching, onRefresh = { scope.launch { discovery.refresh() } }, modifier = Modifier.fillMaxSize().background(Backdrop).safeDrawingPadding()) {
        LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Luxio", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { add = true }, modifier = Modifier.testTag("add-device")) { Icon(Icons.Rounded.Add, "Add Luxio", tint = Color.White) }
                }
            }
            if(discovery.clients.isEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.Lightbulb, null, tint = Accent, modifier = Modifier.size(36.dp))
                        Text("No Luxio Devices Found", style = MaterialTheme.typography.titleLarge)
                        Text("Power on Luxio nearby and enable Bluetooth, or connect it to the same Wi-Fi.", color = Muted)
                        Button(onClick = { add = true }) { Text("Add Luxio"); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
                    }
                }
            }
            items(discovery.clients.entries.sortedBy { it.value.state?.name?.lowercase() }, key = { it.key }) { (id, client) ->
                HomeLightCard(client, onOpen = { selectedId = id })
            }
            if(discovery.bluetoothStatus != null) item {
                TextButton(onClick = { permission.launch(if(Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }) {
                    Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(discovery.bluetoothStatus!!, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            discovery.discoveryError?.let { error -> item { Text(error, style = MaterialTheme.typography.bodySmall, color = Muted) } }
        }
    }
    if(add) AddDeviceSheet(discovery, onDismiss = { add = false }, onConnected = { selectedId = it.state!!.deviceId; add = false })
}
@Composable fun HomeLightCard(client: DeviceClient, onOpen: () -> Unit) {
    val state = client.state ?: return
    val scope = rememberCoroutineScope(); val view = LocalView.current
    val preview = when(state.mode) { 1 -> state.gradient.colors; 2 -> state.preview; else -> listOf(state.color) }
    val darkInk = state.on && preview.isNotEmpty() && preview.all { val c = it.display(); c.red * .2126f + c.green * .7152f + c.blue * .0722f > .68f }
    val ink = if(darkInk) Color.Black.copy(alpha = .85f) else Color.White
    Box(Modifier.fillMaxWidth().animateContentSize().height(if(state.on) 117.dp else 74.dp).clip(RoundedCornerShape(15.dp))) {
        LightPreview(client, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .03f), Color.Black.copy(alpha = if(darkInk) .22f else .48f)))))
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if(client.simulator == null) Icon(if(client.usingBluetooth) Icons.Rounded.Bluetooth else Icons.Rounded.Wifi, client.connectionName, Modifier.size(22.dp), tint = ink.copy(alpha = .8f))
                    Text(state.name, color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                }
                Switch(checked = state.on, onCheckedChange = { on ->
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    scope.launch { try { client.request(15, Proto().uint(10, if(on) 1 else 0)) } catch(e: Exception) { client.error = e.message } }
                }, modifier = Modifier.semantics { contentDescription = "${state.name} power" }, colors = SwitchDefaults.colors(checkedTrackColor = Color.Black.copy(alpha = .28f), checkedThumbColor = Color.White))
            }
            if(state.on) BrightnessControl(client, Modifier.padding(horizontal = 14.dp))
        }
    }
}
