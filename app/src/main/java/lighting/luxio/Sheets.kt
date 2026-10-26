package lighting.luxio

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LuxioSheet(title: String, onDismiss: () -> Unit, locked: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val currentLocked by rememberUpdatedState(locked)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !currentLocked || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if(!locked) onDismiss() }, sheetState = state, containerColor = Ink, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 820.dp).imePadding().padding(horizontal = 22.dp).padding(bottom = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                IconButton(enabled=!locked,onClick=onDismiss) { Icon(Icons.Rounded.Close,"Close") }
            }
            content()
        }
    }
}
@Composable fun AddDeviceSheet(discovery: Discovery, onDismiss: () -> Unit, onConnected: (DeviceClient) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    LuxioSheet("Add Luxio", onDismiss) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(22.dp)) {
            item { Column(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.Lightbulb,null,Modifier.size(46.dp),tint=Accent)
                Text("Create your own Luxio",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                Text("Turn an ESP32 and an LED strip into your own smart light. Our guide will help you get started.",color=Muted,textAlign=TextAlign.Center)
                Button(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://luxio.lighting/"))) },modifier=Modifier.fillMaxWidth().padding(top=6.dp)) { Text("View the guide"); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.OpenInNew,null,Modifier.size(18.dp)) }
            } }
            item { HorizontalDivider(color=Panel) }
            item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Connect by IP address",style=MaterialTheme.typography.titleMedium)
                Text("Nearby devices appear automatically. You can also connect directly by IP address.",style=MaterialTheme.typography.bodySmall,color=Muted)
                OutlinedTextField(address,{ address=it; error=null },label={ Text("IP address or hostname") },placeholder={ Text("192.168.1.42") },singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("device-address"))
                ErrorText(error)
                OutlinedButton(enabled=address.isNotBlank() && !busy,onClick={ busy=true; scope.launch { try { onConnected(discovery.addAddress(address)) } catch(e: Exception) { if(e is CancellationException) throw e; error=e.message } finally { busy=false } } },modifier=Modifier.fillMaxWidth()) { if(busy) { CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp); Spacer(Modifier.width(10.dp)) }; Text(if(busy) "Connecting…" else "Connect") }
            } }
            item { TextButton(onClick={ onConnected(discovery.addSimulator()) },modifier=Modifier.fillMaxWidth().testTag("add-virtual")) { Text("Add a virtual device",color=Muted,style=MaterialTheme.typography.bodySmall) } }
        }
    }
}
@Composable fun SettingsSheet(client: DeviceClient, discovery: Discovery, onDismiss: () -> Unit, onRemoved: () -> Unit) {
    val state=client.state ?: return
    var config by remember { mutableStateOf(state) }; var count by remember { mutableStateOf(state.count.toString()) }; var gpio by remember { mutableStateOf(state.gpio.toString()) }; var relayPin by remember { mutableStateOf(state.relayGpio.toString()) }
    var route by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var confirmWifi by remember { mutableStateOf(false) }; var available by remember { mutableIntStateOf(0) }; var upToDate by remember { mutableStateOf(false) }
    var stripMenu by remember { mutableStateOf(false) }; var remove by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope(); val view=LocalView.current
    val save: () -> Unit = {
        busy=true; error=null; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        scope.launch { try { client.request(3,Proto().data(5,config.copy(count=count.toInt(),gpio=gpio.toInt(),relayGpio=relayPin.toIntOrNull() ?: 0).config().bytes.toByteArray())); onDismiss() } catch(e: Exception) { if(e is CancellationException) throw e; error=e.message } finally { busy=false } }
    }
    when(route) {
        "wifi" -> WifiSheet(client) { route=null }
        "matter" -> MatterSheet(client) { route=null }
        "update" -> UpdateSheet(client,available) { route=null }
        else -> LuxioSheet("Settings",onDismiss,locked=busy) {
            LazyColumn(Modifier.weight(1f,false).testTag("settings-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { SectionHeading("Connection"); SettingsGroup {
                    SettingsRow("Type",client.connectionName,if(client.usingBluetooth) Icons.Rounded.Bluetooth else if(client.simulator!=null) Icons.Rounded.Science else Icons.Rounded.Wifi)
                    if(client.simulator==null) SettingsRow("Address",state.ip.ifEmpty { client.address.removePrefix("http://") })
                } }
                item { SectionHeading("LED Strip"); SettingsGroup {
                    SettingsRow("Name", config.name, onValueChange = { config = config.copy(name = it) }, enabled = !busy, fieldLabel = "Device name")
                    Box { ListItem(headlineContent={ Text("Strip type") },trailingContent={ Row(verticalAlignment=Alignment.CenterVertically) { Text(config.strip,color=Muted,style=MaterialTheme.typography.bodyLarge); Icon(Icons.Rounded.ExpandMore,null) } },modifier=Modifier.clickable { stripMenu=true },colors=ListItemDefaults.colors(containerColor=Panel))
                        DropdownMenu(expanded=stripMenu,onDismissRequest={ stripMenu=false }) { listOf("WS2812","WS2814","WS2805","SK6812").forEach { strip -> DropdownMenuItem(text={ Text(strip) },onClick={ config=config.copy(strip=strip); stripMenu=false }) } }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = Color.White.copy(alpha = .08f))
                    SettingsRow("LED count", count, onValueChange = { count = it.filter(Char::isDigit).take(4) }, number = true, invalid = count.toIntOrNull() !in 1..1024, enabled = !busy)
                    SettingsRow("Data GPIO", gpio, onValueChange = { gpio = it.filter(Char::isDigit).take(2) }, number = true, invalid = gpio.toIntOrNull() !in 0..54, enabled = !busy)
                } }
                item { SectionHeading("Relay"); SettingsGroup {
                    SwitchRow("Enable Relay",config.relayEnabled,{ config=config.copy(relayEnabled=it) },enabled=state.build>=27)
                    if(config.relayEnabled) { SettingsRow("Relay GPIO", relayPin, onValueChange = { relayPin = it.filter(Char::isDigit).take(2) }, number = true, invalid = relayPin.toIntOrNull() !in 0..54 || relayPin == gpio, enabled = !busy); SwitchRow("Invert Relay",config.relayInverted,{ config=config.copy(relayInverted=it) }) }
                }; Text("Some controllers use a relay to switch power to the LED strip. Set its GPIO to match your controller; invert it if the relay is active-low.",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.padding(top=8.dp)) }
                if(client.simulator==null) {
                    item { SectionHeading("Wi-Fi"); SettingsGroup {
                        SwitchRow("Enable Wi-Fi",config.wifiEnabled,{ enabled -> config=config.copy(wifiEnabled=enabled) },enabled=state.build>=34)
                        if(config.wifiEnabled) {
                            SettingsRow("Network",client.network.text(11).ifEmpty { "Not connected" })
                            SettingsRow("IP address",state.ip.ifEmpty { "—" })
                            SettingsRow("Gateway",client.network.text(12).ifBlank { "—" })
                            SettingsRow("DNS", client.network.fields[13]?.joinToString(", ") { it.toString(Charsets.UTF_8) }?.ifBlank { "—" } ?: "—")
                            TextButton(onClick={ route="wifi" },enabled=state.wifiEnabled,modifier=Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Configure Wi-Fi", Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp)) }
                        }
                    }; Text("Changing this setting restarts Luxio and releases Wi-Fi resources when disabled. Your network is retained. Re-enable Wi-Fi using Bluetooth or USB.",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.padding(top=8.dp)) }
                    if(state.supportsMatter) item { SectionHeading("Matter"); SettingsGroup {
                        SwitchRow("Enable Matter",config.matterEnabled,{ config=config.copy(matterEnabled=it) },enabled=state.build>=34)
                        if(config.matterEnabled && config.wifiEnabled) TextButton(onClick={ route="matter" },enabled=state.matterEnabled && state.wifiEnabled,modifier=Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Show pairing code", Modifier.weight(1f)); Icon(Icons.Rounded.QrCode2,null,Modifier.size(20.dp)) }
                    }; Text("Connect Luxio to your smart home. Matter starts after Wi-Fi connects. Disabling it releases its resources after a restart; pairings are retained.",style=MaterialTheme.typography.bodySmall,color=Muted,modifier=Modifier.padding(top=8.dp)) }
                    item { SectionHeading("Firmware"); SettingsGroup {
                        SettingsRow("Build",state.build.toString())
                        TextButton(enabled=!busy,onClick={ busy=true; error=null; scope.launch { try {
                            var response=client.request(13)
                            val deadline=System.currentTimeMillis()+45000
                            while(response.number(9)!=0 && System.currentTimeMillis()<deadline) { delay(1000); response=client.request(0) }
                            check(response.number(9)==0) { "Update check timed out. Try again." }; check(response.text(10).isEmpty()) { response.text(10) }
                            available=response.number(8); if(available>0) route="update" else upToDate=true
                        } catch(e: Exception) { if(e is CancellationException) throw e; error=e.message } finally { busy=false } } },modifier=Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { if(busy) { CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp); Spacer(Modifier.width(8.dp)) }; Text(if(busy) "Checking…" else "Check for updates", Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp)) }
                    } }
                } else item { SectionHeading("Simulator"); SettingsGroup { Text("This virtual device runs locally. Wi-Fi, Matter pairing and firmware updates require a physical Luxio.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(16.dp)); HorizontalDivider(Modifier.padding(start = 16.dp), color = Color.White.copy(alpha = .08f)); TextButton(onClick={ remove=true }, modifier=Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(0.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Remove virtual device",color=MaterialTheme.colorScheme.error, modifier=Modifier.weight(1f)); Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) } } }
            }
            ErrorText(error); Button(enabled=!busy && config.name.isNotBlank() && config.name.toByteArray().size<=63 && count.toIntOrNull() in 1..1024 && gpio.toIntOrNull() in 0..54 && (!config.relayEnabled || relayPin.toIntOrNull() in 0..54 && relayPin.toIntOrNull()!=gpio.toIntOrNull()),onClick={ if(!config.wifiEnabled && state.wifiEnabled && !client.usingBluetooth && client.simulator==null) confirmWifi=true else save() },modifier=Modifier.fillMaxWidth()) { Text(if(busy) "Saving…" else "Save settings") }; Text("Changes to hardware or connectivity restart your device.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=10.dp,bottom=16.dp))
        }
    }
    if(confirmWifi) AlertDialog(onDismissRequest={ confirmWifi=false },title={ Text("Disable Wi-Fi?") },text={ Text("You’re connected over Wi-Fi. Saving this change will disconnect the app. You’ll need Bluetooth or USB to enable Wi-Fi again.") },confirmButton={ TextButton(onClick={ confirmWifi=false; save() }) { Text("Disable Wi-Fi") } },dismissButton={ TextButton(onClick={ confirmWifi=false }) { Text("Cancel") } })
    if(upToDate) AlertDialog(onDismissRequest={ upToDate=false },title={ Text("Up to date") },text={ Text("Your Luxio is running the latest firmware.") },confirmButton={ TextButton(onClick={ upToDate=false }) { Text("Done") } })
    if(remove) AlertDialog(onDismissRequest={ remove=false },title={ Text("Remove virtual device?") },text={ Text("Your saved virtual device will be available when you add it again.") },confirmButton={ TextButton(onClick={ discovery.removeSimulator(); onRemoved() }) { Text("Remove") } },dismissButton={ TextButton(onClick={ remove=false }) { Text("Cancel") } })
}
@Composable fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) { Surface(shape=RoundedCornerShape(15.dp),color=Panel,modifier=Modifier.fillMaxWidth()) { Column(content=content) } }
@Composable fun SettingsRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onValueChange: ((String) -> Unit)? = null, number: Boolean = false, invalid: Boolean = false, enabled: Boolean = true, fieldLabel: String = label) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if(icon != null) Icon(icon, null, Modifier.size(20.dp), tint = Muted)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if(onValueChange == null) Text(value, color = Muted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        else BasicTextField(value, onValueChange, enabled = enabled, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End, color = if(invalid) MaterialTheme.colorScheme.error else Color.White), keyboardOptions = KeyboardOptions(keyboardType = if(number) KeyboardType.Number else KeyboardType.Text), cursorBrush = SolidColor(Accent), modifier = Modifier.weight(1f).padding(vertical = 16.dp).semantics { contentDescription = fieldLabel; if(invalid) error("Invalid $label") }.testTag("setting-$fieldLabel"))
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = Color.White.copy(alpha = .08f))
}
@Composable fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) { Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically) { Text(label,Modifier.weight(1f)); Switch(checked,onChange,enabled=enabled,modifier=Modifier.semantics { contentDescription=label }) }; HorizontalDivider(Modifier.padding(start = 16.dp), color = Color.White.copy(alpha = .08f)) }
