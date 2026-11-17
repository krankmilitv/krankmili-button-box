package com.krankmili.buttonbox

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.krankmili.buttonbox.data.ConnectionState
import com.krankmili.buttonbox.ui.BoxScreen
import com.krankmili.buttonbox.ui.ConnectScreen
import com.krankmili.buttonbox.ui.Palette

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A button box on the dash must not go to sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Phones often prefer mobile data; the PC is only reachable over Wi-Fi.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }?.let { cm.bindProcessToNetwork(it) }
        } catch (_: Exception) {
        }

        val vm = ViewModelProvider(this)[ButtonBoxViewModel::class.java]

        // Android 17+ blocks LAN traffic for apps targeting API 37 until this permission is granted.
        if (Build.VERSION.SDK_INT >= 37) {
            val perm = "android.permission.ACCESS_LOCAL_NETWORK"
            val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) vm.discover()
            }
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) ask.launch(perm)
        }
        val prefs = getSharedPreferences("buttonbox", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.Accent,
                    onPrimary = Palette.OnAccent,
                    background = Palette.Background,
                    surface = Palette.Background,
                ),
            ) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Palette.Background) {
                    AppRoot(
                        vm = vm,
                        initialHost = prefs.getString("host", "") ?: "",
                        initialPort = prefs.getString("port", "5555") ?: "5555",
                        initialToken = prefs.getString("token", "") ?: "",
                        onSave = { host, port, token ->
                            prefs.edit()
                                .putString("host", host)
                                .putString("port", port)
                                .putString("token", token)
                                .apply()
                        },
                    )
                    // Shown on every launch; the app is usable as soon as it is dismissed.
                    var showTribute by rememberSaveable { mutableStateOf(true) }
                    if (showTribute) {
                        AlertDialog(
                            onDismissRequest = { showTribute = false },
                            containerColor = Palette.Panel,
                            title = null,
                            text = {
                                Text(
                                    "SEZER İŞ CANDIR\nRAHMETLİ EMRE BOZKURT DOSTUMUZU SAYGIYLA ANIYORUZ.\n\nİMZA:\nKRANKMİLİ TV & ORHAN KÖKBUDAK.",
                                    color = Palette.Text,
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showTribute = false }) {
                                    Text("Tamam", color = Palette.Accent)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    vm: ButtonBoxViewModel,
    initialHost: String,
    initialPort: String,
    initialToken: String,
    onSave: (host: String, port: String, token: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val layout by vm.layout.collectAsState()
    val truck by vm.truck.collectAsState()
    val rearOn by vm.rearOn.collectAsState()
    val rearFrame by vm.rearFrame.collectAsState()

    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    var token by remember { mutableStateOf(initialToken) }
    val searching by vm.searching.collectAsState()
    val found by vm.found.collectAsState()
    val searchFailed by vm.searchFailed.collectAsState()

    // First launch without a saved address: look for the PC right away.
    LaunchedEffect(Unit) { if (initialHost.isBlank()) vm.discover() }

    // A server was found: fill the form and connect (token, if any, is already typed in or empty).
    LaunchedEffect(found) {
        val f = found ?: return@LaunchedEffect
        host = f.host
        port = f.port.toString()
        vm.consumeFound()
        if (!f.tokenRequired || token.isNotBlank()) {
            onSave(host, port, token)
            vm.connect(host, f.port, token)
        }
    }

    val statusText = when (val s = state) {
        is ConnectionState.Disconnected ->
            if (searching) "PC aranıyor…" else if (searchFailed) "PC bulunamadı. Bu cihazın IP'si: ${com.krankmili.buttonbox.data.Discovery.localIps.joinToString().ifEmpty { "yok (Wi-Fi bağlı mı?)" }} — PC ile aynı ağda (192.168.1.x) olmalı" else "Bağlı değil"
        is ConnectionState.Connecting ->
            if (s.note.isEmpty()) "Bağlanıyor…" else "Yeniden deneniyor: ${s.note}"
        is ConnectionState.Connected -> "Bağlı · $host:$port"
    }

    // The button screen needs a layout from the PC; until the first one arrives, show the form.
    val showBox = layout.isNotEmpty() && state !is ConnectionState.Disconnected
    if (showBox) {
        BoxScreen(
            allGroups = layout,
            truck = truck,
            connected = state is ConnectionState.Connected,
            statusText = statusText,
            onDisconnect = vm::disconnect,
            onPress = vm::press,
            onRelease = vm::release,
            rearOn = rearOn,
            rearFrame = rearFrame,
        )
    } else {
        ConnectScreen(
            host = host, port = port, token = token,
            statusText = statusText,
            busy = state is ConnectionState.Connecting,
            searching = searching,
            onDiscover = vm::discover,
            onHost = { host = it },
            onPort = { port = it.filter(Char::isDigit) },
            onToken = { token = it },
            onConnect = {
                val p = port.toIntOrNull()
                if (host.isNotBlank() && p != null && p in 1..65535) {
                    onSave(host.trim(), port, token)
                    vm.connect(host.trim(), p, token)
                }
            },
            onCancel = vm::disconnect,
        )
    }
}
