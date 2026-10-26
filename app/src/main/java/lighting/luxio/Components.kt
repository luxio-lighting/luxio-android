package lighting.luxio

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.*
import kotlin.math.*

fun LedColor.display(): Color {
    val c = channels + List(6) { 0 }
    val r = c[0] + c[3] + c[4] + c[5] * .72f
    val g = c[1] + c[3] + c[4] * .65f + c[5] * .84f
    val b = c[2] + c[3] + c[4] * .25f + c[5]
    val scale = maxOf(255f, r, g, b)
    return Color((r/scale).coerceIn(0f,1f), (g/scale).coerceIn(0f,1f), (b/scale).coerceIn(0f,1f))
}
@Composable fun LightPreview(client: DeviceClient, modifier: Modifier = Modifier) {
    val s = client.state ?: return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    val reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && s.on && !reducedMotion
    var pixels by remember(client) { mutableStateOf<List<Color>?>(null) }
    val params by rememberUpdatedState(s.parameters)
    LaunchedEffect(client, s.mode, s.revision, s.animation, s.animationName) {
        if(s.mode == 2) {
            try { client.activeScript = client.detail(LibraryItem(id = s.animation, name = s.animationName, kind = 2)) }
            catch(e: Exception) { if(e is CancellationException) throw e; client.activeScript = builtInAnimations.find { it.name == s.animationName } }
        } else client.activeScript = null
    }
    val source = if(s.mode == 2) client.activeScript?.source else null
    LaunchedEffect(source, s.count, s.strip, active) {
        pixels = null
        if(source.isNullOrEmpty() || !active) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val count = s.count.coerceIn(1,1024)
            val handle = AnimationRuntime.create(count, s.strip.removePrefix("WS").removePrefix("SK").toIntOrNull() ?: 2812)
            if(handle == 0L) return@withContext
            try {
                params.forEach { AnimationRuntime.parameter(handle, it.name, it.boolean, it.value) }
                if(!AnimationRuntime.start(handle, source.toByteArray())) { if(client.simulator != null) client.animationError = AnimationRuntime.error(handle); return@withContext }
                val started = android.os.SystemClock.elapsedRealtime()
                while(isActive) {
                    params.forEach { AnimationRuntime.parameter(handle, it.name, it.boolean, it.value) }
                    val frame = AnimationRuntime.frame(handle, (android.os.SystemClock.elapsedRealtime()-started).toInt(), count)
                    if(frame == null) { if(client.simulator != null) client.animationError = AnimationRuntime.error(handle); break }
                    pixels = (0 until minOf(count,65)).map { sample ->
                        val index = if(count == 1) 0 else sample * (count-1) / (minOf(count,65)-1)
                        LedColor((0..5).map { frame[index*6+it].toInt() and 255 }).display()
                    }
                    delay(33)
                }
            } finally { AnimationRuntime.destroy(handle) }
        }
    }
    val palette = if(!s.on) listOf(Color(0xFF282630),Color(0xFF16151B)) else pixels ?: when(s.mode) { 1 -> s.gradient.colors.map { it.display() }; 2 -> s.preview.map { it.display() }; else -> listOf(s.color.display()) }
    Palette(palette, modifier)
}
@Composable fun Palette(colors: List<Color>, modifier: Modifier = Modifier) {
    val palette = colors.ifEmpty { listOf(Color(0xFF332942),Color(0xFF644266)) }.let { if(it.size == 1) it + it else it }
    Box(modifier.background(Brush.horizontalGradient(palette)))
}
@Composable fun BrightnessControl(client: DeviceClient, modifier: Modifier = Modifier) {
    val state = client.state ?: return
    var brightness by remember { mutableFloatStateOf(state.brightness.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope(); val view = LocalView.current
    LaunchedEffect(state.brightness) { if(!dragging) brightness = state.brightness.toFloat() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

        Slider(brightness, { if(!dragging) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); dragging = true; brightness = it }, valueRange = 1f..256f, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = .65f), inactiveTrackColor = Color.Black.copy(alpha = .18f)), modifier = Modifier.weight(1f).semantics { contentDescription = "Brightness" }, onValueChangeFinished = {
            dragging = false; scope.launch { try { client.request(2, Proto().uint(4, brightness.roundToInt().toLong())) } catch(e: Exception) { client.error = e.message } }
        })

    }
}
@Composable fun ColorWheel(color: LedColor, white: Boolean, strip: String, modifier: Modifier = Modifier, onChange: (LedColor, Int) -> Unit, onCommit: () -> Unit, points: List<LedColor> = listOf(color), selectedPoint: Int = 0, onSelectPoint: (Int) -> Unit = {}) {
    val view = LocalView.current
    val currentChange by rememberUpdatedState(onChange); val currentCommit by rememberUpdatedState(onCommit)
    val texture by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.Default) {
            val side = 512; val pixels = IntArray(side * side); val hsv = floatArrayOf(0f, 1f, 1f)
            for(y in 0 until side) for(x in 0 until side) {
                val dx = (x + .5f - side / 2f) / (side / 2f); val dy = (y + .5f - side / 2f) / (side / 2f)
                hsv[0] = ((atan2(dy, dx) * 180 / PI + 480) % 360).toFloat()
                hsv[1] = sqrt(dx * dx + dy * dy).coerceIn(0f, 1f)
                pixels[y * side + x] = android.graphics.Color.HSVToColor(hsv)
            }
            android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
    val hsv = FloatArray(3); android.graphics.Color.RGBToHSV(color.channels[0], color.channels[1], color.channels[2], hsv)
    val temperature = color.channels[5].toFloat() / (color.channels[4] + color.channels[5]).coerceAtLeast(1)
    val markers = points.mapIndexedNotNull { index, c ->
        if(c.channels.drop(3).any { it > 0 } != white) null else {
            val h = FloatArray(3); android.graphics.Color.RGBToHSV(c.channels[0], c.channels[1], c.channels[2], h)
            val a = (h[0] - 120) / 180 * PI
            val t = c.channels[5].toFloat() / (c.channels[4] + c.channels[5]).coerceAtLeast(1)
            Triple(index, c.display(), if(white && strip == "WS2805") Offset(0f, t*2-1) else if(white) Offset.Zero else Offset(cos(a).toFloat()*h[1], sin(a).toFloat()*h[1]))
        }
    }
    var gesturePoint by remember { mutableIntStateOf(selectedPoint) }
    val currentSelectedPoint by rememberUpdatedState(selectedPoint)
    val currentMarkers by rememberUpdatedState(markers)
    val currentSelect by rememberUpdatedState(onSelectPoint)
    val hitRadius = with(LocalDensity.current) { 22.dp.toPx() }
    val gesture: (Offset, Float) -> Unit = { point, width ->
        val radius = width/2; val x = (point.x-radius)/radius; val y = (point.y-radius)/radius
        if(white) {
            val cool = (((y+1)/2).coerceIn(0f,1f)*255).roundToInt()
            currentChange(LedColor(if(strip == "WS2805") listOf(0,0,0,0,255-cool,cool) else listOf(0,0,0,255,0,0)), gesturePoint)
        } else {
            val hue = ((atan2(y,x)*180/PI+120+360)%360).toFloat(); val sat = sqrt(x*x+y*y).coerceIn(0f,1f)
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue,sat,1f))
            currentChange(LedColor(listOf(rgb shr 16 and 255,rgb shr 8 and 255,rgb and 255,0,0,0)), gesturePoint)
        }
    }
    Canvas(modifier.aspectRatio(1f).clip(CircleShape).semantics {
        contentDescription = if(white && strip == "WS2805") "White temperature wheel" else if(white) "White wheel" else "Color wheel"
        stateDescription = if(white && strip != "WS2805") "Dedicated white LEDs" else if(white) "${(2700+temperature*3800).roundToInt()} kelvin" else "Hue ${hsv[0].roundToInt()}, saturation ${(hsv[1]*100).roundToInt()} percent"
        progressBarRangeInfo = ProgressBarRangeInfo(if(white) temperature else hsv[0], if(white) 0f..1f else 0f..360f)
        setProgress { value ->
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            if(white) { val cool = (value.coerceIn(0f,1f) * 255).roundToInt(); currentChange(LedColor(if(strip == "WS2805") listOf(0,0,0,0,255-cool,cool) else listOf(0,0,0,255,0,0)), currentSelectedPoint) }
            else { val rgb = android.graphics.Color.HSVToColor(floatArrayOf(value.coerceIn(0f,360f),hsv[1],1f)); currentChange(LedColor(listOf(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255,0,0,0)), currentSelectedPoint) }
            currentCommit(); true
        }
        customActions = listOf(CustomAccessibilityAction("Set warm white") { currentChange(LedColor(if(strip == "WS2805") listOf(0,0,0,0,255,0) else if(strip in listOf("SK6812","WS2814")) listOf(0,0,0,255,0,0) else listOf(255,160,60,0,0,0)), currentSelectedPoint); currentCommit(); true })
    }.pointerInput(white, strip) { detectTapGestures(onTap = { tap ->
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val middle = Offset(size.width / 2f, size.height / 2f)
        val hit = currentMarkers.minByOrNull { (middle + it.third * (size.width / 2f - 17.dp.toPx()) - tap).getDistance() }
        if(hit != null && (middle + hit.third * (size.width / 2f - 17.dp.toPx()) - tap).getDistance() <= hitRadius) currentSelect(hit.first)
        else { gesturePoint = currentSelectedPoint; gesture(tap, size.width.toFloat()) }
        currentCommit()
    }) }.pointerInput(white, strip) { detectDragGestures(onDragStart = { start ->
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val middle = Offset(size.width / 2f, size.height / 2f)
        val hit = currentMarkers.minByOrNull { (middle + it.third * (size.width / 2f - 17.dp.toPx()) - start).getDistance() }
        if(hit != null && (middle + hit.third * (size.width / 2f - 17.dp.toPx()) - start).getDistance() <= hitRadius) { gesturePoint = hit.first; currentSelect(hit.first) }
        else { gesturePoint = currentSelectedPoint; gesture(start, size.width.toFloat()) }
    }, onDragEnd = { currentCommit() }, onDragCancel = { currentCommit() }) { change, _ -> change.consume(); gesture(change.position, size.width.toFloat()) } }) {
        if(white && strip != "WS2805") drawCircle(Color(0xFFF5F4FF))
        else if(white) drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFBA4D), Color(0xFFFFEDA6), Color.White, Color(0xFFC7EDFF))))
        else if(texture != null) drawImage(texture!!, dstSize = IntSize(size.width.roundToInt(),size.height.roundToInt()), filterQuality = FilterQuality.Medium)
        else {
            val hues = (0..12).map { Color.hsv(((it*30+120)%360).toFloat(), 1f, 1f) }
            drawCircle(Brush.sweepGradient(hues)); drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
        }
        for(marker in markers.sortedBy { if(it.first == selectedPoint) 1 else 0 }) {
            val point = center + marker.third * (size.width / 2 - 17.dp.toPx())
            val radius = if(marker.first == selectedPoint) 17.dp.toPx() else 14.dp.toPx()
            drawCircle(Color.Black.copy(alpha = .35f), radius + 2.dp.toPx(), point)
            drawCircle(marker.second, radius, point)
            drawCircle(Color.White, radius - 1.5.dp.toPx(), point, style = Stroke(3.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText("${marker.first + 1}", point.x, point.y + 4.dp.toPx(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.dp.toPx(); this.color = android.graphics.Color.argb(180,0,0,0); textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD })
        }

    }
}
@Composable fun SectionHeading(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
@Composable fun ErrorText(error: String?) { if(!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
