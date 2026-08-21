package com.screenstream.unified

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val incomingConfig = mutableStateOf<RoomConfig?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { ScreenStreamApp(incomingConfig.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "screenstream" || data.host != "connect") return

        val server = data.getQueryParameter("server")?.trim().orEmpty()
        val room = data.getQueryParameter("room")?.trim()?.uppercase().orEmpty()
        if (server.isNotBlank() && room.isNotBlank()) {
            incomingConfig.value = RoomConfig(server, room)
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun ScreenStreamApp(deepLinkConfig: RoomConfig?) {
    var tab by remember { mutableIntStateOf(if (deepLinkConfig != null) 1 else 0) }
    var config by remember(deepLinkConfig?.serverUrl, deepLinkConfig?.room) {
        mutableStateOf(deepLinkConfig ?: RoomConfig())
    }
    var status by remember { mutableStateOf("Ready") }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("screenstream", Context.MODE_PRIVATE) }

    LaunchedEffect(deepLinkConfig?.serverUrl, deepLinkConfig?.room) {
        if (deepLinkConfig != null) {
            config = deepLinkConfig
            prefs.edit()
                .putString("server", deepLinkConfig.normalizedServer())
                .putString("room", deepLinkConfig.room.trim().uppercase())
                .apply()
            status = "Setup imported"
        } else {
            val savedServer = prefs.getString("server", null)
            val savedRoom = prefs.getString("room", null)
            if (!savedServer.isNullOrBlank() && !savedRoom.isNullOrBlank()) {
                config = RoomConfig(savedServer, savedRoom)
            }
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("ScreenStream Unified") }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text("Viewer") },
                        icon = { Icon(Icons.Default.Visibility, null) }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text("Stream") },
                        icon = { Icon(Icons.Default.ScreenShare, null) }
                    )
                }
                if (tab == 0) {
                    ViewerTab(config, status = status, onStatus = { status = it })
                } else {
                    StreamTab(
                        config = config,
                        onConfig = {
                            config = it
                            prefs.edit()
                                .putString("server", it.normalizedServer())
                                .putString("room", it.room)
                                .apply()
                        },
                        status = status,
                        onStatus = { status = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerTab(config: RoomConfig, status: String, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val engine = remember(config.normalizedServer(), config.room) {
        ViewerEngine(context, config, onStatus)
    }
    var connected by remember { mutableStateOf(false) }

    DisposableEffect(engine) {
        onDispose { engine.dispose() }
    }

    Column(Modifier.fillMaxSize()) {
        Card(
            Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Live Viewer", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("Room ${config.room}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .background(Color.Black, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { engine.view() },
                modifier = Modifier.fillMaxSize()
            )
            if (status == "Ready" || status.contains("Waiting")) {
                Text("No video yet", color = Color.White)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { engine.start(); connected = true },
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.size(6.dp))
                Text(if (connected) "Reconnect" else "Connect")
            }
            OutlinedButton(
                onClick = { engine.stop(); connected = false },
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.size(6.dp))
                Text("Stop")
            }
        }
    }
}

@Composable
private fun StreamTab(
    config: RoomConfig,
    onConfig: (RoomConfig) -> Unit,
    status: String,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    var server by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var room by remember(config.room) { mutableStateOf(config.room) }
    var sharing by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val finalConfig = RoomConfig(server.trim(), room.trim().uppercase())
            onConfig(finalConfig)
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_SERVER_URL, finalConfig.normalizedServer())
                putExtra(ScreenCaptureService.EXTRA_ROOM_CODE, finalConfig.room)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            sharing = true
            onStatus("Waiting for Viewer…")
        } else {
            onStatus("Screen sharing permission cancelled")
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Stream", style = MaterialTheme.typography.headlineMedium)
        Text("Turn this phone into the sender. Android will ask for screen-capture permission before streaming.")
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Secure signaling server") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = room,
            onValueChange = { room = it.uppercase() },
            label = { Text("Room code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    val finalConfig = RoomConfig(
                        server.trim(),
                        room.trim().uppercase().ifBlank { RoomConfig().room }
                    )
                    server = finalConfig.normalizedServer()
                    room = finalConfig.room
                    onConfig(finalConfig)
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                },
                enabled = !sharing,
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Icon(Icons.Default.ScreenShare, null)
                Spacer(Modifier.size(6.dp))
                Text("Start sharing")
            }
            OutlinedButton(
                onClick = {
                    context.stopService(Intent(context, ScreenCaptureService::class.java))
                    sharing = false
                    onStatus("Stopped")
                },
                enabled = sharing,
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.size(6.dp))
                Text("Stop")
            }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Connection setup", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Server: ${RoomConfig(server, room).normalizedServer()}")
                Text("Room: ${room.ifBlank { "—" }}")
                Text("Status: $status")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "ScreenStream setup",
                            RoomConfig(server, room).deepLink().toString()
                        )
                    )
                    onStatus("Setup link copied")
                }) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Copy setup link")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Internet signaling requires WSS. TURN is intentionally not used in this first release.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
