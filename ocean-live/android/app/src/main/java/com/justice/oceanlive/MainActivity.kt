package com.justice.oceanlive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }
}

private val CardColor = Color.White.copy(alpha = 0.07f)
private val Blue = Color(0xFF1A8CF2)
private val Cyan = Color(0xFF4CC3E6)
private val Muted = Color(0xFF9AA8B8)

private fun requiredPermissions(): Array<String> {
    val l = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        l.add(Manifest.permission.BLUETOOTH_SCAN)
        l.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    l.add(Manifest.permission.ACCESS_FINE_LOCATION)
    l.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
    return l.toTypedArray()
}

private fun f(v: Double?, digits: Int): String =
    if (v == null) "--" else String.format(Locale.US, "%.${digits}f", v)

@Composable
fun App() {
    val ctx = LocalContext.current
    val ui by Engine.ui.collectAsState()

    var apiKey by remember { mutableStateOf(Engine.getPref("apiKey", "")) }
    var token by remember { mutableStateOf(Engine.getPref("token", "")) }
    var interval by remember { mutableStateOf(Engine.getPref("interval", "5").toIntOrNull() ?: 5) }
    var model by remember { mutableStateOf(Engine.getPref("model", "big")) }
    var capText by remember { mutableStateOf(Engine.getPref("capacityKWh", "106.5")) }
    var testHeader by remember { mutableStateOf(Engine.getPref("testHeader", "")) }
    var testRx by remember { mutableStateOf(Engine.getPref("testRx", "")) }
    var testCmd by remember { mutableStateOf(Engine.getPref("testCmd", "")) }
    var testSession by remember { mutableStateOf(Engine.getPref("testSession", "0") == "1") }
    var scanHeader by remember { mutableStateOf("7E1") }
    var scanRx by remember { mutableStateOf("7E9") }
    var scanFrom by remember { mutableStateOf("2000") }
    var scanTo by remember { mutableStateOf("20FF") }
    var confirmTest by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var socText by remember { mutableStateOf("") }
    var startAfterSync by remember { mutableStateOf(false) }
    var afterPerms by remember { mutableStateOf<(() -> Unit)?>(null) }

    val capacity: Double = when (model) {
        "sport" -> 75.0
        "custom" -> capText.toDoubleOrNull() ?: 106.5
        else -> 106.5
    }
    val usable = ui.learnedCap ?: capacity
    val whPerMile: Double? = run {
        val miles = ui.tripMeters / 1609.34
        if (miles >= 0.5) ui.tripKWh * 1000 / miles else null
    }
    val rangeMiles: Double? = run {
        val w = whPerMile
        val s = ui.estSoc
        if (w != null && w > 50 && s != null) s / 100 * usable * 1000 / w else null
    }

    fun startNow() {
        Engine.begin(apiKey, token, interval.toDouble(), capacity)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val ok = requiredPermissions().filter {
            it != Manifest.permission.POST_NOTIFICATIONS
        }.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (ok) {
            afterPerms?.invoke()
        } else {
            Engine.notice("Allow Bluetooth and Location in Settings")
        }
        afterPerms = null
    }

    fun ensurePerms(then: () -> Unit) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            then()
        } else {
            afterPerms = then
            permLauncher.launch(requiredPermissions())
        }
    }

    fun toggle() {
        if (ui.active) {
            Engine.halt()
            return
        }
        // ABRP is optional. With no keys the app just shows the car's charge and data.
        ensurePerms {
            startNow()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF08172E), Color(0xFF050A17))))
            .systemBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ocean Live", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (ui.running) Color(0xFF34C759)
                                    else if (ui.connected) Color(0xFFFFD60A) else Color.Gray
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(ui.status, fontSize = 13.sp, color = Muted)
                    }
                }
                TextButton(onClick = { showSettings = true }) {
                    Text("Settings", color = Cyan)
                }
            }

            // Charge ring
            val soc = ui.estSoc ?: 0.0
            val sweep by animateFloatAsState(targetValue = (soc / 100.0).toFloat(), label = "soc")
            val ringColor = if (soc < 15) Color(0xFFFF453A) else if (soc < 30) Color(0xFFFF9F0A) else Color(0xFF33D99A)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(250.dp)) {
                        val stroke = 20.dp.toPx()
                        val inset = stroke / 2
                        val sz = Size(size.width - stroke, size.height - stroke)
                        drawArc(
                            color = Color.White.copy(alpha = 0.08f), startAngle = 0f, sweepAngle = 360f,
                            useCenter = false, topLeft = Offset(inset, inset), size = sz, style = Stroke(stroke)
                        )
                        drawArc(
                            color = ringColor, startAngle = -90f, sweepAngle = sweep * 360f,
                            useCenter = false, topLeft = Offset(inset, inset), size = sz,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (ui.estSoc == null) "--" else f(soc, 0) + "%",
                            fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White
                        )
                        Text(
                            if (ui.estSoc == null) "waiting for the car" else if (ui.charging) "charging" else if (ui.socFromCar) "charge from your car" else "estimated charge",
                            fontSize = 13.sp, color = if (ui.charging) Color(0xFFFFD60A) else Muted
                        )
                    }
                }
                if (!ui.socFromCar) {
                    TextButton(onClick = { showSync = true }) {
                        Text("Match to car screen", color = Cyan)
                    }
                }
            }

            // Adapter picker
            if (ui.found.isNotEmpty() && !ui.connected) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose your adapter", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    ui.found.forEach { d ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(CardColor)
                                .clickable { Engine.connect(d.address) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(d.name, color = Color.White, modifier = Modifier.weight(1f))
                            Text(">", color = Muted)
                        }
                    }
                }
            }

            // Stat tiles
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile("Power", f(ui.live["power"], 1), "kW", Modifier.weight(1f))
                    Tile("Battery temp", f(ui.live["batt_temp"], 0), "°C", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile("Voltage", f(ui.live["voltage"], 0), "V", Modifier.weight(1f))
                    Tile("Current", f(ui.live["current"], 0), "A", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile("Efficiency", f(whPerMile, 0), "Wh/mi", Modifier.weight(1f))
                    Tile("Range left", f(rangeMiles, 0), "mi", Modifier.weight(1f))
                }
            }

            // ABRP card
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardColor)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("A Better Route Planner", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(ui.abrpStatus, fontSize = 13.sp, color = Muted)
                }
                if (ui.lastSent > 0) {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ui.lastSent)), fontSize = 13.sp, color = Muted)
                }
            }

            // Start and stop
            Button(
                onClick = { toggle() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (ui.active) Color(0xFFD9372E) else Blue,
                    contentColor = Color.White
                )
            ) {
                Text(if (ui.active) "Stop" else "Start", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // Match charge dialog
    if (showSync) {
        AlertDialog(
            onDismissRequest = {
                startAfterSync = false
                showSync = false
            },
            title = { Text("Match charge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = socText,
                        onValueChange = { socText = it },
                        label = { Text("Percent on the car screen") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text(
                        "The app estimates your charge from battery power. Enter what the car shows now to start or correct it. Each match also teaches the app your real battery size.",
                        fontSize = 13.sp, color = Muted
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = socText.toDoubleOrNull()
                    if (v != null) {
                        Engine.sync(v)
                        showSync = false
                        if (startAfterSync) {
                            startAfterSync = false
                            startNow()
                        }
                    }
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    startAfterSync = false
                    showSync = false
                }) { Text("Cancel") }
            }
        )
    }

    if (confirmTest) {
        AlertDialog(
            onDismissRequest = { confirmTest = false },
            title = { Text("Send this to your car?") },
            text = { Text("Header " + testHeader + ", command " + testCmd + (if (testSession) ", after opening an extended session" else "") + ". Make sure the car is parked and awake.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTest = false
                    Engine.runTestCommand(testHeader, testRx, testCmd, testSession)
                }) { Text("Send", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("Cancel") } }
        )
    }

    // Settings
    if (showSettings) {
        Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1424)) {
                Column(
                    Modifier
                        .systemBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSettings = false }) { Text("Done", color = Cyan) }
                    }

                    SectionTitle("ABRP")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it; Engine.setPref("apiKey", it) },
                        label = { Text("API key") },
                        isError = apiKey.isBlank() && token.isNotBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; Engine.setPref("token", it) },
                        label = { Text("Generic token") },
                        isError = token.isBlank() && apiKey.isNotBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Send every $interval sec", color = Color.White, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            interval = (interval - 1).coerceAtLeast(1)
                            Engine.setPref("interval", interval.toString())
                        }) { Text("-") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            interval = (interval + 1).coerceAtMost(60)
                            Engine.setPref("interval", interval.toString())
                        }) { Text("+") }
                    }
                    if (apiKey.isBlank() != token.isBlank()) {
                        Text(
                            "Both the API key and the token are needed to send to ABRP. Fill in the red field, or clear both to just watch your car.",
                            fontSize = 13.sp, color = Color(0xFFFF6B6B)
                        )
                    }
                    Text(
                        "Leave both empty if you only want to watch your charge and car data. Token comes from ABRP. Open your car, choose Live data, then Generic. The API key is a free Telemetry key from abetterrouteplanner.com/resources/api. This is an unofficial community app and only reads data from your car.",
                        fontSize = 13.sp, color = Muted
                    )

                    SectionTitle("Car")
                    ModelRow("Ocean One, Extreme or Ultra", model == "big") {
                        model = "big"; Engine.setPref("model", "big")
                    }
                    ModelRow("Ocean Sport", model == "sport") {
                        model = "sport"; Engine.setPref("model", "sport")
                    }
                    ModelRow("Other or custom", model == "custom") {
                        model = "custom"; Engine.setPref("model", "custom")
                    }
                    if (model == "custom") {
                        OutlinedTextField(
                            value = capText,
                            onValueChange = { capText = it; Engine.setPref("capacityKWh", it) },
                            label = { Text("Usable battery in kWh") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Usable battery  " + f(capacity, 1) + " kWh", color = Color.White)
                    }
                    Text(
                        "The One, Extreme and Ultra use 106.5 kWh and the Sport uses 75 kWh. Published figures for the Sport vary between 71 and 75 kWh. The app adjusts this on its own as you match the car screen after drives.",
                        fontSize = 13.sp, color = Muted
                    )
                    if (ui.learnedCap != null) {
                        Text("Learned battery size  " + f(ui.learnedCap, 1) + " kWh", color = Color.White)
                        OutlinedButton(onClick = { Engine.resetLearning() }) { Text("Reset learning") }
                    }

                    SectionTitle("Adapter")
                    Text(ui.status, color = Color.White)
                    if (ui.hasSavedAdapter) {
                        OutlinedButton(onClick = { Engine.forgetAdapter() }) { Text("Forget saved adapter") }
                    } else {
                        OutlinedButton(onClick = { ensurePerms { Engine.connectAdapter() } }) { Text("Look for adapters") }
                    }

                    SectionTitle("Read only scan")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scanHeader = "7E1"; scanRx = "7E9"; scanFrom = "2000"; scanTo = "21FF" }) { Text("Battery") }
                        OutlinedButton(onClick = { scanHeader = "7F0"; scanRx = "7F8"; scanFrom = "0000"; scanTo = "0FFF" }) { Text("Climate") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scanHeader = "752"; scanRx = "75A"; scanFrom = "0000"; scanTo = "0FFF" }) { Text("Heater 752") }
                        OutlinedButton(onClick = { scanHeader = "7A2"; scanRx = "7AA"; scanFrom = "F100"; scanTo = "FFFF" }) { Text("Key") }
                        OutlinedButton(onClick = { scanHeader = "7D5"; scanRx = "7DD"; scanFrom = "0000"; scanTo = "0FFF" }) { Text("Heater 7D5") }
                    }
                    OutlinedTextField(value = scanHeader, onValueChange = { scanHeader = it.uppercase() }, label = { Text("Header") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = scanRx, onValueChange = { scanRx = it.uppercase() }, label = { Text("Reply address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = scanFrom, onValueChange = { scanFrom = it.uppercase() }, label = { Text("First identifier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = scanTo, onValueChange = { scanTo = it.uppercase() }, label = { Text("Last identifier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (ui.scanning) {
                        OutlinedButton(onClick = { Engine.stopDidScan() }) { Text("Stop scan") }
                    } else {
                        OutlinedButton(onClick = { Engine.startDidScan(scanHeader, scanRx, scanFrom, scanTo) }) { Text("Start scan") }
                    }
                    if (ui.scanText.isNotEmpty()) {
                        Text(ui.scanText.lines().takeLast(40).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White)
                        OutlinedButton(onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("scan", ui.scanText))
                        }) { Text("Copy results") }
                    }
                    Text(
                        "Asks a module for each identifier in a range and lists the ones that answer. It only reads and never writes. Sending to ABRP pauses while it runs. Do it with the car parked and awake.",
                        fontSize = 13.sp, color = Muted
                    )

                    SectionTitle("Preheat test")
                    OutlinedTextField(
                        value = testHeader,
                        onValueChange = { testHeader = it.uppercase(); Engine.setPref("testHeader", testHeader) },
                        label = { Text("Header, like 7E1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = testRx,
                        onValueChange = { testRx = it.uppercase(); Engine.setPref("testRx", testRx) },
                        label = { Text("Reply address, like 7E9") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = testCmd,
                        onValueChange = { testCmd = it.uppercase(); Engine.setPref("testCmd", testCmd) },
                        label = { Text("Command in hex") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Open extended session first", color = Color.White, modifier = Modifier.weight(1f))
                        Switch(checked = testSession, onCheckedChange = { testSession = it; Engine.setPref("testSession", if (it) "1" else "0") })
                    }
                    OutlinedButton(onClick = { confirmTest = true }) { Text("Send test command") }
                    if (ui.testResult.isNotEmpty()) {
                        Text(ui.testResult, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
                    }
                    Text(
                        "Experimental. Sends one command you type in to your own car. Only services 31 and 2F are allowed. It will not send while the car is moving or charging, and it needs the adapter connected. Use at your own risk, and only with a command you have a trusted source for.",
                        fontSize = 13.sp, color = Muted
                    )

                    SectionTitle("Activity")
                    ui.log.takeLast(12).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cyan)
}

@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, color = Color.White)
    }
}

@Composable
private fun Tile(title: String, value: String, unit: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 13.sp, color = Muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(unit, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}
