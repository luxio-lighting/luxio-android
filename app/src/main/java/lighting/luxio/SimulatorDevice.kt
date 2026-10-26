package lighting.luxio

import java.util.UUID

// In-process device API: uses the same wire contract, controls and Lua preview as hardware.
class SimulatorDevice(saved: ByteArray? = null, private val persist: (ByteArray) -> Unit = {}) {
    var state = LightState("Luxio Studio", "WS2805", 60, 16, 0, 180, "", true, 35, "", emptyList(), deviceId = "luxio-simulator", ip = "simulator", mode = 1,
        gradient = LibraryItem(name = "Luxio", kind = 1, colors = listOf(LedColor(listOf(255,70,40,0,0,0)), LedColor(listOf(255,40,150,0,0,0)), LedColor(listOf(100,40,255,0,0,0)))))
        private set
    private val libraries = Array(3) { mutableListOf<LibraryItem>() }
    private var animation: LibraryItem? = null
    init {
        if(saved != null) runCatching {
            val f = Proto.decode(saved); val restored = LightState.from(f.message(3))
            require(restored.deviceId == "luxio-simulator" && restored.count in 1..1024 && restored.strip in listOf("WS2812", "WS2814", "WS2805", "SK6812"))
            state = restored
            for(kind in 0..2) libraries[kind].addAll(f.messages(listOf(18,15,4)[kind]).map { LibraryItem.from(it, kind) })
            if(f.fields[5] != null) animation = LibraryItem.from(f.message(5), 2)
        }
    }
    val snapshot: ByteArray get() = Proto().data(3, state.proto().bytes.toByteArray()).apply {
        for(kind in 0..2) libraries[kind].forEach { data(listOf(18,15,4)[kind], it.proto().bytes.toByteArray()) }
        animation?.let { data(5, it.proto().bytes.toByteArray()) }
    }.bytes.toByteArray()
    fun request(bytes: ByteArray): Fields {
        val input = Proto.decode(bytes); val operation = input.number(2)
        val response = Proto().uint(1, input.number(1).toLong())
        when(operation) {
            0 -> Unit
            1 -> {
                val color = LedColor.from(input.message(3)); validateColors(listOf(color))
                state = state.copy(color = color, mode = 0, on = true, colorId = "", gradientId = "", animation = "", animationName = "", parameters = emptyList(), preview = emptyList()); animation = null
            }
            2 -> { require(input.number(4) in 1..256); state = state.copy(brightness = input.number(4)) }
            15 -> state = state.copy(on = input.number(10) != 0)
            3 -> {
                val c = input.message(5)
                require(c.text(1).isNotBlank() && c.text(1).toByteArray().size <= 63 && c.text(2) in listOf("WS2812", "WS2814", "WS2805", "SK6812") && c.number(3) in 1..1024 && c.number(4) in 0..54) { "Invalid device settings" }
                val old = state
                state = state.copy(name = c.text(1), strip = c.text(2), count = c.number(3), gpio = c.number(4), channel = c.number(5), relayEnabled = c.message(6).number(1) != 0, relayGpio = c.message(6).number(2), relayInverted = c.message(6).number(3) != 0, wifiEnabled = c.message(7).number(1) != 0, matterEnabled = c.message(8).number(1) != 0)
                if(old.strip != state.strip || old.count != state.count) { state = state.copy(mode = 0, color = LedColor(), colorId = "", gradientId = "", animation = "", animationName = "", parameters = emptyList(), preview = emptyList()); animation = null }
            }
            5,18,25 -> { val kind = listOf(25,18,5).indexOf(operation); libraries[kind].forEach { response.data(listOf(18,15,4)[kind], it.proto().bytes.toByteArray()) } }
            6,19,26 -> {
                val kind = listOf(26,19,6).indexOf(operation); val id = input.text(listOf(15,12,8)[kind])
                val item = if(kind == 2 && id.isEmpty()) animation else libraries[kind].find { it.id == id }
                checkNotNull(item) { "Saved item not found" }; response.data(listOf(19,16,5)[kind], item.proto().bytes.toByteArray())
            }
            9,22,29 -> {
                val kind = listOf(29,22,9).indexOf(operation); val id = input.text(listOf(15,12,8)[kind])
                check(libraries[kind].removeAll { it.id == id }) { "Saved item not found" }
                state = state.copy(colorId = if(state.colorId == id) "" else state.colorId, gradientId = if(state.gradientId == id) "" else state.gradientId, animation = if(state.animation == id) "" else state.animation)
            }
            7,8,10,17,20,21,23,27,28,30,31 -> {
                val kind = when(operation) { 27,28,30 -> 0; 17,20,21,23 -> 1; else -> 2 }
                val create = operation in listOf(27,20,7); val update = operation in listOf(28,21,8); val run = operation in listOf(30,23,10)
                val id = input.text(listOf(15,12,8)[kind])
                var item = if(run) checkNotNull(libraries[kind].find { it.id == id }) { "Saved item not found" } else LibraryItem.from(input.message(listOf(14,11,7)[kind]), kind)
                require(item.name.toByteArray().size <= 63 && (!(create || update) || item.name.isNotBlank())) { "A name is required (up to 63 bytes)" }
                if(kind < 2) { require(if(kind == 0) item.colors.size == 1 else item.colors.size in 2..8 && state.count > 1) { "Gradients require 2–8 colors and at least 2 LEDs" }; validateColors(item.colors); require(item.metadata.toByteArray().size <= 2048) }
                else item = animationItem(item.name, item.source, item.parameters).copy(id = item.id)
                item = item.copy(id = if(create) UUID.randomUUID().toString() else if(update || run) item.id else "")
                if(create) libraries[kind].add(item)
                else if(update) { val index = libraries[kind].indexOfFirst { it.id == item.id }; check(index >= 0) { "Saved item not found" }; libraries[kind][index] = item }
                else {
                    state = state.copy(mode = kind, on = true, colorId = "", gradientId = "", animation = "", animationName = "", parameters = emptyList(), preview = emptyList()); animation = null
                    when(kind) {
                        0 -> state = state.copy(color = item.colors.first(), colorId = item.id)
                        1 -> state = state.copy(gradient = item, gradientId = item.id)
                        2 -> { animation = item; state = state.copy(animation = item.id, animationName = item.name, parameters = item.parameters, preview = item.colors, revision = state.revision + 1) }
                    }
                }
                response.data(listOf(19,16,5)[kind], item.proto().bytes.toByteArray())
            }
            11,24 -> { state = state.copy(mode = 0, animation = "", animationName = "", gradientId = "", parameters = emptyList(), preview = emptyList()); animation = null }
            32 -> {
                require(state.mode == 2 && (input.text(8).isEmpty() || input.text(8) == state.animation) && (input.fields[7] == null || input.message(7).text(2) == state.animationName)) { "Animation changed" }
                val patches = input.messages(17).map(AnimationParameter::from)
                require(patches.map { it.name }.distinct().size == patches.size)
                for(p in patches) { val definition = state.parameters.find { it.name == p.name }; require(definition != null && definition.boolean == p.boolean && p.value.isFinite() && p.value in definition.minimum..definition.maximum && (!p.boolean || p.value in listOf(0.0,1.0))) { "Invalid parameter value" } }
                state = state.copy(parameters = state.parameters.map { p -> p.copy(value = patches.find { it.name == p.name }?.value ?: p.value) })
                animation = animation?.copy(parameters = state.parameters)
            }
            else -> error("This feature requires a physical Luxio device")
        }
        response.data(3, state.proto().bytes.toByteArray())
        if(operation != 0 && operation !in listOf(5,18,25,6,19,26)) persist(snapshot)
        return Proto.decode(response.bytes.toByteArray())
    }
    private fun validateColors(colors: List<LedColor>) {
        require(colors.all { c -> c.channels.all { it in 0..255 } && (state.strip == "WS2805" || c.channels[4] == 0 && c.channels[5] == 0) && (state.strip in listOf("SK6812", "WS2814") || c.channels[3] == 0) }) { "White channel unavailable on this strip type" }
    }
}
