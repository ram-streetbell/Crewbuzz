package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val Red = Color(0xFFDC2626)
private val Green = Color(0xFF16A34A)
private val Slate = Color(0xFF0F172A)
private val Muted = Color(0xFF64748B)

private data class Call(
    val id: Int,
    val table: String,
    val type: String,
    val created: Long,
    val attended: Long? = null,
    val staff: String? = null
)

private data class Device(
    val id: String,
    val name: String,
    val table: String,
    val online: Boolean = true,
    val battery: Int = 100
)

private data class AppState(
    val calls: List<Call>,
    val history: List<Call>,
    val devices: List<Device>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrewBuzzNetwork.start(this)
        setContent { CrewBuzzApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrewBuzzApp() {
    var loggedIn by remember { mutableStateOf(false) }
    var staff by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(10) }
    val context = androidx.compose.ui.platform.LocalContext.current

    var state by remember {
        mutableStateOf(AppState(emptyList(), emptyList(), emptyList()))
    }

    LaunchedEffect(Unit) {
        CrewBuzzNetwork.devices.collectLatest { event ->
            val index = state.devices.indexOfFirst { it.id == event.deviceId }
            val device = Device(
                id = event.deviceId,
                name = "ESP8266 Call Bell",
                table = event.tableId,
                online = true,
                battery = 100
            )
            state = if (index >= 0) {
                state.copy(devices = state.devices.toMutableList().also { it[index] = device })
            } else {
                state.copy(devices = state.devices + device)
            }
        }
    }

    LaunchedEffect(Unit) {
        CrewBuzzNetwork.tableCalls.collectLatest { event ->
            if (!state.calls.any { it.table == event.tableId }) {
                state = state.copy(
                    calls = state.calls + Call(nextId++, event.tableId, event.request, System.currentTimeMillis())
                )
                CrewBuzzAlertService.startCall(context, event.tableId)
            }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Red,
            background = Color(0xFFFDFDFD),
            surface = Color.White
        )
    ) {
        if (!loggedIn) {
            LoginScreen { name ->
                staff = name
                loggedIn = true
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("CrewBuzz", fontWeight = FontWeight.Black, color = Red) },
                        actions = {
                            Text(staff, color = Red, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        val labels = listOf("Dashboard", "Devices", "History", "Settings")
                        val icons = listOf(Icons.Default.Dashboard, Icons.Default.Router, Icons.Default.History, Icons.Default.Settings)
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = page == index,
                                onClick = { page = index },
                                icon = { Icon(icons[index], contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(
                    Modifier.padding(padding).fillMaxSize().background(Color(0xFFFDFDFD))
                ) {
                    when (page) {
                        0 -> Dashboard(
                            state = state,
                            onCall = { table, type ->
                                if (!state.calls.any { it.table == table }) {
                                    state = state.copy(calls = state.calls + Call(nextId++, table, type, System.currentTimeMillis()))
                                    CrewBuzzAlertService.startCall(context, table)
                                }
                            },
                            onSnooze = { table -> CrewBuzzAlertService.snooze(context, table) },
                            onAttend = { id ->
                                val call = state.calls.firstOrNull { it.id == id }
                                if (call != null) {
                                    state = state.copy(
                                        calls = state.calls.filterNot { it.id == id },
                                        history = listOf(call.copy(attended = System.currentTimeMillis(), staff = staff)) + state.history
                                    )
                                    CrewBuzzAlertService.attend(context, call.table)
                                }
                            }
                        )
                        1 -> Devices(
                            state = state,
                            onScan = { CrewBuzzNetwork.scanNow() },
                            onToggle = { id ->
                                state = state.copy(devices = state.devices.map { if (it.id == id) it.copy(online = !it.online) else it })
                            },
                            onAdd = { id, name, table ->
                                val newId = id.ifBlank { "DEV-${state.devices.size + 1}" }
                                val newTable = table.ifBlank { "Table ${state.devices.size + 1}" }
                                val newName = name.ifBlank { "$newTable Call Bell" }
                                if (!state.devices.any { it.id == newId }) {
                                    state = state.copy(devices = state.devices + Device(newId, newName, newTable))
                                }
                            },
                            onDelete = { id -> state = state.copy(devices = state.devices.filterNot { it.id == id }) }
                        )
                        2 -> History(state.history) { state = state.copy(history = emptyList()) }
                        else -> Settings(
                            staff = staff,
                            onReset = {
                                state = state.copy(calls = emptyList())
                                CrewBuzzAlertService.reset(context)
                            },
                            onLogout = {
                                loggedIn = false
                                staff = ""
                                page = 0
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(onLogin: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Color(0xFFFDFDFD)), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(24.dp), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Restaurant, null, tint = Red, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("CrewBuzz Terminal", fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("Waitstaff paging dashboard receiver unit", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Waiter Name / ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    pin, { if (it.length <= 4) pin = it },
                    label = { Text("Station PIN Code") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotEmpty()) Text(error, color = Red, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        when {
                            name.isBlank() -> error = "Enter staff name"
                            pin != "1234" -> error = "Invalid PIN. Demo PIN is 1234"
                            else -> onLogin(name.trim())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("INITIALIZE TERMINAL", fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

@Composable
private fun Dashboard(
    state: AppState,
    onCall: (String, String) -> Unit,
    onSnooze: (String) -> Unit,
    onAttend: (Int) -> Unit
) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }

    val tables = listOf("Table 1", "Table 2", "Table 3", "Table 4", "Table 12", "VIP A", "VIP B", "Bar 1")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("ACTIVE CALLS", state.calls.size.toString(), Red, Modifier.weight(1f))
            StatCard("ONLINE DEVICES", "${state.devices.count { it.online }}/${state.devices.size}", Slate, Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("SIMULATOR PANEL • TRIGGER CALL", color = Red, fontWeight = FontWeight.Black, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tables.forEach { table ->
                        OutlinedButton(onClick = { onCall(table, listOf("SERVICE", "BILL", "WATER", "URGENT").random()) }) {
                            Text(table, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Text("LIVE SERVICE QUEUE", fontWeight = FontWeight.Black, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 10.dp))
        if (state.calls.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(52.dp))
                    Text("ALL RECIPIENTS SERVED", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.calls.sortedWith(compareByDescending<Call> { it.type == "URGENT" }.thenBy { it.created })) { call ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(call.table, fontSize = 17.sp, fontWeight = FontWeight.Black)
                                Text("${call.type} • ${((tick - call.created) / 1000).coerceAtLeast(0)}s ago", color = Muted, fontSize = 11.sp)
                            }
                            OutlinedButton(onClick = { onSnooze(call.table) }) {
                                Icon(Icons.Default.Snooze, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("SNOOZE")
                            }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = { onAttend(call.id) }) {
                                Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("ATTEND")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 10.sp, color = Muted, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 30.sp, color = color, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun Devices(
    state: AppState,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onAdd: (String, String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var add by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var table by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("PAGER & ALERT NETWORK", fontWeight = FontWeight.Black)
                Text("Device status overview", color = Muted, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = {
                    scanning = true
                    onScan()
                },
                enabled = !scanning
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (scanning) "SCANNING..." else "SCAN DEVICES")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { add = !add }) { Text(if (add) "CANCEL" else "REGISTER") }
        }

        LaunchedEffect(scanning) {
            if (scanning) {
                delay(2500)
                scanning = false
            }
        }

        if (scanning) {
            Text("Searching local Wi-Fi for CrewBuzz ESP8266 devices...", color = Red, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (add) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(id, { id = it }, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(name, { name = it }, label = { Text("Display Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(table, { table = it }, label = { Text("Table") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onAdd(id, name, table)
                            id = ""
                            name = ""
                            table = ""
                            add = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("PROVISION NETWORK DEVICE") }
                }
            }
        }

        if (state.devices.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No devices found", fontWeight = FontWeight.Bold)
                    Text("Tap SCAN DEVICES while the ESP8266 is powered on.", color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.devices) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(device.table, fontWeight = FontWeight.Black, color = Red)
                                IconButton(onClick = { onDelete(device.id) }) { Icon(Icons.Default.DeleteOutline, null) }
                            }
                            Text(device.name, fontWeight = FontWeight.Bold)
                            Text("ID: ${device.id}", color = Muted, fontSize = 11.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (device.online) "● ONLINE" else "● OFFLINE", color = if (device.online) Green else Muted, fontWeight = FontWeight.Bold)
                                Text("Battery ${device.battery}%", color = Muted)
                                TextButton(onClick = { onToggle(device.id) }) { Text("TOGGLE") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun History(history: List<Call>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ATTENDED CALL LOG", fontWeight = FontWeight.Black)
            TextButton(onClick = onClear) { Text("CLEAR ALL", color = Red) }
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("History Queue is Empty", color = Muted) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(history) { call ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(call.table, fontWeight = FontWeight.Bold)
                                Text(call.type, color = Muted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(call.staff ?: "Staff", fontWeight = FontWeight.Bold)
                                val sec = ((call.attended ?: call.created) - call.created) / 1000
                                Text("Served in ${sec}s", color = Green, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(staff: String, onReset: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("RECEIVING TERMINAL SETTINGS", fontWeight = FontWeight.Black)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("ACTIVE TERMINAL OPERATOR", color = Red, fontWeight = FontWeight.Bold)
                Text(staff, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Assigned Floor Zone: Main Hall", color = Muted, fontSize = 11.sp)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("HARDWARE PARAMETERS", color = Red, fontWeight = FontWeight.Bold)
                Text("Receiver RF Channel     433.92 Mhz (CH5)")
                Text("Firmware Build          v1.4.1-P1-NoDB")
                Text("AP Link Mode            STANDALONE LOCAL", color = Green)
                Text("Discovery               UDP AUTO-DISCOVERY")
                Text("HTTP Receiver           8080")
                Text("No fixed tablet IP required", color = Green, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("RESET ALL ACTIVE SESSIONS") }
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("DE-AUTHORIZE TERMINAL UNIT") }
    }
}
