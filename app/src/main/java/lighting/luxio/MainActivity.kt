package lighting.luxio

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

private val lilac = Color(0xFFB8A1FA)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = lilac, background = Color(0xFF101116), surface = Color(0xFF191A21))) { Surface(Modifier.fillMaxSize()) { Luxio(this) } } }
    }
}
@Composable fun Luxio(context: Context) {
    val lights = remember { mutableStateListOf<Light>() }
    var selected by remember { mutableStateOf<Light?>(null) }
    var manual by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) { scope.launch { lights.removeAll { it.name == service.serviceName } } }
            override fun onServiceFound(service: NsdServiceInfo) {
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: host
                        val address = (if (host.contains(':')) "[$host]" else host) + ":${info.port}"
                        scope.launch { lights.removeAll { it.id == id }; lights.add(Light(id, info.serviceName, address)) }
                    }
                })
            }
        }
        manager.discoverServices("_luxio._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        onDispose { manager.stopServiceDiscovery(listener) }
    }
    LaunchedEffect(refreshing) {
        try {
            val discovered = withContext(Dispatchers.IO) {
                val connection = URI("https://api.luxio.lighting/devices").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 7000; connection.readTimeout = 7000
                try { check(connection.responseCode == 200) { "Cloud discovery unavailable. Local discovery is still running." }; val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() }); (0 until array.length()).map { val d = array.getJSONObject(it); val ip = d.getString("lan_ip"); Light(d.getString("deviceId"), d.getString("name"), if (ip.contains(':')) "[$ip]" else ip) } } finally { connection.disconnect() }
            }
            discovered.forEach { light -> if (lights.none { it.id == light.id }) lights.add(light) }; error = ""
        } catch (e: Exception) { error = e.message ?: "Discovery unavailable" }
    }
    if (selected != null) { LightScreen(selected!!, onBack = { selected = null }); return }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("luxio", fontSize = 38.sp, fontWeight = FontWeight.Bold); TextButton(onClick = { refreshing++ }) { Text("Refresh") } }
        Text("A little light.\nA whole different feeling.", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text("Your lights, right here.", color = Color.Gray)
        if (lights.isEmpty()) Card { Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("Your first light starts here.", style = MaterialTheme.typography.titleLarge); Text("Set up your ESP32 over USB, then connect your phone to the same network.", color = Color.Gray); TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://setup.luxio.lighting"))) }) { Text("Open device setup ↗") } } }
        lights.forEach { light -> Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(lilac.copy(alpha = .22f), Color(0xFF191A21))), RoundedCornerShape(24.dp)).clickable { selected = light }.padding(26.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(light.name, fontWeight = FontWeight.Bold, fontSize = 19.sp); Spacer(Modifier.height(8.dp)); Text("On your network", color = Color.Gray, fontSize = 12.sp) }; Text("→", color = lilac) } }
        OutlinedTextField(manual, { manual = it }, label = { Text("Device IP or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { if (manual.isNotBlank()) selected = Light(manual, "Luxio", manual.trim()) }) { Text("Connect by address") }
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}
@Composable fun LightScreen(light: Light, onBack: () -> Unit) {
    val client = remember(light.id) { DeviceClient(light.address) }
    var state by remember { mutableStateOf<LightState?>(null) }
    var brightness by remember { mutableFloatStateOf(180f) }
    var error by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("#AA77FF") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(light.id) { try { state = LightState.from(client.request(0).message(3)); brightness = state!!.brightness.toFloat() } catch (e: Exception) { error = e.message ?: "Connection failed" } }
    val scenes = listOf("Golden hour" to 0xFFFF6E19, "Blush" to 0xFFFF418C, "Iris" to 0xFF7D2DFF, "Ocean" to 0xFF0A5AFF, "Mint" to 0xFF1EE696, "Moonlight" to 0xFF7DAFFF, "Terracotta" to 0xFFDC461E, "Lime" to 0xFF7DFF19)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = onBack) { Text("‹ Your lights") }; TextButton(onClick = { settings = true }) { Text("Settings") } }
        Box(Modifier.fillMaxWidth().height(175.dp).background(Brush.radialGradient(listOf(lilac.copy(alpha = .4f), Color.Transparent))), contentAlignment = Alignment.Center) { Text("◈", fontSize = 90.sp, color = lilac) }
        Text(state?.name ?: light.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Brightness"); Text("${(brightness / 255 * 100).toInt()}%", color = Color.Gray) }
        Slider(brightness, { brightness = it }, valueRange = 0f..255f, onValueChangeFinished = { scope.launch { try { state = LightState.from(client.request(2, Proto().uint(4, brightness.toLong())).message(3)); error = "" } catch (e: Exception) { error = e.message ?: "Failed" } } })
        OutlinedTextField(custom, { custom = it }, label = { Text("Your color · #RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { scope.launch { try { require(custom.matches(Regex("#[a-fA-F0-9]{6}"))) { "Enter a color such as #AA77FF" }; val n = custom.drop(1).toInt(16); state = LightState.from(client.color(listOf(n shr 16, n shr 8 and 255, n and 255, 0, 0, 0)).message(3)); error = "" } catch (e: Exception) { error = e.message ?: "Failed" } } }) { Text("Apply color") }
        if (state?.strip != "WS2812") Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("Warm", "Cool").forEach { label -> OutlinedButton(onClick = { scope.launch { try { state = LightState.from(client.color(if (state?.strip == "WS2814") listOf(0,0,0,255,0,0) else if (label == "Warm") listOf(0,0,0,0,255,0) else listOf(0,0,0,0,0,255)).message(3)) } catch (e: Exception) { error = e.message ?: "Failed" } } }) { Text(label) } } }
        Text("Set the mood", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        scenes.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { (name, hex) -> Box(Modifier.weight(1f).height(112.dp).background(Brush.linearGradient(listOf(Color(hex).copy(alpha = .7f), Color(hex).copy(alpha = .12f))), RoundedCornerShape(20.dp)).clickable { scope.launch { try { state = LightState.from(client.color(listOf((hex shr 16 and 255).toInt(), (hex shr 8 and 255).toInt(), (hex and 255).toInt(),0,0,0)).message(3)); error = "" } catch (e: Exception) { error = e.message ?: "Failed" } } }.padding(18.dp), contentAlignment = Alignment.BottomStart) { Text(name, fontWeight = FontWeight.SemiBold) } } } }
        Text("Animations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        listOf("Aurora", "Ember", "Tidal").forEach { name -> OutlinedButton(onClick = { scope.launch { try {
            val rgb = if (name == "Ember") listOf(255,70,12) else if (name == "Tidal") listOf(15,140,255) else listOf(130,70,255)
            val source = "function setup() end\nfunction loop()\n for i=0,luxio_get_pixel_count()-1 do\n local t=0.35+0.65*(math.sin(i/8+luxio_millis()/1800)+1)/2\n luxio_set_pixel_color(i,math.floor(${rgb[0]}*t),math.floor(${rgb[1]}*t),math.floor(${rgb[2]}*t))\n end\nend"
            val existing = client.request(5).fields[4]?.map { Proto.decode(it) }?.find { it.text(2) == "Luxio · $name" }?.text(1)
            val script = Proto().text(1, existing ?: UUID.randomUUID().toString()).text(2, "Luxio · $name").text(3, source)
            val saved = client.request(if (existing == null) 7 else 8, Proto().data(7, script.bytes.toByteArray()))
            state = LightState.from(client.request(10, Proto().text(8, saved.message(5).text(1))).message(3)); error = ""
        } catch (e: Exception) { error = e.message ?: "Failed" } } }, modifier = Modifier.fillMaxWidth()) { Text("$name  ▷") } }
        if (!state?.animation.isNullOrBlank()) TextButton(onClick = { scope.launch { try { state = LightState.from(client.request(11).message(3)) } catch (e: Exception) { error = e.message ?: "Failed" } } }) { Text("Stop animation") }
        TextButton(onClick = { scope.launch { try { pairing = client.request(12).text(6) } catch (e: Exception) { error = e.message ?: "Failed" } } }) { Text("Connect to Matter") }
        if (pairing.isNotBlank()) Text(pairing, fontSize = 30.sp, color = lilac)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    if (settings && state != null) {
        var name by remember { mutableStateOf(state!!.name) }; var strip by remember { mutableStateOf(state!!.strip) }; var count by remember { mutableStateOf(state!!.count.toString()) }; var gpio by remember { mutableStateOf(state!!.gpio.toString()) }
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Light settings") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Name") }); listOf("WS2805","WS2814","WS2812").forEach { type -> Row(Modifier.clickable { strip = type }, verticalAlignment = Alignment.CenterVertically) { RadioButton(strip == type, { strip = type }); Text(type) } }; OutlinedTextField(count, { count = it }, label = { Text("Controllers") }); OutlinedTextField(gpio, { gpio = it }, label = { Text("GPIO") }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { scope.launch { try { val n = count.toInt(); val pin = gpio.toInt(); require(n in 1..1024 && pin in 0..54 && name.isNotBlank()); val config = Proto().text(1,name).text(2,strip).uint(3,n.toLong()).uint(4,pin.toLong()).uint(5,state!!.channel.toLong()); state = LightState.from(client.request(3,Proto().data(5,config.bytes.toByteArray())).message(3)); settings = false; error = "" } catch (e: Exception) { error = e.message ?: "Invalid settings" } } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { settings = false }) { Text("Cancel") } })
    }
}
