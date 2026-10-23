package com.example.unoparty

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: GameVM by viewModels()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.permissionsReady()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permLauncher.launch(neededPermissions())
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { UnoApp(vm) } } }
    }

    private fun neededPermissions(): Array<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += Manifest.permission.BLUETOOTH_ADVERTISE
            p += Manifest.permission.BLUETOOTH_CONNECT
            p += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT <= 32) p += Manifest.permission.ACCESS_FINE_LOCATION
        return p.toTypedArray()
    }
}

fun UnoColor.ui(): Color = when (this) {
    UnoColor.RED -> Color(0xFFD32F2F)
    UnoColor.YELLOW -> Color(0xFFF9A825)
    UnoColor.GREEN -> Color(0xFF388E3C)
    UnoColor.BLUE -> Color(0xFF1976D2)
    UnoColor.WILD -> Color(0xFF212121)
}

@Composable
fun UnoApp(vm: GameVM) {
    when (vm.screen) {
        Screen.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        Screen.LOGIN -> LoginScreen(vm)
        Screen.HOME -> HomeScreen(vm)
        Screen.ROOM -> RoomScreen(vm)
        Screen.GAME -> GameScreen(vm)
    }
}

@Composable
fun LoginScreen(vm: GameVM) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("UNO Party", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("Data stays on this phone", fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Player name") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pin, { if (it.length <= 4 && it.all(Char::isDigit)) pin = it },
            label = { Text("4-digit PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button({ vm.login(name, pin) }) { Text("Login / Create account") }
    }
}

@Composable
fun HomeScreen(vm: GameVM) {
    val u = vm.user ?: return
    LaunchedEffect(vm.permsOk) { if (vm.permsOk) vm.startScan() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Hi ${u.name}", style = MaterialTheme.typography.headlineMedium)
        Text("Wins ${u.wins} · Rounds played ${u.played}")
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.createRoom() }) { Text("Create room") }
            TextButton({ vm.logout() }) { Text("Switch user") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Rooms nearby", style = MaterialTheme.typography.titleMedium)
        if (!vm.permsOk) Text("Allow Nearby/Bluetooth permissions to find rooms")
        else if (vm.rooms.isEmpty()) Text("Searching… ask a friend to create a room")
        LazyColumn {
            items(vm.rooms.toList()) { (id, name) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, fontSize = 18.sp)
                    Button({ vm.join(id) }) { Text("Join") }
                }
            }
        }
    }
}

@Composable
fun RoomScreen(vm: GameVM) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Room lobby", style = MaterialTheme.typography.headlineMedium)
        Text("${vm.players.size} / 10 players")
        Spacer(Modifier.height(12.dp))
        vm.players.forEachIndexed { i, p -> Text("• $p${if (i == 0) " (host)" else ""}", fontSize = 18.sp) }
        Spacer(Modifier.height(24.dp))
        if (vm.isHost) {
            Button({ vm.startGame() }, enabled = vm.players.size >= 2) { Text("Start game") }
            if (vm.players.size < 2) Text("Need at least 2 players", fontSize = 12.sp)
        } else Text("Waiting for host to start…")
        TextButton({ vm.leave() }) { Text("Leave room") }
    }
}

@Composable
fun CardView(c: Card, big: Boolean = false, onClick: (() -> Unit)? = null) {
    val w = if (big) 90.dp else 60.dp
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(w, w * 1.5f).clip(shape).background(c.color.ui())
            .border(2.dp, Color.White, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(c.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 30.sp else 20.sp)
    }
}

@Composable
fun GameScreen(vm: GameVM) {
    val v = vm.view ?: return
    var pick by remember { mutableStateOf<Int?>(null) }
    val myTurn = v.cur == v.me && v.win < 0 && !v.chal

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (v.dir == 1) "Clockwise ↻" else "Counter-clockwise ↺")
                TextButton({ vm.leave() }) { Text("Leave") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(v.names) { i, name ->
                    if (i != v.me) Column(
                        Modifier.background(
                            if (i == v.cur) Color(0xFFFFE082) else Color(0xFFEEEEEE),
                            RoundedCornerShape(8.dp)
                        ).padding(8.dp)
                    ) {
                        Text(name, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text("${v.counts[i]} cards · ${v.scores[i]} pts", fontSize = 12.sp, color = Color.Black)
                        if (v.vuln == i) Button({ vm.send(Msg.catchUno()) }) { Text("Catch! (no UNO)") }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CardView(v.top.copy(color = v.color), big = true)
            Spacer(Modifier.height(8.dp))
            Text(v.log)
            v.err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                if (v.win >= 0) "" else if (myTurn) "Your turn" else "${v.names[v.cur]}'s turn",
                fontWeight = FontWeight.Bold
            )
        }

        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.send(Msg.draw()) }, enabled = myTurn && !v.drawn) { Text("Draw") }
                Button({ vm.send(Msg.pass()) }, enabled = myTurn && v.drawn) { Text("Keep") }
                Button(
                    { vm.send(Msg.uno()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("UNO!") }
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(v.hand) { i, c ->
                    CardView(c) {
                        if (myTurn) {
                            if (c.color == UnoColor.WILD) pick = i else vm.send(Msg.play(i))
                        }
                    }
                }
            }
        }
    }

    pick?.let { idx ->
        AlertDialog(
            onDismissRequest = { pick = null },
            confirmButton = {},
            title = { Text("Choose a colour") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE).forEach { c ->
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(c.ui())
                                .clickable { vm.send(Msg.play(idx, c)); pick = null }
                        )
                    }
                }
            }
        )
    }

    if (v.chal) AlertDialog(
        onDismissRequest = {},
        title = { Text("Wild Draw Four!") },
        text = { Text("Challenge it, or accept and draw 4?") },
        confirmButton = { Button({ vm.send(Msg.challenge(true)) }) { Text("Challenge") } },
        dismissButton = { TextButton({ vm.send(Msg.challenge(false)) }) { Text("Accept (+4)") } }
    )

    if (v.win >= 0) AlertDialog(
        onDismissRequest = {},
        title = { Text("${v.names[v.win]} wins the round!") },
        text = {
            Column {
                v.names.forEachIndexed { i, n -> Text("$n: ${v.scores[i]} pts") }
                if (v.scores.any { it >= 500 }) Text("🏆 Match over (500 points reached)")
            }
        },
        confirmButton = {
            if (vm.isHost) Button({ vm.send(Msg.next()) }) { Text("Next round") }
            else Text("Waiting for host…")
        }
    )
}
