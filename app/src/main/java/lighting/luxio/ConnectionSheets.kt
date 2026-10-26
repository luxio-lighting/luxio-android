package lighting.luxio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

@Composable fun WifiSheet(client: DeviceClient, onDismiss: () -> Unit) {
    var networks by remember { mutableStateOf<List<Fields>>(emptyList()) }; var action by remember { mutableStateOf("scan") }
    var choosing by remember { mutableStateOf(true) }; var manual by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var secured by remember { mutableStateOf(true) }; var showPassword by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    val valid = ssid.isNotEmpty() && ssid.toByteArray().size<=32 && (!secured || password.toByteArray().size in 8..63 || password.length==64 && password.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
    LaunchedEffect(action) {
        if(action.isEmpty()) return@LaunchedEffect
        error=null
        try {
            if(action=="scan") {
                var response=client.request(16); val deadline=System.currentTimeMillis()+30000
                while(response.number(13)!=0 && System.currentTimeMillis()<deadline) { delay(1000); try { response=client.request(0,timeout=5000) } catch(e: Exception) { if(e is CancellationException) throw e } }
                check(response.number(13)==0) { "The scan timed out. Try again or enter your network manually." }
                check(response.text(14).isEmpty()) { "Couldn't scan networks. Try again or enter your network manually." }
                networks=response.messages(12).filter { it.text(1).isNotBlank() }.sortedByDescending { val r=it.number(2); (r ushr 1) xor -(r and 1) }.distinctBy { it.text(1) }
            } else {
                client.request(4,Proto().data(6,Proto().text(1,ssid).text(2,if(secured) password else "").bytes.toByteArray()))
                val deadline=System.currentTimeMillis()+60000
                while(!connected && System.currentTimeMillis()<deadline) {
                    delay(2000)
                    try { client.request(0,timeout=5000); connected=client.network.number(1)!=0 && client.state?.ip?.let { it.isNotEmpty() && it!="0.0.0.0" }==true && client.network.text(11)==ssid }
                    catch(e: Exception) { if(e is CancellationException) throw e }
                }
                check(connected) { "Luxio couldn't connect. Check the password and network, then try again. If its IP changed, reconnect from the device list." }
                password=""; showPassword=false
            }
        } catch(e: Exception) { if(e is CancellationException) throw e; error=e.message }
        finally { action="" }
    }
    LuxioSheet("Configure Wi-Fi",onDismiss,locked=action=="connect") {
        LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Column(Modifier.fillMaxWidth().padding(vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Icon(if(connected) Icons.Rounded.CheckCircle else Icons.Rounded.Wifi,null,Modifier.size(46.dp),tint=Accent)
                Text(if(connected) "You’re connected" else if(action=="connect") "Connecting to Wi-Fi" else if(choosing) "Choose a network" else "Join your network",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                Text(if(connected) "Luxio is now connected to $ssid." else if(action=="connect") "Joining $ssid…\nKeep Luxio powered on and stay nearby." else if(choosing) "Choose the network you want Luxio to use." else if(secured) "Enter the password for ${ssid.ifEmpty { "your network" }}." else "This network doesn’t require a password.",color=Muted,textAlign=TextAlign.Center)
                if(action=="connect") CircularProgressIndicator(Modifier.padding(12.dp))
            } }
            if(!connected && action!="connect") {
                if(choosing) {
                    item { SectionHeading("Nearby networks") { IconButton(onClick={ action="scan" },enabled=action!="scan") { Icon(Icons.Rounded.Refresh,"Scan again") } }; if(action=="scan") LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if(networks.isEmpty() && action.isEmpty()) item { Text("No networks found. Try again, or enter a network manually.",color=Muted) }
                    items(networks,key={ it.text(1) }) { network ->
                        val r=network.number(2); val rssi=(r ushr 1) xor -(r and 1)
                        ListItem(headlineContent={ Text(network.text(1)) },supportingContent={ Text(if(rssi>=-55) "Strong signal" else if(rssi>=-70) "Fair signal" else "Weak signal") },leadingContent={ Icon(Icons.Rounded.Wifi,null,tint=Muted) },trailingContent={ if(network.number(3)!=0) Icon(Icons.Rounded.Lock,null,Modifier.size(18.dp),tint=Muted) },modifier=Modifier.clickable { ssid=network.text(1); secured=network.number(3)!=0; password=""; choosing=false; manual=false; error=null; action="" },colors=ListItemDefaults.colors(containerColor=Panel))
                    }
                    item { TextButton(onClick={ choosing=false; manual=true; ssid=""; password=""; secured=true; error=null; action="" }) { Text("Other network…") }; Text("For hidden networks, enter the name manually.",color=Muted,style=MaterialTheme.typography.bodySmall) }
                } else {
                    if(manual) item { OutlinedTextField(ssid,{ ssid=it; error=null },label={ Text("Network name") },singleLine=true,modifier=Modifier.fillMaxWidth().testTag("wifi-ssid")); SwitchRow("Password required",secured,{ secured=it; password="" }) }
                    else item { SettingsGroup { SettingsRow("Network",ssid,Icons.Rounded.Wifi) } }
                    if(secured) item { OutlinedTextField(password,{ password=it; error=null },label={ Text("Password") },singleLine=true,visualTransformation=if(showPassword) VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={ IconButton(onClick={ showPassword=!showPassword }) { Icon(if(showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,if(showPassword) "Hide password" else "Show password") } },supportingText={ if(password.isNotEmpty() && !valid) Text("Use 8–63 bytes, or a 64-digit hexadecimal key.") },modifier=Modifier.fillMaxWidth().testTag("wifi-password")) }
                    item { TextButton(onClick={ choosing=true; error=null }) { Text("Choose another network") } }
                }
            }
            item { ErrorText(error) }
        }
        if(connected || !choosing && action!="connect") Button(enabled=connected || valid,onClick={ if(connected) onDismiss() else action="connect" },modifier=Modifier.fillMaxWidth().padding(top=12.dp)) { Text(if(connected) "Done" else "Connect to Wi-Fi") }
    }
}
@Composable fun MatterSheet(client: DeviceClient, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }; var qr by remember { mutableStateOf<Bitmap?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var copied by remember { mutableStateOf(false) }
    val context=LocalContext.current
    LaunchedEffect(client) { try {
        val response=client.request(12); code=response.text(6)
        check(code.isNotEmpty()) { "Pairing is not available. Check that Wi-Fi and Matter are enabled." }
        if(response.text(7).isNotEmpty()) qr=withContext(Dispatchers.Default) {
            val bits=QRCodeWriter().encode(response.text(7),BarcodeFormat.QR_CODE,600,600)
            Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888).apply { for(y in 0 until 600) for(x in 0 until 600) setPixel(x,y,if(bits[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) }
        }
    } catch(e: Exception) { if(e is CancellationException) throw e; error=e.message } }
    LuxioSheet("Connect with Matter",onDismiss) {
        Column(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Text("Bring Luxio into your smart home.",style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
            Text("Scan this code using your smart home app, or enter the setup code manually.",color=Muted,textAlign=TextAlign.Center)
            if(qr!=null) Image(qr!!.asImageBitmap(),"Matter pairing QR code",Modifier.size(240.dp))
            else if(error==null) CircularProgressIndicator()
            if(code.isNotEmpty()) { Text(code,style=MaterialTheme.typography.headlineMedium); OutlinedButton(onClick={ context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Matter setup code",code)); copied=true }) { Icon(Icons.Rounded.ContentCopy,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if(copied) "Copied" else "Copy setup code") } }
            ErrorText(error)
        }
    }
}
@Composable fun UpdateSheet(client: DeviceClient, build: Int, onDismiss: () -> Unit) {
    var installing by remember { mutableStateOf(false) }; var complete by remember { mutableStateOf(false) }; var progress by remember { mutableFloatStateOf(0f) }; var error by remember { mutableStateOf<String?>(null) }; var stage by remember { mutableStateOf("Preparing your Luxio…") }; var started by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val recovery=client.address.takeIf { it.startsWith("http") || !it.contains("://") }?.let { if(it.startsWith("http")) it.trimEnd('/') else "http://$it" }
    LaunchedEffect(started) {
        if(!started) return@LaunchedEffect
        installing=true; error=null
        try {
            // An interrupted acknowledgement can mean the device has already restarted.
            try { client.request(14,timeout=5000) } catch(e: Exception) { if(e is CancellationException) throw e; if(e !is java.io.IOException && e.message?.contains("disconnected",true)!=true) throw e }
            val deadline=System.currentTimeMillis()+300000
            while(!complete && System.currentTimeMillis()<deadline) {
                delay(2000)
                try { client.request(0,timeout=2000); complete=client.state?.build==build } catch(e: Exception) { if(e is CancellationException) throw e }
                if(complete) break
                if(recovery!=null) {
                    val status=withContext(Dispatchers.IO) {
                        val connection=URI("$recovery/status").toURL().openConnection() as HttpURLConnection
                        try { connection.connectTimeout=2000; connection.readTimeout=2000; if(connection.responseCode!=200) null else {
                            val bytes=connection.inputStream.use { input ->
                                val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(2048)
                                while(output.size()<=16384) { val count=input.read(buffer,0,minOf(buffer.size,16385-output.size())); if(count<0) break; output.write(buffer,0,count) }
                                output.toByteArray()
                            }; if(bytes.size>16384) null else JSONObject(bytes.toString(Charsets.UTF_8))
                        } } catch(_: Exception) { null } finally { connection.disconnect() }
                    }
                    if(status!=null) {
                        check(status.optString("error").isEmpty()) { status.optString("error") }
                        val size=status.optLong("size"); val received=status.optLong("received")
                        if(size>0) progress=(received.toFloat()/size).coerceIn(0f,1f)
                        stage=if(progress>=1f) "Verifying firmware and restarting…" else if(received>0) "Downloading firmware…" else "Starting the recovery loader…"
                    } else stage=if(progress>=1f) "Waiting for Luxio to reconnect…" else "Restarting into the recovery loader…"
                }
            }
            check(complete) { "Luxio hasn’t reconnected with build $build. Keep it powered on and check the recovery page or reconnect from the device list." }
            progress=1f; stage="Your Luxio is up to date."
        } catch(e: Exception) { if(e is CancellationException) throw e; error=e.message ?: "The update could not be completed" }
        finally { installing=false }
    }
    LuxioSheet("Firmware update",onDismiss,locked=installing) {
        LazyColumn(verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item { Column(Modifier.fillMaxWidth().padding(vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Icon(if(complete) Icons.Rounded.CheckCircle else if(error!=null) Icons.Rounded.ErrorOutline else Icons.Rounded.SystemUpdate,null,Modifier.size(48.dp),tint=Accent)
                Text(if(complete) "Update installed" else if(error!=null) "Update interrupted" else if(installing) "Installing update" else "Update available",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
                Text(if(complete) "Build $build is installed and running." else if(installing) stage else if(error!=null) "Keep Luxio powered on while you check its status." else "Build $build is ready to install. Luxio will briefly go offline during the update.",color=Muted,textAlign=TextAlign.Center)
            } }
            if(installing) item { if(progress>0) LinearProgressIndicator(progress={ progress },modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if(progress>0) "${(progress*100).toInt()}% downloaded" else "Preparing…",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp)); Text("Keep Luxio powered on. This can take a few minutes.",color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp)) }
            item { ErrorText(error) }
            if(!installing) item {
                if(error!=null && recovery!=null) OutlinedButton(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(recovery))) },modifier=Modifier.fillMaxWidth()) { Text("Open recovery page"); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.OpenInNew,null,Modifier.size(18.dp)) }
                Button(onClick={ if(complete || error!=null) onDismiss() else started=true },modifier=Modifier.fillMaxWidth().testTag("install-update")) { Text(if(complete) "Done" else if(error!=null) "Close" else "Install") }
            }
        }
    }
}
