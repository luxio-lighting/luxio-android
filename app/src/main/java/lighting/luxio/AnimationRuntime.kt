package lighting.luxio

// The same bounded Lua runtime used by iOS. Each preview owns one instance and
// runs it on a worker dispatcher; native resources are released in finally.
object AnimationRuntime {
    init { System.loadLibrary("luxio") }
    external fun create(count: Int, strip: Int): Long
    external fun destroy(handle: Long)
    external fun parameter(handle: Long, name: String, boolean: Boolean, value: Double): Boolean
    external fun start(handle: Long, source: ByteArray): Boolean
    external fun frame(handle: Long, milliseconds: Int, count: Int): ByteArray?
    external fun error(handle: Long): String
}

fun animationItem(name: String, source: String, supplied: List<AnimationParameter> = emptyList()): LibraryItem {
    require(source.toByteArray().size in 1..16384) { "Animation source must be 1–16384 bytes" }
    val parameters = mutableListOf<AnimationParameter>()
    val colors = mutableListOf<LedColor>()
    var header = true
    for(raw in source.lines()) {
        val line = raw.trim()
        if(line.startsWith("-- @parameter ")) {
            require(header) { "Parameters must be at the top of the animation" }
            val p = line.removePrefix("-- @parameter ").substringBefore("//").split(',').map(String::trim)
            require(p.size >= 3 && p[0].matches(Regex("[A-Za-z][A-Za-z0-9_ ]{0,31}")) && parameters.none { it.name == p[0] } && parameters.size < 16) { "Invalid animation parameter" }
            val boolean = p[1] in listOf("Checkbox", "Boolean")
            val initial: Double; val low: Double; val high: Double
            if(boolean) {
                require(p.size == 3 && p[2] in listOf("true", "false")) { "Invalid checkbox parameter" }
                initial = if(p[2] == "true") 1.0 else 0.0; low = 0.0; high = 1.0
            } else {
                require(p.size == 5 && p[1] in listOf("Slider", "Number")) { "Invalid numeric parameter" }
                initial = p[2].toDouble(); low = p[3].toDouble(); high = p[4].toDouble()
                require(listOf(initial, low, high).all { it.isFinite() && kotlin.math.abs(it) <= 1e9 } && low < high && initial in low..high) { "Invalid parameter bounds" }
            }
            val override = supplied.firstOrNull { it.name == p[0] }
            require(override == null || override.boolean == boolean && override.value.isFinite() && override.value in low..high && (!boolean || override.value in listOf(0.0, 1.0))) { "Invalid parameter value" }
            parameters += AnimationParameter(p[0], boolean, override?.value ?: initial, low, high, p[1] == "Number", initial)
        } else if(line.startsWith("-- @preview ")) {
            require(header && colors.isEmpty()) { "Invalid animation preview" }
            val values = line.removePrefix("-- @preview ").substringBefore("//").split(',')
            require(values.size in 2..8)
            values.forEach { value ->
                val hex = value.trim().removePrefix("#"); require(hex.matches(Regex("[0-9a-fA-F]{6}")))
                val rgb = hex.toInt(16); colors += LedColor(listOf(rgb shr 16, rgb shr 8 and 255, rgb and 255, 0, 0, 0))
            }
        } else if(line.isNotEmpty() && (!line.startsWith("--") || line.startsWith("--["))) header = false
    }
    return LibraryItem(name = name, kind = 2, source = source, parameters = parameters, colors = colors)
}
