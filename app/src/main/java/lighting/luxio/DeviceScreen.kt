package lighting.luxio

import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import java.util.Base64

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DeviceScreen(client: DeviceClient, discovery: Discovery, onBack: () -> Unit) {
    val state = client.state ?: return
    val preview = when(state.mode) { 1 -> state.gradient.colors; 2 -> state.preview; else -> listOf(state.color) }
    val darkInk = state.on && preview.isNotEmpty() && preview.all { val c = it.display(); c.red * .2126f + c.green * .7152f + c.blue * .0722f > .68f }
    val ink = if(darkInk) Color.Black.copy(alpha = .85f) else Color.White
    val compact = LocalConfiguration.current.screenWidthDp < 380 || LocalDensity.current.fontScale > 1.15f
    val scope = rememberCoroutineScope(); val view = LocalView.current; val context = LocalContext.current
    DisposableEffect(view, darkInk) {
        val window = (context as? android.app.Activity)?.window
        if(window != null) androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkInk
        onDispose { if(window != null) androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false }
    }
    var mode by remember(client) { mutableIntStateOf(state.mode) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var color by remember(client) { mutableStateOf(state.color) }
    var points by remember(client) { mutableStateOf(state.gradient.colors.ifEmpty { gradientPresets.first().colors }) }
    var point by remember { mutableIntStateOf(0) }
    var rgbPoints by remember(client) { mutableStateOf<Map<Int, LedColor>>(emptyMap()) }
    var white by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var library by remember(client, mode) { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var search by remember(client, mode) { mutableStateOf("") }
    var renaming by remember { mutableStateOf<LibraryItem?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryError by remember(client, mode) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<LibraryItem?>(null) }
    var actionCount by remember { mutableIntStateOf(0) }
    var saveName by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var colorJob by remember { mutableStateOf<Job?>(null) }
    var pendingColor by remember { mutableStateOf<LibraryItem?>(null) }
    var touchingWheel by remember { mutableStateOf(false) }
    val previewColor: (LibraryItem) -> Unit = { item ->
        pendingColor = item
        if(colorJob?.isActive != true) colorJob = scope.launch {
            try {
                while(pendingColor != null) {
                    delay(45)
                    val latest = pendingColor ?: break; pendingColor = null
                    client.apply(latest)
                }
            } catch(e: Exception) { if(e is CancellationException) throw e; pendingColor = null; localError = e.message }
            finally { if(!touchingWheel) editing = false }
        }
    }
    LaunchedEffect(state.mode, state.revision) { mode = state.mode }
    LaunchedEffect(state.color, state.gradient) { if(!editing) { color = state.color; if(state.gradient.colors.size >= 2) points = state.gradient.colors; if(mode < 2) white = (if(mode == 0) color else points[point.coerceAtMost(points.lastIndex)]).channels.drop(3).any { it > 0 } } }
    LaunchedEffect(client, mode, sheet, saving, libraryRevision) {
        if(sheet == null && saving == null) try {
            libraryError = null
            val listed = client.library(mode)
            library = listed
            for(item in listed) { val detail = client.detail(item); library = library.map { if(it.id == detail.id) detail else it } }
        } catch(e: Exception) { if(e is CancellationException) throw e; libraryError = e.message }
    }
    val act: (suspend () -> Unit) -> Unit = { operation ->
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        scope.launch { actionCount++; localError = null; try { colorJob?.join(); operation() } catch(e: Exception) { if(e is CancellationException) throw e; localError = e.message ?: "Could not update Luxio" } finally { actionCount--; editing = false } }
    }
    val apply: (LibraryItem) -> Unit = { item -> act {
        var candidate = item
        if(item.kind == 2) {
            val saved = discovery.prefs.getString("parameters:${state.deviceId}:${if(item.id.isEmpty()) "preset:${item.name}" else "saved:${item.id}"}", null)
            if(saved != null) runCatching {
                val remembered = Proto.decode(Base64.getDecoder().decode(saved)).messages(17).map(AnimationParameter::from)
                candidate = candidate.copy(parameters = candidate.parameters.map { parameter ->
                    val value = remembered.find { it.name == parameter.name && it.boolean == parameter.boolean && it.value.isFinite() && it.value in parameter.minimum..parameter.maximum && (!it.boolean || it.value == 0.0 || it.value == 1.0) }
                    parameter.copy(value = value?.value ?: parameter.value)
                })
            }
        }
        client.apply(candidate)
        if(candidate.kind == 2) {
            client.activeScript = candidate
            if(candidate.id.isNotEmpty() && candidate.parameters.isNotEmpty()) client.request(32, Proto().text(8,candidate.id).apply { candidate.parameters.forEach { data(17,it.proto().bytes.toByteArray()) } })
        }
        mode = item.kind
    } }
    val patch: (List<AnimationParameter>) -> Unit = { parameters -> scope.launch {
        localError = null
        try {
        val payload = Proto().text(8, state.animation).data(7, Proto().text(2,state.animationName).bytes.toByteArray()).apply { parameters.forEach { data(17,it.proto().bytes.toByteArray()) } }
        client.request(32,payload)
        val all = client.state!!.parameters
        discovery.prefs.edit().putString("parameters:${state.deviceId}:${if(state.animation.isEmpty()) "preset:${state.animationName}" else "saved:${state.animation}"}", Base64.getEncoder().encodeToString(Proto().apply { all.forEach { data(17,it.proto().bytes.toByteArray()) } }.bytes.toByteArray())).apply()
        } catch(e: Exception) { if(e is CancellationException) throw e; localError = e.message }
    } }
    Column(Modifier.fillMaxSize().background(Backdrop).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))) {
            LightPreview(client, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if(darkInk) .22f else .45f)))))
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ink) }
                    Text(state.name, color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("device-title"))
                    IconButton(onClick = { sheet = "settings" }) { Icon(Icons.Rounded.MoreHoriz, "Device settings", tint = ink) }
                    Switch(state.on, { on -> act { client.request(15, Proto().uint(10, if(on) 1 else 0)) } }, modifier = Modifier.semantics { contentDescription = if(state.on) "Turn light off" else "Turn light on" }, colors = SwitchDefaults.colors(checkedTrackColor = Color.Black.copy(alpha = .28f), checkedThumbColor = Color.White))
                }
                BrightnessControl(client, Modifier.padding(horizontal = 20.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    listOf("Color", "Gradient", "Animation").forEachIndexed { index, title ->
                        SegmentedButton(icon = {}, colors = SegmentedButtonDefaults.colors(activeContainerColor = Color.White.copy(alpha = .24f), activeContentColor = ink, inactiveContainerColor = Color.Black.copy(alpha = .16f), inactiveContentColor = ink.copy(alpha = .8f), activeBorderColor = Color.White.copy(alpha = .25f), inactiveBorderColor = Color.White.copy(alpha = .15f)), modifier = Modifier.testTag("mode-$index"), selected = mode == index, onClick = { mode = index; white = false }, shape = SegmentedButtonDefaults.itemShape(index, 3), enabled = index != 1 || state.count > 1) { Text(title, maxLines = 1) }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("device-scroll"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(localError != null || client.error != null) item { ErrorText(localError ?: client.error) }
            if(mode < 2) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        ColorWheel(if(mode == 0) color else points[point.coerceAtMost(points.lastIndex)], white && state.hasWhite, state.strip, Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 6.dp, vertical = 18.dp), onChange = { next, editedPoint ->
                            editing = true; touchingWheel = true
                            if(mode == 0) { color = next; previewColor(LibraryItem(name = "", kind = 0, colors = listOf(next))) }
                            else { points = points.mapIndexed { index, old -> if(index == editedPoint) next else old }; previewColor(LibraryItem(name = "Custom gradient", kind = 1, colors = points)) }
                        }, onCommit = { touchingWheel = false; if(colorJob?.isActive != true) editing = false }, points = if(mode == 1) points else listOf(color), selectedPoint = if(mode == 1) point else 0, onSelectPoint = { if(mode == 1) point = it })
                        if(state.hasWhite) Row(Modifier.align(Alignment.Start).padding(start = 6.dp, top = 4.dp, bottom = 10.dp).clip(CircleShape).background(Color.White.copy(alpha = .1f)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for(isWhite in listOf(false, true)) {
                                Box(Modifier.size(40.dp).border(2.dp, if(white == isWhite) Color.White else Color.Transparent, CircleShape).selectable(selected = white == isWhite, role = Role.RadioButton, onClick = {
                                    if(white != isWhite) {
                                        val key = if(mode == 0) -1 else point
                                        val current = if(mode == 0) color else points[point]
                                        if(isWhite) rgbPoints = rgbPoints + (key to current)
                                        val next = if(isWhite) LedColor(if(state.strip == "WS2805") listOf(0,0,0,0,128,127) else listOf(0,0,0,255,0,0)) else rgbPoints[key] ?: LedColor(listOf(255,255,255,0,0,0))
                                        white = isWhite
                                        if(mode == 0) { color = next; apply(LibraryItem(name = "", kind = 0, colors = listOf(next))) }
                                        else { points = points.mapIndexed { index, old -> if(index == point) next else old }; apply(LibraryItem(name = "Custom gradient", kind = 1, colors = points)) }
                                    }
                                }).semantics { contentDescription = if(isWhite) { if(state.strip == "WS2805") "White temperature selector" else "White channel selector" } else "Color wheel selector" }.padding(6.dp).clip(CircleShape)) {
                                    // Match iOS's smooth angular gradient and dark-mode system colors.
                                    if(!isWhite) Box(Modifier.fillMaxSize().background(Brush.sweepGradient(listOf(Color(0xFFFF453A), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF64D2FF), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF453A)))))
                                    else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(if(state.strip == "WS2805") listOf(Color(0xFFFFBA4D), Color.White, Color(0xFF99FFFF)) else listOf(Color.White, Color.White))))
                                }
                            }
                        }
                    }
                }
                if(mode == 1) item {
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Palette(points.map { it.display() }, Modifier.fillMaxWidth().height(14.dp).clip(CircleShape))
                        Row(Modifier.fillMaxWidth()) { Text("START", fontSize = 9.sp, color = Muted, letterSpacing = 1.4.sp); Spacer(Modifier.weight(1f)); Text("END", fontSize = 9.sp, color = Muted, letterSpacing = 1.4.sp) }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            points.forEachIndexed { index, c ->
                                Box(Modifier.size(44.dp).border(if(point == index) 2.dp else 0.dp, if(point == index) Color.White else Color.Transparent, CircleShape).padding(5.dp).clip(CircleShape).background(c.display()).clickable { point = index; white = c.channels.drop(3).any { it > 0 } }.testTag("gradient-stop-$index").semantics { contentDescription = "Gradient stop ${index + 1}"; selected = point == index; role = Role.Button }, contentAlignment = Alignment.Center) { Text("${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = .7f)) }
                            }
                            if(points.size < 8) IconButton(onClick = { points = points + points.last(); point = points.lastIndex; apply(LibraryItem(name = "Custom gradient", kind = 1, colors = points)) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Add, "Add gradient stop") }
                            if(points.size > 2) IconButton(onClick = { points = points.filterIndexed { index, _ -> index != point }; point = point.coerceAtMost(points.lastIndex); apply(LibraryItem(name = "Custom gradient", kind = 1, colors = points)) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Remove, "Remove gradient stop") }
                        }
                    }
                }
            }
            item { SectionHeading("Presets") }
            val presets = when(mode) { 0 -> colorPresets; 1 -> gradientPresets; else -> builtInAnimations }
            items(presets.chunked(if(compact) 2 else 3), key = { it.first().name }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item -> PresetCard(item, Modifier.weight(1f), selected = when(mode) { 2 -> state.mode == 2 && state.animationName == item.name; 1 -> state.mode == 1 && state.gradient.colors == item.colors; else -> state.mode == 0 && state.color == item.colors.first() }, onClick = { apply(item) }, onOptions = if(mode == 2 && state.mode == 2 && state.animationName == item.name) ({ sheet = "animation" }) else null, onSave = { act { client.request(listOf(27,20,7)[item.kind], Proto().data(listOf(14,11,7)[item.kind], item.copy(id = "").proto().bytes.toByteArray())); libraryRevision++ } }) }
                    repeat((if(compact) 2 else 3) - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saved", style = MaterialTheme.typography.titleMedium)
                    if(library.isEmpty()) Text(buildAnnotatedString {
                        append("No saved ${listOf("colors", "gradients", "animations")[mode]} yet.")
                        if(mode == 2) {
                            append(" To create your own animations, try ")
                            withLink(LinkAnnotation.Url("https://studio.luxio.lighting", TextLinkStyles(style = SpanStyle(color = Color.White.copy(alpha = .75f), textDecoration = TextDecoration.Underline)))) { append("Luxio Studio") }
                            append(".")
                        }
                    }, color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if(libraryError != null) item { ErrorText(libraryError) }
            if(library.size > 8) item { TextField(search, { search = it }, placeholder = { Text("Search saved items") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Panel, unfocusedContainerColor = Panel, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), modifier = Modifier.fillMaxWidth().testTag("saved-search")) }
            val savedTiles: List<LibraryItem?> = library.filter { it.name.contains(search, true) } + if(mode < 2) listOf(null) else emptyList()
            items(savedTiles.chunked(if(compact) 2 else 3), key = { "saved-${it.firstOrNull()?.id ?: "add"}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        if(item == null) Column(Modifier.weight(1f).height(116.dp).clip(RoundedCornerShape(13.dp)).background(Panel).clickable { saving = LibraryItem(name = "", kind = mode, colors = if(mode == 0) listOf(color) else points); saveName = "" }.testTag("save-preset").semantics { contentDescription = if(mode == 0) "Save color" else "Save gradient" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, Modifier.size(24.dp)) }
                            Spacer(Modifier.height(12.dp)); Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        } else PresetCard(item, Modifier.weight(1f), selected = state.mode == mode && when(mode) { 0 -> state.colorId == item.id; 1 -> state.gradientId == item.id; else -> state.animation == item.id }, onClick = { apply(item) }, onOptions = if(mode == 2 && state.mode == 2 && state.animation == item.id) ({ sheet = "animation" }) else null, onRename = { renaming = item; renameName = item.name }, onDelete = { deleting = item })
                    }
                    repeat((if(compact) 2 else 3) - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if(sheet == "animation") LuxioSheet(state.animationName.ifEmpty { "Animation" }, { sheet = null }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { ErrorText(client.animationError) }
            items(state.parameters, key = { it.name }) { parameter -> ParameterControl(parameter) { value -> patch(listOf(parameter.copy(value = value))) } }
            if(state.parameters.isNotEmpty()) item { TextButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); patch(state.parameters.map { it.copy(value = it.defaultValue) }) }) { Text("Reset parameters") } }
            item { Row {
                TextButton(onClick = { act { saving = client.detail(LibraryItem(id = state.animation, name = state.animationName, kind = 2)).copy(id = "", parameters = state.parameters); saveName = state.animationName } }) { Text("Save animation") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { act { client.request(11); sheet = null } }) { Text("Stop animation") }
            } }
        }
    }
    if(sheet == "settings") SettingsSheet(client, discovery, onDismiss = { sheet = null }, onRemoved = { sheet = null; onBack() })
    renaming?.let { item -> AlertDialog(onDismissRequest = { if(actionCount == 0) renaming = null }, title = { Text("Rename") }, text = { Column { OutlinedTextField(renameName, { renameName = it }, label = { Text("Name") }, singleLine = true); ErrorText(localError) } }, confirmButton = { TextButton(enabled = actionCount == 0 && renameName.isNotBlank() && renameName.toByteArray().size <= 63, onClick = { act { val updated = client.detail(item).copy(name = renameName.trim()); client.request(listOf(28,21,8)[item.kind], Proto().data(listOf(14,11,7)[item.kind], updated.proto().bytes.toByteArray())); renaming = null; search = ""; libraryRevision++ } }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }) }
    deleting?.let { item -> AlertDialog(onDismissRequest = { if(actionCount == 0) deleting = null }, title = { Text("Delete saved item?") }, text = { Column { Text("Delete ${item.name} from this device?"); ErrorText(localError) } }, confirmButton = { TextButton(enabled = actionCount == 0, onClick = { act { client.request(listOf(29,22,9)[item.kind], Proto().text(listOf(15,12,8)[item.kind], item.id)); deleting = null; libraryRevision++ } }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    saving?.let { item ->
        AlertDialog(onDismissRequest = { if(actionCount == 0) saving = null }, title = { Text("Save ${listOf("color","gradient","animation")[item.kind]}") }, text = { Column { OutlinedTextField(saveName,{ saveName=it },label={ Text("Name") },singleLine=true); ErrorText(localError) } }, confirmButton = { TextButton(enabled=saveName.isNotBlank() && saveName.toByteArray().size <= 63 && actionCount == 0, onClick={ act { client.request(listOf(27,20,7)[item.kind],Proto().data(listOf(14,11,7)[item.kind],item.copy(id="",name=saveName.trim()).proto().bytes.toByteArray())); saving=null } }) { Text("Save") } }, dismissButton = { TextButton(onClick={ saving=null }) { Text("Cancel") } })
    }
}
@Composable fun PresetCard(item: LibraryItem, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit, onOptions: (() -> Unit)? = null, onRename: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onSave: (() -> Unit)? = null) {
    var menu by remember(item.id, item.name) { mutableStateOf(false) }
    Box(modifier.height(116.dp).clip(RoundedCornerShape(13.dp)).background(Panel).border(if(selected) 1.5.dp else 0.dp, if(selected) Color.White.copy(alpha = .8f) else Color.Transparent, RoundedCornerShape(13.dp))) {
        Column(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClickLabel = "Options for ${item.name}", onLongClick = { menu = true }).testTag("preset-${item.name}"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val colors = item.colors.map { it.display() }.ifEmpty { listOf(Accent, Panel) }.let { if(it.size == 1) it + it else it }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(colors)))
            Spacer(Modifier.height(12.dp))
            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if(onOptions != null) IconButton(onClick = onOptions, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) { Icon(Icons.Rounded.Tune, "Settings for ${item.name}", Modifier.size(18.dp)) }
        DropdownMenu(menu, { menu = false }) {
            if(onSave != null) DropdownMenuItem(text = { Text("Save to device") }, leadingIcon = { Icon(Icons.Rounded.BookmarkBorder, null) }, onClick = { menu = false; onSave() })
            if(onRename != null) DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
            if(onDelete != null) DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; onDelete() })
        }
    }
}
@Composable fun ParameterControl(parameter: AnimationParameter, onChange: (Double) -> Unit) {
    var value by remember(parameter) { mutableDoubleStateOf(parameter.value) }
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    if(!parameter.boolean && (!parameter.minimum.isFinite() || !parameter.maximum.isFinite() || parameter.minimum >= parameter.maximum)) {
        ErrorText("${parameter.name} has invalid bounds in this animation."); return
    }
    if(parameter.boolean) Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) { Text(parameter.name,Modifier.weight(1f)); Switch(value != 0.0, { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); value=if(it) 1.0 else 0.0; onChange(value) }, modifier=Modifier.semantics { contentDescription=parameter.name }) }
    else if(parameter.numberInput) {
        var text by remember(parameter) { mutableStateOf("%.2f".format(java.util.Locale.US,value).trimEnd('0').trimEnd('.')) }
        val valid = text.replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in parameter.minimum..parameter.maximum } == true
        OutlinedTextField(text,{ text=it },label={ Text(parameter.name) },supportingText={ Text("${parameter.minimum} – ${parameter.maximum}") },isError=!valid,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),trailingIcon={ IconButton(enabled=valid,onClick={ view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onChange(text.replace(',', '.').toDouble()) }) { Icon(Icons.Rounded.Check,"Apply ${parameter.name}") } })
    } else Column {
        Row(Modifier.fillMaxWidth()) { Text(parameter.name,Modifier.weight(1f)); Text("%.0f".format(value),color=Muted) }
        Slider(value.toFloat(),{ if(!dragging) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); dragging=true; value=it.toDouble() },modifier=Modifier.semantics { contentDescription=parameter.name },valueRange=parameter.minimum.toFloat()..parameter.maximum.toFloat(),onValueChangeFinished={ dragging=false; onChange(value) })
    }
}
