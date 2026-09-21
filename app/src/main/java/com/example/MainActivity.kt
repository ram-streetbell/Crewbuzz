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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private val Brand = Color(0xFFE11D48)
private val BrandDark = Color(0xFF9F1239)
private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF64748B)
private val Surface = Color(0xFFF8FAFC)
private val Success = Color(0xFF16A34A)

private data class Call(val id: Int, val table: String, val type: String, val created: Long, val attended: Long? = null, val staff: String? = null)
private data class Device(val id: String, val name: String, val table: String, val ip: String, val online: Boolean = true)

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
    var nextId by remember { mutableIntStateOf(1) }
    var calls by remember { mutableStateOf(emptyList<Call>()) }
    var history by remember { mutableStateOf(emptyList<Call>()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val discovered by CrewBuzzNetwork.devices.collectAsState()

    LaunchedEffect(Unit) {
        CrewBuzzNetwork.tableCalls.collectLatest { event ->
            if (calls.none { it.table == event.tableId }) {
                calls = calls + Call(nextId++, event.tableId, event.request, System.currentTimeMillis())
                CrewBuzzAlertService.startCall(context, event.tableId)
            }
        }
    }

    val devices = discovered.values.map { Device(it.deviceId, "ESP8266 Call Bell", it.tableId, it.ip) }

    MaterialTheme(colorScheme = lightColorScheme(primary = Brand, secondary = BrandDark, background = Surface, surface = Color.White)) {
        if (!loggedIn) {
            LoginScreen { name -> staff = name; loggedIn = true }
        } else {
            Scaffold(
                containerColor = Surface,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(com.example.R.drawable.ic_crewbuzz), null, Modifier.size(34.dp))
                                Spacer(Modifier.width(10.dp))
                                Column { Text("CrewBuzz", fontWeight = FontWeight.Black, color = Ink); Text("SERVICE TERMINAL", fontSize = 9.sp, color = Brand, fontWeight = FontWeight.Bold) }
                            }
                        },
                        actions = { Text(staff, color = Brand, fontWeight = FontWeight.Bold); Spacer(Modifier.width(16.dp)) }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        listOf("Home" to Icons.Default.Home, "Devices" to Icons.Default.Router, "History" to Icons.Default.History, "Settings" to Icons.Default.Settings).forEachIndexed { i, item ->
                            NavigationBarItem(page == i, { page = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (page) {
                        0 -> Dashboard(calls, devices.size, { table, type ->
                            if (calls.none { it.table == table }) {
                                calls = calls + Call(nextId++, table, type, System.currentTimeMillis())
                                CrewBuzzAlertService.startCall(context, table)
                            }
                        }, { CrewBuzzAlertService.snooze(context, it) }, { id ->
                            calls.firstOrNull { it.id == id }?.let { call ->
                                calls = calls.filterNot { it.id == id }
                                history = listOf(call.copy(attended = System.currentTimeMillis(), staff = staff)) + history
                                CrewBuzzAlertService.attend(context, call.table)
                            }
                        })
                        1 -> DevicesScreen(devices) { CrewBuzzNetwork.scanNow() }
                        2 -> HistoryScreen(history) { history = emptyList() }
                        else -> SettingsScreen(staff, { calls = emptyList(); CrewBuzzAlertService.reset(context) }, { loggedIn = false; staff = ""; page = 0 })
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
    Box(Modifier.fillMaxSize().background(Surface), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().padding(24.dp), shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(8.dp)) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(com.example.R.drawable.ic_crewbuzz), null, Modifier.size(86.dp))
                Spacer(Modifier.height(14.dp)); Text("CrewBuzz", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Ink); Text("Restaurant service command terminal", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(28.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Staff name / ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(pin, { if (it.length <= 4) pin = it }, label = { Text("Station PIN") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = Brand, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(22.dp))
                Button(onClick = { if (name.isBlank()) error = "Enter staff name" else if (pin != "1234") error = "Invalid PIN" else onLogin(name.trim()) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Login, null); Spacer(Modifier.width(8.dp)); Text("ENTER TERMINAL", fontWeight = FontWeight.ExtraBold) }
                Spacer(Modifier.height(10.dp)); Text("Local Wi-Fi • No cloud required", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Dashboard(calls: List<Call>, deviceCount: Int, onCall: (String, String) -> Unit, onSnooze: (String) -> Unit, onAttend: (Int) -> Unit) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick = System.currentTimeMillis() } }
    val tables = listOf("Table 1", "Table 2", "Table 3", "Table 4")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) { MetricCard("ACTIVE CALLS", calls.size.toString(), Brand, Modifier.weight(1f)); MetricCard("DEVICES ONLINE", deviceCount.toString(), Success, Modifier.weight(1f)) } }
        item { Card(shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.TouchApp, null, tint = Brand); Spacer(Modifier.width(8.dp)); Text("TEST A TABLE CALL", fontWeight = FontWeight.Black, color = Ink) }; Text("Use this only for testing the terminal.", color = Muted, fontSize = 11.sp); Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) { tables.forEach { table -> OutlinedButton(onClick = { onCall(table, "SERVICE") }, modifier = Modifier.weight(1f)) { Text(table.removePrefix("Table ")) } } } } } }
        item { Text("LIVE SERVICE QUEUE", fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = Ink) }
        if (calls.isEmpty()) item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(34.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.CheckCircle, null, tint = Success, Modifier.size(52.dp)); Spacer(Modifier.height(8.dp)); Text("ALL TABLES SERVED", fontWeight = FontWeight.Bold); Text("Waiting for the next call", color = Muted, fontSize = 12.sp) } } }
        items(calls.sortedBy { it.created }) { call -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = Brand.copy(alpha = .1f), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.RoomService, null, tint = Brand, Modifier.padding(12.dp)) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(call.table, fontSize = 18.sp, fontWeight = FontWeight.Black); Text("${call.type} • ${((tick - call.created) / 1000).coerceAtLeast(0)}s", color = Muted, fontSize = 11.sp) }; OutlinedButton(onClick = { onSnooze(call.table) }) { Icon(Icons.Default.Snooze, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("SNOOZE") }; Spacer(Modifier.width(6.dp)); Button(onClick = { onAttend(call.id) }) { Icon(Icons.Default.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("ATTEND") } } } }
    }
}

@Composable private fun MetricCard(title: String, value: String, tint: Color, modifier: Modifier) { Card(modifier, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp)) { Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(value, color = tint, fontSize = 32.sp, fontWeight = FontWeight.Black) } } }

@Composable
private fun DevicesScreen(devices: List<Device>, onScan: () -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    LaunchedEffect(scanning) { if (scanning) { delay(4000); scanning = false } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Card(shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("DEVICE NETWORK", fontWeight = FontWeight.Black); Text("ESP8266 table call bells", color = Muted, fontSize = 11.sp) }; Button(enabled = !scanning, onClick = { scanning = true; onScan() }) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(5.dp)); Text(if (scanning) "SCANNING" else "SCAN") } } } }
        if (devices.isEmpty()) item { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.WifiFind, null, tint = Muted, Modifier.size(50.dp)); Spacer(Modifier.height(8.dp)); Text("No CrewBuzz devices yet", fontWeight = FontWeight.Bold); Text("Keep the ESP8266 powered on and tap SCAN.", color = Muted, fontSize = 12.sp) } } }
        items(devices) { device -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Surface(color = Success.copy(alpha = .12f), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Router, null, tint = Success, Modifier.padding(10.dp)) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(device.table, fontWeight = FontWeight.Black, fontSize = 18.sp); Text(device.name, color = Muted, fontSize = 12.sp) }; Text("ONLINE", color = Success, fontWeight = FontWeight.Bold, fontSize = 11.sp) }; Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp)); Text("Device ID: ${device.id}", fontSize = 12.sp); Text("IP address: ${device.ip}", fontSize = 12.sp, color = Muted) } } }
    }
}

@Composable private fun HistoryScreen(history: List<Call>, onClear: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("SERVICE HISTORY", fontWeight = FontWeight.Black, fontSize = 20.sp); Text("Completed table requests", color = Muted, fontSize = 11.sp) }; if (history.isNotEmpty()) TextButton(onClick = onClear) { Text("CLEAR") } }; Spacer(Modifier.height(12.dp)); if (history.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No completed calls yet", color = Muted) } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(history) { call -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Success); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(call.table, fontWeight = FontWeight.Bold); Text("${call.type} • ${call.staff ?: "Staff"}", color = Muted, fontSize = 11.sp) }; Text("DONE", color = Success, fontWeight = FontWeight.Bold, fontSize = 10.sp) } } } } } }

@Composable private fun SettingsScreen(staff: String, onReset: () -> Unit, onLogout: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SETTINGS", fontWeight = FontWeight.Black, fontSize = 22.sp); Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("CURRENT OPERATOR", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(staff, fontWeight = FontWeight.Bold, fontSize = 18.sp); Spacer(Modifier.height(8.dp)); Text("Network mode: Local Wi-Fi", color = Muted, fontSize = 12.sp); Text("Station PIN: Demo mode", color = Muted, fontSize = 12.sp) } }; OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(8.dp)); Text("RESET ACTIVE CALLS") }; Button(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("LOG OUT") } } }
