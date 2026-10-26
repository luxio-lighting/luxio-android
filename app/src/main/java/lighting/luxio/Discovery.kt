package lighting.luxio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ParcelUuid
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

class Discovery(val context: Context) {
    val clients = mutableStateMapOf<String, DeviceClient>()
    val prefs = context.getSharedPreferences("luxio", Context.MODE_PRIVATE)
    var searching by mutableStateOf(false)
    var bluetoothStatus by mutableStateOf<String?>(null)
    var discoveryError by mutableStateOf<String?>(null)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null
    private var scanner: BluetoothLeScanner? = null
    private val connections = mutableMapOf<String, BluetoothConnection>()
    private val checks = mutableSetOf<String>()
    private val resolveMutex = Mutex()
    init {
        if(prefs.getBoolean("simulator", false)) addSimulator()
        prefs.getStringSet("addresses", emptySet())!!.forEach { address ->
            val client = DeviceClient(address)
            runCatching { prefs.getString("state:$address", null)?.let { client.state = LightState.from(Proto.decode(Base64.getDecoder().decode(it))) } }
            client.state?.let { clients[it.deviceId] = client; client.error = "Reconnecting…" }
        }
    }
    fun addSimulator(): DeviceClient {
        clients["luxio-simulator"]?.let { return it }
        val saved = runCatching { prefs.getString("simulatorData", null)?.let { Base64.getDecoder().decode(it) } }.getOrNull()
        val simulator = SimulatorDevice(saved) { prefs.edit().putString("simulatorData", Base64.getEncoder().encodeToString(it)).apply() }
        val client = DeviceClient("simulator", simulator); client.state = simulator.state
        clients["luxio-simulator"] = client; prefs.edit().putBoolean("simulator", true).apply(); return client
    }
    fun removeSimulator() { clients.remove("luxio-simulator"); prefs.edit().putBoolean("simulator", false).apply() }
    suspend fun addAddress(input: String): DeviceClient {
        var address = input.trim()
        if(!address.contains("://")) address = "http://$address"
        val uri = try { URI(address) } catch(_: java.net.URISyntaxException) { throw IllegalArgumentException("Enter an IP address or hostname, for example 192.168.1.42") }
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path in listOf("", "/")) { "Enter an IP address or hostname, for example 192.168.1.42" }
        val normalized = address.trimEnd('/')
        val known = clients.values.firstOrNull { it.simulator == null && it.address == normalized }
        if(known != null) { known.request(0, timeout = 5000); return known }
        val client = DeviceClient(normalized); client.request(0, timeout = 5000)
        val state = checkNotNull(client.state)
        val existing = clients[state.deviceId]
        val result = if(existing != null && existing.simulator == null) { existing.address = client.address; existing.usingBluetooth = false; existing.request(0, timeout = 5000); existing.error = null; existing } else client
        clients[state.deviceId] = result
        val addresses = prefs.getStringSet("addresses", emptySet())!!.toMutableSet(); addresses.add(result.address)
        prefs.edit().putStringSet("addresses", addresses).putString("state:${result.address}", Base64.getEncoder().encodeToString(state.proto().bytes.toByteArray())).apply()
        return result
    }
    fun start() {
        if(listener != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val browsing = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, error: Int) { discoveryError = "Local discovery is unavailable. You can still connect by IP address." }
            override fun onStopDiscoveryFailed(type: String, error: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                scope.launch { resolveMutex.withLock {
                    val completion = CompletableDeferred<Unit>()
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, error: Int) { completion.complete(Unit) }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress?.substringBefore('%')
                            if(host != null) scope.launch { runCatching { addAddress("${if(host.contains(':')) "[$host]" else host}:${info.port}") } }
                            completion.complete(Unit)
                        }
                    })
                    withTimeoutOrNull(6000) { completion.await() }
                } }
            }
        }
        listener = browsing
        runCatching { nsd.discoverServices("_luxio._tcp.", NsdManager.PROTOCOL_DNS_SD, browsing) }
        startBluetooth()
        scope.launch {
            refresh(true)
            while(isActive) { delay(4000); refresh(false) }
        }
    }
    suspend fun refresh(cloud: Boolean = true) {
        if(cloud) { searching = true; discoveryError = null }
        try {
            coroutineScope {
                clients.values.toList().map { client -> launch {
                    try {
                        client.request(0, timeout = 2500)
                        if(client.simulator == null && !client.usingBluetooth) client.state?.let { prefs.edit().putString("state:${client.address}", Base64.getEncoder().encodeToString(it.proto().bytes.toByteArray())).apply() }
                    } catch(e: Exception) { if(e is CancellationException) throw e; client.error = e.message ?: "Device unavailable" }
                } }.joinAll()
            }
            if(cloud) {
                val addresses = withContext(Dispatchers.IO) {
                    val connection = URI("https://api.luxio.lighting/devices").toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    try {
                        check(connection.responseCode == 200)
                        val bytes = connection.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                            while(output.size() <= 262144) { val count = input.read(buffer, 0, minOf(buffer.size, 262145 - output.size())); if(count < 0) break; output.write(buffer, 0, count) }
                            output.toByteArray()
                        }; check(bytes.size <= 262144)
                        val array = JSONArray(bytes.toString(Charsets.UTF_8))
                        (0 until array.length()).map { array.getJSONObject(it).getString("lan_ip") }
                    } finally { connection.disconnect() }
                }
                coroutineScope { addresses.distinct().take(64).map { ip -> launch { runCatching { addAddress(if(ip.contains(':')) "[$ip]" else ip) } } }.joinAll() }
            }
        } catch(e: Exception) { if(e is CancellationException) throw e; if(cloud) discoveryError = "Cloud discovery is unavailable. Nearby discovery is still running." }
        finally { if(cloud) searching = false }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) { bluetoothStatus = "Bluetooth scan unavailable ($errorCode). Wi-Fi discovery is still running." }
        @SuppressLint("MissingPermission") override fun onScanResult(callbackType: Int, result: ScanResult) {
            scope.launch {
                val address = result.device.address
                if(!checks.add(address)) return@launch
                try {
                    val connection = connections.getOrPut(address) { BluetoothConnection(context, result.device) }
                    val client = DeviceClient("ble://$address"); client.bluetooth = connection
                    client.request(0)
                    val state = checkNotNull(client.state)
                    val existing = clients[state.deviceId]
                    if(existing != null) existing.bluetooth = connection else clients[state.deviceId] = client
                    bluetoothStatus = null
                } catch(e: Exception) { if(e is CancellationException) throw e; bluetoothStatus = e.message }
                finally { delay(15000); checks.remove(address) }
            }
        }
    }
    @SuppressLint("MissingPermission") fun startBluetooth() {
        if(scanner != null) return
        val granted = if(Build.VERSION.SDK_INT >= 31) context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED else context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if(!granted) { bluetoothStatus = "Allow nearby devices to discover Luxio over Bluetooth."; return }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if(adapter == null || !adapter.isEnabled) { bluetoothStatus = "Turn on Bluetooth to find nearby Luxio devices."; return }
        scanner = adapter.bluetoothLeScanner
        try { scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BluetoothConnection.SERVICE)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback); bluetoothStatus = null }
        catch(e: Exception) { scanner = null; bluetoothStatus = e.message }
    }
    @SuppressLint("MissingPermission") fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        runCatching { scanner?.stopScan(scanCallback) }; scanner = null
        scope.cancel(); connections.values.forEach { it.close() }; checks.clear()
    }
}
