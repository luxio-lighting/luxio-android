package lighting.luxio

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                    1 -> { require(offset + 8 <= bytes.size); offset += 8 }
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
    fun message(field: Int) = Proto.decode(fields[field]?.firstOrNull() ?: byteArrayOf())
}
data class Light(val id: String, val name: String, val address: String)
data class LightState(val name: String, val strip: String, val count: Int, val gpio: Int, val channel: Int, val brightness: Int, val animation: String) {
    companion object { fun from(fields: Fields): LightState { val c = fields.message(3); return LightState(c.text(1), c.text(2), c.number(3), c.number(4), c.number(5), fields.number(4), fields.text(6)) } }
}
class DeviceClient(private val address: String) {
    private var nextId = 1
    suspend fun request(operation: Int, payload: Proto = Proto()): Fields = withContext(Dispatchers.IO) {
        val id = synchronized(this@DeviceClient) { nextId++ }
        val message = Proto().uint(1, id.toLong()).uint(2, operation.toLong()); message.bytes.write(payload.bytes.toByteArray())
        val base = if (address.contains("://")) address else "http://$address"
        val url = URI(base.trimEnd('/') + "/api").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/x-protobuf"); connection.doOutput = true
            connection.outputStream.use { it.write(message.bytes.toByteArray()) }
            check(connection.responseCode == 200) { "Device returned HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { it.readNBytes(32769) }; check(bytes.size <= 32768)
            val result = Proto.decode(bytes)
            check(result.number(1) == id) { "Mismatched device response" }; check(result.text(2).isEmpty()) { result.text(2) }
            result
        } finally { connection.disconnect() }
    }
    suspend fun color(channels: List<Int>): Fields {
        val color = Proto(); channels.forEachIndexed { index, value -> color.uint(index + 1, value.toLong()) }
        return request(1, Proto().data(3, color.bytes.toByteArray()))
    }
}
