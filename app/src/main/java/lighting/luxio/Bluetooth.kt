package lighting.luxio

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BluetoothFrame {
    private var bytes = byteArrayOf()
    fun append(chunk: ByteArray): ByteArray? {
        require(bytes.size + chunk.size <= 32776) { "Bluetooth response is too large" }
        bytes += chunk
        if(bytes.size < 8) return null
        require(bytes.copyOfRange(0, 4).contentEquals("LXP1".toByteArray())) { "Invalid Bluetooth frame" }
        val size = (4..7).fold(0L) { n, i -> (n shl 8) or (bytes[i].toLong() and 255) }
        require(size in 1..32768 && bytes.size <= size + 8) { "Invalid Bluetooth frame size" }
        if(bytes.size != size.toInt() + 8) return null
        return bytes.copyOfRange(8, bytes.size).also { bytes = byteArrayOf() }
    }
    companion object {
        fun encode(payload: ByteArray): ByteArray {
            require(payload.size in 1..32768)
            return "LXP1".toByteArray() + ByteArray(4) { (payload.size ushr ((3-it)*8)).toByte() } + payload
        }
    }
}
@SuppressLint("MissingPermission")
class BluetoothConnection(private val context: Context, val device: BluetoothDevice) {
    companion object {
        val SERVICE: UUID = UUID.fromString("e97b1000-3c27-4a20-9df4-7d025bc7e451")
        val RX: UUID = UUID.fromString("e97b1001-3c27-4a20-9df4-7d025bc7e451")
        val TX: UUID = UUID.fromString("e97b1002-3c27-4a20-9df4-7d025bc7e451")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private data class Event(val type: String, val status: Int = 0)
    private val mutex = Mutex()
    private val events = Channel<Event>(Channel.UNLIMITED)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var ready = false
    @Volatile private var mtu = 23
    @Volatile private var frame = BluetoothFrame()
    @Volatile private var response: CompletableDeferred<ByteArray>? = null
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if(g !== gatt) return
            if(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) events.trySend(Event("connected"))
            else if(newState == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                ready = false; events.trySend(Event("disconnected", status))
                response?.completeExceptionally(IllegalStateException("Bluetooth disconnected. Move closer and reconnect."))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { if(g === gatt) events.trySend(Event("services", status)) }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) { if(g === gatt) { if(status == 0) mtu = value; events.trySend(Event("mtu")) } }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { if(g === gatt) events.trySend(Event("descriptor", status)) }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { if(g === gatt) events.trySend(Event("write", status)) }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if(g !== gatt || characteristic.uuid != TX) return
            try { frame.append(value)?.let { response?.complete(it) } } catch(e: Exception) { response?.completeExceptionally(e) }
        }
        @Deprecated("Pre Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if(Build.VERSION.SDK_INT < 33) onCharacteristicChanged(g, characteristic, characteristic.value ?: byteArrayOf())
        }
    }
    private suspend fun awaitEvent(type: String) {
        val event = events.receive()
        check(event.type == type && event.status == 0) {
            if(event.status == 5 || event.status == 15) "Accept the Luxio Bluetooth pairing request, then reconnect."
            else "Bluetooth ${event.type} failed (${event.status}). Reconnect and try again."
        }
    }
    suspend fun request(bytes: ByteArray): ByteArray = mutex.withLock {
        try { withTimeout(30000) {
            if(!ready) {
                close()
                while(events.tryReceive().isSuccess) {}
                withContext(Dispatchers.Main) { gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, android.os.Handler(android.os.Looper.getMainLooper())) }
                awaitEvent("connected")
                val g = checkNotNull(gatt)
                check(g.discoverServices()); awaitEvent("services")
                if(g.requestMtu(517)) awaitEvent("mtu")
                val service = checkNotNull(g.getService(SERVICE)) { "Luxio Bluetooth service is missing" }
                val tx = checkNotNull(service.getCharacteristic(TX))
                check(g.setCharacteristicNotification(tx, true))
                val descriptor = checkNotNull(tx.getDescriptor(CCCD))
                if(Build.VERSION.SDK_INT >= 33) check(g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS)
                else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; check(g.writeDescriptor(descriptor)) }
                awaitEvent("descriptor"); ready = true
            }
            val g = checkNotNull(gatt)
            val rx = checkNotNull(g.getService(SERVICE)?.getCharacteristic(RX))
            frame = BluetoothFrame(); response = CompletableDeferred()
            val packet = BluetoothFrame.encode(bytes)
            var offset = 0
            while(offset < packet.size) {
                val end = minOf(offset + (mtu - 3).coerceIn(20, 512), packet.size)
                val chunk = packet.copyOfRange(offset, end)
                if(Build.VERSION.SDK_INT >= 33) check(g.writeCharacteristic(rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS)
                else { rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; rx.value = chunk; check(g.writeCharacteristic(rx)) }
                awaitEvent("write"); offset = end
            }
            val result = response!!.await()
            check(Proto.decode(result).number(1) == Proto.decode(bytes).number(1)) { "Mismatched Bluetooth response" }
            response = null; delay(30); result
        } } catch(e: Exception) { close(); throw e }
    }
    fun close() {
        ready = false; mtu = 23
        val old = gatt; gatt = null; old?.disconnect(); old?.close()
        response?.cancel(); response = null
    }
}
