package lighting.luxio

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.*

// Proto3 wire encoding for packages/protocol/luxio.proto; unknown fields are skipped.
class Proto {
    val bytes = ByteArrayOutputStream()
    fun uint(field: Int, value: Long): Proto {
        for (number in listOf((field shl 3).toLong(), value)) {
            var n = number
            while (n > 127) { bytes.write(((n and 127) or 128).toInt()); n = n ushr 7 }
            bytes.write(n.toInt())
        }
        return this
    }
    fun data(field: Int, value: ByteArray): Proto {
        for (number in listOf(((field shl 3) or 2).toLong(), value.size.toLong())) {
            var n = number
            while (n > 127) { bytes.write(((n and 127) or 128).toInt()); n = n ushr 7 }
            bytes.write(n.toInt())
        }
        bytes.write(value); return this
    }
    fun real(field: Int, value: Double): Proto {
        var tag = ((field shl 3) or 1).toLong()
        while (tag > 127) { bytes.write(((tag and 127) or 128).toInt()); tag = tag ushr 7 }
        bytes.write(tag.toInt())
        val bits = value.toBits(); repeat(8) { bytes.write((bits ushr (it * 8)).toInt()) }
        return this
    }
    fun text(field: Int, value: String) = data(field, value.toByteArray())
    companion object {
        fun decode(bytes: ByteArray): Fields {
            var offset = 0
            val result = mutableMapOf<Int, MutableList<ByteArray>>()
            fun varint(): Long {
                var value = 0L
                for (shift in 0..63 step 7) {
                    require(offset < bytes.size) { "Truncated Protobuf message" }
                    val byte = bytes[offset++].toInt() and 255
                    require(shift != 63 || byte <= 1) { "Invalid Protobuf integer" }
                    value = value or ((byte and 127).toLong() shl shift)
                    if (byte and 128 == 0) return value
                }
                error("Invalid Protobuf integer")
            }
            while (offset < bytes.size) {
                val tag = varint().toInt(); val field = tag ushr 3
                require(field > 0) { "Invalid Protobuf field" }
                when (tag and 7) {
                    0 -> { val number = varint(); result.getOrPut(field) { mutableListOf() }.add(ByteArray(8) { (number ushr (it * 8)).toByte() }) }
                    2 -> { val length = varint(); require(length >= 0 && length <= 32768 && length <= bytes.size - offset) { "Invalid Protobuf size" }; result.getOrPut(field) { mutableListOf() }.add(bytes.copyOfRange(offset, offset + length.toInt())); offset += length.toInt() }
                    1 -> { require(offset + 8 <= bytes.size); result.getOrPut(field) { mutableListOf() }.add(bytes.copyOfRange(offset, offset + 8)); offset += 8 }
                    5 -> { require(offset + 4 <= bytes.size); offset += 4 }
                    else -> error("Unsupported Protobuf wire type")
                }
            }
            return Fields(result)
        }
    }
}
class Fields(val fields: Map<Int, List<ByteArray>>) {
    fun text(field: Int) = fields[field]?.firstOrNull()?.toString(Charsets.UTF_8) ?: ""
    fun number(field: Int): Int = fields[field]?.firstOrNull()?.take(4)?.foldIndexed(0) { i, n, byte -> n or ((byte.toInt() and 255) shl (i * 8)) } ?: 0
    fun real(field: Int): Double = Double.fromBits(fields[field]?.firstOrNull()?.foldIndexed(0L) { i, n, byte -> n or ((byte.toLong() and 255) shl (i * 8)) } ?: 0L)
    fun message(field: Int) = Proto.decode(fields[field]?.firstOrNull() ?: byteArrayOf())
}
data class Light(val id: String, val name: String, val address: String)
data class LedColor(val channels: List<Int> = listOf(255, 110, 20, 0, 0, 0)) {
    fun proto() = Proto().apply { channels.forEachIndexed { i, n -> uint(i + 1, n.coerceIn(0, 255).toLong()) } }
    companion object { fun from(f: Fields) = LedColor((1..6).map { f.number(it).coerceIn(0, 255) }) }
}
data class AnimationParameter(val name: String, val boolean: Boolean, val value: Double, val minimum: Double, val maximum: Double, val numberInput: Boolean = false, val defaultValue: Double = value) {
    fun proto() = Proto().text(1, name).uint(2, if (boolean) 1 else 0).real(3, value).real(4, defaultValue).real(5, minimum).real(6, maximum).uint(7, if (numberInput) 1 else 0)
    companion object { fun from(p: Fields) = AnimationParameter(p.text(1), p.number(2) != 0, p.real(3), p.real(5), p.real(6), p.number(7) != 0, p.real(4)) }
}
data class LibraryItem(val id: String = "", val name: String, val kind: Int, val colors: List<LedColor> = emptyList(), val source: String = "", val parameters: List<AnimationParameter> = emptyList(), val metadata: String = "") {
    fun proto() = Proto().text(1, id).text(2, name).apply {
        when (kind) {
            0 -> data(3, (colors.firstOrNull() ?: LedColor()).proto().bytes.toByteArray())
            1 -> { colors.forEach { data(3, it.proto().bytes.toByteArray()) }; text(4, metadata) }
            2 -> { text(3, source); uint(4, 2); parameters.forEach { data(5, it.proto().bytes.toByteArray()) }; colors.forEach { data(6, it.proto().bytes.toByteArray()) } }
        }
    }
    companion object {
        fun from(f: Fields, kind: Int) = LibraryItem(f.text(1), f.text(2), kind,
            when(kind) { 0 -> listOf(LedColor.from(f.message(3))); 1 -> f.messages(3).map(LedColor::from); else -> f.messages(6).map(LedColor::from) },
            if(kind == 2) f.text(3) else "", if(kind == 2) f.messages(5).map(AnimationParameter::from) else emptyList(), if(kind == 1) f.text(4) else "")
    }
}
fun Fields.messages(field: Int) = (fields[field] ?: emptyList()).map(Proto::decode)
data class LightState(val name: String, val strip: String, val count: Int, val gpio: Int, val channel: Int, val brightness: Int, val animation: String, val on: Boolean, val build: Int, val animationName: String, val parameters: List<AnimationParameter>, val relayEnabled: Boolean = false, val relayGpio: Int = 0, val relayInverted: Boolean = false, val supportsMatter: Boolean = true, val wifiEnabled: Boolean = true, val matterEnabled: Boolean = true,
    val deviceId: String = "", val ip: String = "", val mode: Int = 0, val color: LedColor = LedColor(), val gradient: LibraryItem = LibraryItem(name = "", kind = 1), val gradientId: String = "", val colorId: String = "", val preview: List<LedColor> = emptyList(), val revision: Int = 0) {
    val hasWhite get() = strip in listOf("WS2814", "WS2805", "SK6812")
    fun config() = Proto().text(1, name).text(2, strip).uint(3, count.toLong()).uint(4, gpio.toLong()).uint(5, channel.toLong())
        .data(6, Proto().uint(1, if(relayEnabled) 1 else 0).uint(2, relayGpio.toLong()).uint(3, if(relayInverted) 1 else 0).bytes.toByteArray())
        .data(7, Proto().uint(1, if(wifiEnabled) 1 else 0).bytes.toByteArray()).data(8, Proto().uint(1, if(matterEnabled) 1 else 0).bytes.toByteArray())
    fun proto() = Proto().text(1, deviceId).uint(2, build.toLong()).data(3, config().bytes.toByteArray()).uint(4, brightness.toLong()).data(5, color.proto().bytes.toByteArray()).text(6, animation).text(7, ip).uint(8, mode.toLong()).uint(9, if(on) 1 else 0).text(10, gradientId).data(11, gradient.proto().bytes.toByteArray()).text(12, colorId).text(13, animationName).apply { parameters.forEach { data(14, it.proto().bytes.toByteArray()) }; preview.forEach { data(15, it.proto().bytes.toByteArray()) } }.uint(16, revision.toLong()).uint(17, if(supportsMatter) 0 else 1)
    companion object { fun from(fields: Fields): LightState { val c = fields.message(3); return LightState(c.text(1), c.text(2), c.number(3), c.number(4), c.number(5), (if (fields.number(2) >= 11) fields.number(4) else if (fields.number(4) > 0) (fields.number(4) * 256 + 127) / 255 else 16).coerceIn(1, 256), fields.text(6), if (fields.number(2) >= 11) fields.number(9) != 0 else fields.number(4) > 0, fields.number(2), fields.text(13), fields.messages(14).map(AnimationParameter::from), c.message(6).number(1) != 0, c.message(6).number(2), c.message(6).number(3) != 0, fields.number(17) == 0, c.fields[7] == null || c.message(7).number(1) != 0, c.fields[8] == null || c.message(8).number(1) != 0,
        fields.text(1), fields.text(7), fields.number(8), LedColor.from(fields.message(5)), LibraryItem.from(fields.message(11), 1), fields.text(10), fields.text(12), fields.messages(15).map(LedColor::from), fields.number(16)) } }
}
class DeviceClient(var address: String, val simulator: SimulatorDevice? = null) {
    var bluetooth: BluetoothConnection? = null
    var usingBluetooth by mutableStateOf(address.startsWith("ble://"))
    var state by mutableStateOf<LightState?>(null)
    var network by mutableStateOf(Fields(emptyMap()))
    var error by mutableStateOf<String?>(null)
    var animationError by mutableStateOf("")
    var activeScript by mutableStateOf<LibraryItem?>(null)
    private var nextId = 1
    private val mutex = Mutex()
    private var lastProbe = 0L
    val connectionName get() = if(simulator != null) "Virtual device" else if(usingBluetooth) "Bluetooth" else "Wi-Fi"
    suspend fun request(operation: Int, payload: Proto = Proto(), timeout: Int = 10000): Fields = mutex.withLock {
        val items = mutableListOf<ByteArray>()
        val pagination = when(operation) { 5 -> Triple(9, 11, 4); 18 -> Triple(13, 17, 15); 25 -> Triple(16, 20, 18); else -> null }
        var offset = 0
        var result: Fields
        do {
            val id = nextId++
            val message = Proto().uint(1, id.toLong()).uint(2, operation.toLong())
            message.bytes.write(payload.bytes.toByteArray())
            if(offset > 0) message.uint(pagination!!.first, offset.toLong())
            val bytes = message.bytes.toByteArray()
            if(simulator != null) result = simulator.request(bytes)
            else {
                // A failed write may already have taken effect. Only reads can switch transport and retry.
                if(usingBluetooth && operation == 0 && address.startsWith("http") && System.currentTimeMillis() - lastProbe > 10000) {
                    lastProbe = System.currentTimeMillis()
                    try {
                        val probe = http(bytes, 1800)
                        val identity = probe.message(3).text(1)
                        if(identity.isNotBlank() && (state == null || state?.deviceId == identity)) usingBluetooth = false
                    } catch (_: java.io.IOException) {} catch (_: IllegalStateException) {}
                }
                try {
                    result = if(usingBluetooth) Proto.decode(checkNotNull(bluetooth) { "Bluetooth is unavailable" }.request(bytes)) else http(bytes, timeout)
                } catch(e: Exception) {
                    if(e is kotlinx.coroutines.CancellationException) throw e
                    if(operation != 0 || usingBluetooth || bluetooth == null) throw e
                    val fallback = Proto.decode(bluetooth!!.request(bytes))
                    check(state == null || fallback.message(3).text(1) == state?.deviceId) { "Bluetooth device identity changed" }
                    usingBluetooth = true; result = fallback
                }
            }
            check(result.number(1) == id) { "Mismatched device response" }
            check(result.text(2).isEmpty()) { result.text(2) }
            if(result.fields[3] != null) {
                val updated = LightState.from(result.message(3))
                check(updated.deviceId.isNotBlank()) { "Device returned no identity" }
                check(state == null || state?.deviceId == updated.deviceId) { "Device identity changed. Reconnect from the device list." }
                state = updated
                if(usingBluetooth && updated.ip.isNotBlank()) address = "http://${updated.ip}"
                animationError = result.text(22)
            }
            if(result.fields[21] != null) network = result.message(21)
            if(pagination == null) break
            items.addAll(result.fields[pagination.third] ?: emptyList())
            check(items.size <= 4096 && items.sumOf { it.size } <= 2097152) { "Device library is too large" }
            val next = result.number(pagination.second)
            check(next == 0 || next > offset && !result.fields[pagination.third].isNullOrEmpty() && next <= 10000) { "Invalid library continuation" }
            offset = next
        } while(offset > 0)
        if(pagination != null) result = Fields(result.fields.toMutableMap().apply { put(pagination.third, items); remove(pagination.second) })
        error = null
        result
    }
    private suspend fun http(bytes: ByteArray, timeout: Int): Fields = withContext(Dispatchers.IO) {
        val base = if(address.contains("://")) address else "http://$address"
        val connection = URI(base.trimEnd('/') + "/api").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = timeout; connection.readTimeout = timeout
            connection.setRequestProperty("Content-Type", "application/x-protobuf"); connection.doOutput = true
            connection.instanceFollowRedirects = false
            // Streaming bodies cannot be silently replayed after an uncertain POST failure.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            check(connection.responseCode == 200) { "Device returned HTTP ${connection.responseCode}" }
            val response = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while(output.size() <= 32768) { val count = input.read(buffer, 0, minOf(buffer.size, 32769 - output.size())); if(count < 0) break; output.write(buffer, 0, count) }
                output.toByteArray()
            }
            check(response.size <= 32768) { "Device response is too large" }; Proto.decode(response)
        } finally { connection.disconnect() }
    }
    suspend fun color(channels: List<Int>) = request(1, Proto().data(3, LedColor(channels).proto().bytes.toByteArray()))
    suspend fun library(kind: Int): List<LibraryItem> {
        val response = request(listOf(25,18,5)[kind])
        return response.messages(listOf(18,15,4)[kind]).map { LibraryItem.from(it, kind) }
    }
    suspend fun detail(item: LibraryItem) = LibraryItem.from(request(listOf(26,19,6)[item.kind], Proto().text(listOf(15,12,8)[item.kind], item.id)).message(listOf(19,16,5)[item.kind]), item.kind)
    suspend fun apply(item: LibraryItem): Fields {
        if(item.id.isNotEmpty()) return request(listOf(30,23,10)[item.kind], Proto().text(listOf(15,12,8)[item.kind], item.id))
        return request(listOf(1,17,31)[item.kind], Proto().data(listOf(3,11,7)[item.kind], if(item.kind == 0) item.colors.first().proto().bytes.toByteArray() else item.proto().bytes.toByteArray()))
    }
}
