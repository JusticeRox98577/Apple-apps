package com.justice.oceanlive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class FoundDevice(val name: String, val address: String)

data class PID(
    val name: String,
    val field: String,
    val header: String,
    val rx: String,
    val cmd: String,
    val expr: String
)

data class UiState(
    val status: String = "Not connected",
    val connected: Boolean = false,
    val running: Boolean = false,
    val active: Boolean = false,
    val estSoc: Double? = null,
    val socFromCar: Boolean = false,
    val live: Map<String, Double> = emptyMap(),
    val charging: Boolean = false,
    val abrpStatus: String = "Waiting",
    val lastSent: Long = 0L,
    val tripMeters: Double = 0.0,
    val tripKWh: Double = 0.0,
    val learnedCap: Double? = null,
    val found: List<FoundDevice> = emptyList(),
    val log: List<String> = emptyList(),
    val hasSavedAdapter: Boolean = false,
    val testResult: String = "",
    val scanText: String = "",
    val scanning: Boolean = false
)

/**
 * Reads the car through an ELM327 style Bluetooth LE adapter, estimates the charge level
 * by counting energy, and sends everything to A Better Route Planner. Everything here runs
 * on one background thread, and the screen reads the result through [ui].
 */
@SuppressLint("MissingPermission")
object Engine {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    private val pids = listOf(
        PID("Pack voltage", "voltage", "7E1", "7E9", "222003", "(A*256+B)/10"),
        PID("Pack current", "current", "7E1", "7E9", "222016", "T/10"),
        PID("Pack temperature", "batt_temp", "7E1", "7E9", "222038", "(A*256+B)/2-50"),
        PID("Pack charge", "soc", "7E1", "7E9", "222050", "(A*256+B)/10")
    )

    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val skipServices = listOf("1800", "1801", "180a", "180f")

    // Bluetooth state
    private var btAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var ready = false
    private val buffer = StringBuilder()
    private var pending: CompletableDeferred<String>? = null
    private var pollJob: Job? = null
    private var startAction: (() -> Unit)? = null
    private var scanning = false
    @Volatile private var holdLoop = false
    @Volatile private var scanCancel = false

    // Estimator state
    private var capacityKWh = 106.5
    private var lastSample: Long? = null
    private var smoothKW: Double? = null
    private var chargingNow = false
    private var energySinceSync = 0.0
    private var socAtSync: Double? = null
    private var continuous = false
    private var restSince: Long? = null
    private var restVoltage: Double? = null
    private var ocv = mutableListOf<DoubleArray>()

    // Location
    @Volatile
    private var lastLocation: Location? = null
    private var tripPrev: Location? = null
    private var locationManager: LocationManager? = null
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("ocean", Context.MODE_PRIVATE)
        val bm = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = bm.adapter
        locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val soc = prefs.getString("estSOC", null)?.toDoubleOrNull()
        val cap = prefs.getString("learnedCap", null)?.toDoubleOrNull()
        prefs.getString("ocv", "")?.split(";")?.forEach { pair ->
            val p = pair.split(",")
            if (p.size == 2) {
                val a = p[0].toDoubleOrNull()
                val b = p[1].toDoubleOrNull()
                if (a != null && b != null) ocv.add(doubleArrayOf(a, b))
            }
        }
        _ui.update {
            it.copy(
                estSoc = soc,
                learnedCap = cap,
                hasSavedAdapter = prefs.getString("adapterAddr", null) != null
            )
        }
    }

    // MARK: simple settings storage for the screen

    fun getPref(key: String, def: String): String = prefs.getString(key, def) ?: def

    fun setPref(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun log(s: String) {
        _ui.update { st ->
            val l = st.log + s
            st.copy(log = if (l.size > 100) l.takeLast(100) else l)
        }
    }

    private fun status(s: String) {
        _ui.update { it.copy(status = s) }
    }

    fun notice(s: String) {
        status(s)
    }

    private fun saveD(key: String, v: Double) {
        prefs.edit().putString(key, v.toString()).apply()
    }

    private fun touchActive() {
        prefs.edit().putLong("lastActive", System.currentTimeMillis()).apply()
    }

    fun isStale(): Boolean {
        val t = prefs.getLong("lastActive", 0L)
        return t == 0L || System.currentTimeMillis() - t > 20 * 60 * 1000
    }

    // MARK: charge estimate

    fun setSOC(value: Double) {
        scope.launch { setSocInternal(value) }
    }

    private fun setSocInternal(value: Double) {
        val v = min(100.0, max(0.0, value))
        _ui.update { it.copy(estSoc = v) }
        saveD("estSOC", v)
    }

    fun resetLearning() {
        scope.launch {
            _ui.update { it.copy(learnedCap = null) }
            ocv.clear()
            prefs.edit().remove("learnedCap").remove("ocv").apply()
        }
    }

    /** Called when the person types the charge shown on the car screen. */
    fun sync(dash: Double) {
        scope.launch {
            val d = min(100.0, max(0.0, dash))
            val s0 = socAtSync
            if (continuous && s0 != null && abs(s0 - d) >= 8 && energySinceSync != 0.0) {
                val dropped = s0 - d
                if ((energySinceSync > 0) == (dropped > 0)) {
                    val implied = abs(energySinceSync / (dropped / 100.0))
                    if (implied > 40 && implied < 160) {
                        val base = _ui.value.learnedCap ?: capacityKWh
                        val blended = base * 0.5 + implied * 0.5
                        _ui.update { it.copy(learnedCap = blended) }
                        capacityKWh = blended
                        saveD("learnedCap", blended)
                        log("Learned battery size " + String.format(Locale.US, "%.1f", blended) + " kWh")
                    }
                }
            }
            val rs = restSince
            val rv = restVoltage
            if (rs != null && rv != null && System.currentTimeMillis() - rs > 600_000) {
                ocv.add(doubleArrayOf(rv, d))
                if (ocv.size > 40) ocv.removeAt(0)
                prefs.edit().putString("ocv", ocv.joinToString(";") { "${it[0]},${it[1]}" }).apply()
            }
            setSocInternal(d)
            socAtSync = d
            energySinceSync = 0.0
            continuous = true
            touchActive()
        }
    }

    private fun ocvEstimate(v: Double): Double? {
        if (ocv.size < 5) return null
        val pts = ocv.sortedBy { it[0] }
        val lo = pts.lastOrNull { it[0] <= v } ?: return null
        val hi = pts.firstOrNull { it[0] >= v } ?: return null
        if (hi[0] == lo[0]) return lo[1]
        return lo[1] + (hi[1] - lo[1]) * (v - lo[0]) / (hi[0] - lo[0])
    }

    // MARK: connecting

    fun connectAdapter() {
        scope.launch { connectAdapterInternal() }
    }

    private fun connectAdapterInternal() {
        val a = btAdapter
        if (a == null || !a.isEnabled) {
            status("Turn on Bluetooth")
            return
        }
        val saved = prefs.getString("adapterAddr", null)
        if (saved != null) {
            connectTo(saved)
            return
        }
        _ui.update { it.copy(found = emptyList()) }
        try {
            a.bluetoothLeScanner?.startScan(scanCb)
            scanning = true
            status("Looking for your adapter")
        } catch (e: Exception) {
            status("Allow Bluetooth in Settings")
        }
    }

    fun connect(address: String) {
        scope.launch { connectTo(address) }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            btAdapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (e: Exception) {
        }
    }

    private fun connectTo(address: String) {
        val a = btAdapter ?: return
        stopScan()
        try {
            gatt?.close()
        } catch (e: Exception) {
        }
        ready = false
        writeChar = null
        notifyChar = null
        status("Connecting")
        try {
            val dev = a.getRemoteDevice(address)
            gatt = if (Build.VERSION.SDK_INT >= 23) {
                dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            } else {
                dev.connectGatt(app, false, gattCb)
            }
        } catch (e: Exception) {
            status("Could not connect")
        }
    }

    fun forgetAdapter() {
        scope.launch {
            prefs.edit().remove("adapterAddr").apply()
            haltInternal()
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (e: Exception) {
            }
            gatt = null
            ready = false
            _ui.update { it.copy(connected = false, hasSavedAdapter = false, status = "Not connected") }
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.scanRecord?.deviceName ?: result.device.name
            } catch (e: Exception) {
                null
            }
            val addr = result.device.address
            if (name == null) return
            scope.launch {
                _ui.update { st ->
                    if (st.found.any { it.address == addr }) st
                    else st.copy(found = st.found + FoundDevice(name, addr))
                }
            }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                scope.launch {
                    prefs.edit().putString("adapterAddr", g.device.address).apply()
                    _ui.update { it.copy(hasSavedAdapter = true) }
                    status("Setting up")
                    try {
                        g.discoverServices()
                    } catch (e: Exception) {
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                scope.launch {
                    ready = false
                    _ui.update { it.copy(connected = false) }
                    stopLoop()
                    try {
                        g.close()
                    } catch (e: Exception) {
                    }
                    if (gatt === g) gatt = null
                    if (_ui.value.active) {
                        status("Reconnecting")
                        delay(2000)
                        val addr = prefs.getString("adapterAddr", null)
                        if (_ui.value.active && addr != null && gatt == null) connectTo(addr)
                    } else {
                        status("Disconnected")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            scope.launch {
                for (s in g.services) {
                    val short = s.uuid.toString().substring(4, 8).lowercase(Locale.US)
                    if (s.uuid.toString().startsWith("0000") && skipServices.contains(short)) continue
                    for (c in s.characteristics) {
                        val p = c.properties
                        if (notifyChar == null &&
                            (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                        ) {
                            notifyChar = c
                        }
                        if (writeChar == null &&
                            (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                        ) {
                            writeChar = c
                        }
                    }
                }
                val n = notifyChar
                if (n == null || writeChar == null) {
                    status("Adapter not supported")
                    return@launch
                }
                g.setCharacteristicNotification(n, true)
                val d = n.getDescriptor(cccd)
                if (d == null) {
                    markReady(g)
                    return@launch
                }
                val value = if ((n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, value)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = value
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            scope.launch { markReady(g) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onBytes(value)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                onBytes(c.value ?: return)
            }
        }
    }

    private fun markReady(g: BluetoothGatt) {
        if (ready) return
        ready = true
        _ui.update { it.copy(connected = true) }
        var name: String? = null
        try {
            name = g.device.name
        } catch (e: Exception) {
        }
        status("Connected to " + (name ?: "adapter"))
        if (_ui.value.active) startAction?.invoke()
    }

    private fun onBytes(b: ByteArray) {
        val s = String(b, Charsets.UTF_8)
        scope.launch {
            buffer.append(s)
            val p = pending
            if (p != null && buffer.contains('>')) {
                val out = buffer.toString()
                buffer.setLength(0)
                pending = null
                p.complete(out)
            }
        }
    }

    // MARK: sending commands

    private suspend fun send(cmd: String, timeout: Long = 3000): String {
        val g = gatt ?: return ""
        val w = writeChar ?: return ""
        buffer.setLength(0)
        val d = CompletableDeferred<String>()
        pending = d
        val data = (cmd + "\r").toByteArray(Charsets.UTF_8)
        val type = if ((w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(w, data, type)
            } else {
                @Suppress("DEPRECATION")
                w.writeType = type
                @Suppress("DEPRECATION")
                w.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(w)
            }
        } catch (e: Exception) {
            pending = null
            return ""
        }
        val out = withTimeoutOrNull(timeout) { d.await() }
        if (pending === d) pending = null
        if (out != null) return out
        val s = buffer.toString()
        buffer.setLength(0)
        return s
    }

    // Read only scan. Service 22 only, never writes.
    fun stopDidScan() { scanCancel = true }

    fun startDidScan(header: String, rx: String, from: String, to: String) {
        val h = header.trim().uppercase()
        val r = rx.trim().uppercase()
        val a = from.trim().uppercase()
        val b = to.trim().uppercase()
        fun out(s: String) { _ui.update { it.copy(scanText = s) } }
        val three = Regex("^[0-9A-F]{3}$")
        val four = Regex("^[0-9A-F]{4}$")
        if (!three.matches(h) || !three.matches(r) || !four.matches(a) || !four.matches(b)) {
            out("Check the entries. Header and reply are 3 hex characters and the range is 4 hex characters"); return
        }
        val lo = a.toInt(16)
        val hi = b.toInt(16)
        if (hi < lo) { out("The last identifier must be after the first"); return }
        if (hi - lo >= 4096) { out("Keep the range to 4096 identifiers or fewer"); return }
        runDidScan(h, r, (lo..hi).toList(), "Scan $h to $r, $a to $b")
    }

    // Auto probe: first 64 identifiers of every block from 1000 to 4FFF, no typing.
    fun startAutoProbe(header: String, rx: String) {
        val h = header.trim().uppercase()
        val r = rx.trim().uppercase()
        val three = Regex("^[0-9A-F]{3}$")
        if (!three.matches(h) || !three.matches(r)) {
            _ui.update { it.copy(scanText = "Check the header and reply address") }; return
        }
        val ids = ArrayList<Int>()
        for (blk in 0x10..0x4F) for (low in 0x00..0x3F) ids.add(blk * 256 + low)
        runDidScan(h, r, ids, "Auto probe $h to $r, first 64 of each block from 1000 to 4FFF")
    }

    private fun runDidScan(h: String, r: String, ids: List<Int>, label: String) {
        fun out(s: String) { _ui.update { it.copy(scanText = s) } }
        scope.launch {
            if (!ready || !_ui.value.active) { out("Press Start first so the adapter is connected"); return@launch }
            if (holdLoop || _ui.value.scanning) return@launch
            holdLoop = true
            scanCancel = false
            _ui.update { it.copy(scanning = true) }
            val sb = StringBuilder(label + "\n")
            try {
                out(sb.toString())
                delay(1500)
                send("ATSH" + h)
                send("ATCRA" + r)
                var hits = 0
                for (v in ids) {
                    if (scanCancel) break
                    val did = String.format("%04X", v)
                    val cmd = "22$did"
                    val resp = send(cmd, 1500)
                    val bytes = parseBytes(resp, cmd)
                    if (bytes != null) {
                        sb.append(did).append(" = ").append(bytes.joinToString("") { String.format("%02X", it.toInt()) }).append("\n")
                        hits++
                        out(sb.toString())
                    } else if (resp.uppercase().replace(" ", "").contains("7F2233")) {
                        sb.append(did).append(" locked\n")
                        hits++
                        out(sb.toString())
                    }
                }
                sb.append(if (scanCancel) "Stopped. $hits found\n" else "Done. $hits found\n")
                out(sb.toString())
            } finally {
                _ui.update { it.copy(scanning = false) }
                holdLoop = false
            }
        }
    }

    // Test command for your own car, with safety checks.
    // Only services 31 (routine control) and 2F (input output control) can be sent.
    // Writes, security access, resets, code clearing and reflashing are all blocked.
    // 10 03 (extended session) is only sent by the app itself when the switch is on.
    fun checkTestCommand(header: String, rx: String, cmd: String): String? {
        if (!Regex("^[0-9A-F]{3}$").matches(header)) return "Header must be 3 hex characters, like 7E1"
        if (rx.isNotEmpty() && !Regex("^[0-9A-F]{3}$").matches(rx)) return "Reply address must be 3 hex characters, like 7E9"
        if (!Regex("^[0-9A-F]+$").matches(cmd) || cmd.length % 2 != 0) return "Command must be hex bytes, like 31010203"
        if (cmd.length < 6 || cmd.length > 14) return "Command must be 3 to 7 bytes"
        val svc = cmd.substring(0, 2)
        if (svc != "31" && svc != "2F") return "Only service 31 and 2F are allowed here"
        return null
    }

    fun runTestCommand(header: String, rx: String, cmd: String, session: Boolean) {
        val h = header.trim().uppercase()
        val r = rx.trim().uppercase()
        val c = cmd.replace(" ", "").uppercase()
        fun result(s: String) { _ui.update { it.copy(testResult = s) } }
        checkTestCommand(h, r, c)?.let { result(it); return }
        scope.launch {
            if (!ready || !_ui.value.active) { result("Press Start first so the adapter is connected"); return@launch }
            val loc = lastLocation
            if (loc == null || !loc.hasSpeed()) { result("Waiting for a GPS speed. The car must be parked"); return@launch }
            if (loc.speed * 3.6 >= 1) { result("Blocked. The car is moving"); return@launch }
            if (chargingNow) { result("Blocked. Wait until the car is not charging"); return@launch }
            if (holdLoop) return@launch
            holdLoop = true
            try {
                result("Sending")
                delay(1500)
                send("ATSH" + h)
                send(if (r.isEmpty()) "ATAR" else "ATCRA" + r)
                val out = mutableListOf<String>()
                if (session) {
                    val s = send("1003", 3000).trim()
                    out.add("1003 -> $s")
                    log("TEST 1003 $s")
                    if (!s.uppercase().replace(" ", "").contains("5003")) {
                        result(out.joinToString("\n") + "\nSession was refused. Command not sent")
                        return@launch
                    }
                }
                val resp = send(c, 4000).trim()
                out.add("$c -> $resp")
                log("TEST $c $resp")
                val flat = resp.uppercase().replace(" ", "")
                val ok = String.format("%02X", c.substring(0, 2).toInt(16) + 0x40)
                out.add(
                    if (flat.contains("7F" + c.substring(0, 2))) "The car refused it (negative response)"
                    else if (flat.contains(ok)) "The car accepted it"
                    else "No clear answer from the car"
                )
                result(out.joinToString("\n"))
            } finally {
                holdLoop = false
            }
        }
    }

    private fun varMap(b: List<Double>): Map<Char, Double> {
        val m = mutableMapOf<Char, Double>()
        "ABCDEFGH".forEachIndexed { idx, name -> m[name] = if (idx < b.size) b[idx] else 0.0 }
        fun s16(hi: Double, lo: Double): Double {
            val v = hi.toInt() * 256 + lo.toInt()
            return (if (v >= 32768) v - 65536 else v).toDouble()
        }
        m['S'] = if (b.size >= 2) s16(b[0], b[1]) else 0.0
        m['T'] = if (b.size >= 4) s16(b[2], b[3]) else 0.0
        m['U'] = if (b.size >= 6) s16(b[4], b[5]) else 0.0
        return m
    }

    private fun parseBytes(resp: String, cmd: String): List<Double>? {
        if (cmd.length < 2) return null
        val svc = cmd.substring(0, 2).toIntOrNull(16) ?: return null
        val prefix = String.format(Locale.US, "%02X", svc + 0x40) + cmd.substring(2).uppercase(Locale.US)
        val lines = resp.replace(">", "")
            .split("\r", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !Regex("^[0-9A-F]{3}$").matches(it) }
            .map { it.replace(Regex("^[0-9A-F]:"), "") }
        val hex = lines.joinToString("").replace(" ", "").uppercase(Locale.US)
        val at = hex.indexOf(prefix)
        if (at < 0) return null
        val rest = hex.substring(at + prefix.length)
        val out = mutableListOf<Double>()
        var k = 0
        while (k + 1 < rest.length) {
            val v = rest.substring(k, k + 2).toIntOrNull(16)
            if (v != null) out.add(v.toDouble())
            k += 2
        }
        return out
    }

    // MARK: start and stop

    fun begin(apiKey: String, token: String, interval: Double, capacity: Double) {
        try {
            ContextCompat.startForegroundService(app, Intent(app, OceanService::class.java))
        } catch (e: Exception) {
            log("Could not start background service")
        }
        scope.launch {
            _ui.update { it.copy(active = true, tripMeters = 0.0, tripKWh = 0.0) }
            tripPrev = null
            startAction = { runLoop(apiKey, token, interval, capacity) }
            if (ready) startAction?.invoke() else connectAdapterInternal()
        }
    }

    fun halt() {
        scope.launch { haltInternal() }
    }

    private fun haltInternal() {
        _ui.update { it.copy(active = false) }
        startAction = null
        stopLoop()
        stopLocation()
        _ui.value.estSoc?.let { saveD("estSOC", it) }
        touchActive()
        try {
            app.stopService(Intent(app, OceanService::class.java))
        } catch (e: Exception) {
        }
    }

    private fun stopLoop() {
        pollJob?.cancel()
        pollJob = null
        _ui.update { it.copy(running = false) }
        continuous = false
        restSince = null
        pending?.complete("")
        pending = null
    }

    private fun startLocation() {
        val lm = locationManager ?: return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
        } catch (e: Exception) {
            log("GPS unavailable")
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 0f, locListener, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    private fun stopLocation() {
        try {
            locationManager?.removeUpdates(locListener)
        } catch (e: Exception) {
        }
    }

    private fun runLoop(apiKey: String, token: String, interval: Double, capacity: Double) {
        if (!ready || _ui.value.running) return
        _ui.update { it.copy(running = true) }
        capacityKWh = _ui.value.learnedCap ?: max(10.0, capacity)
        lastSample = null
        smoothKW = null
        startLocation()
        pollJob = scope.launch {
            for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATST20")) {
                val r = send(c, if (c == "ATZ") 4000 else 2000)
                log(c + " " + r.trim())
            }
            status("Sending data")
            var lastUpload = 0L
            while (isActive) {
                while (holdLoop && isActive) delay(200)
                val tlm = mutableMapOf<String, Any>("utc" to System.currentTimeMillis() / 1000)
                var header = ""
                var rx = ""
                for (p in pids) {
                    if (!isActive) break
                    if (p.header != header) {
                        send("ATSH" + p.header)
                        header = p.header
                    }
                    if (p.rx != rx) {
                        send(if (p.rx.isEmpty()) "ATAR" else "ATCRA" + p.rx)
                        rx = p.rx
                    }
                    val r = send(p.cmd)
                    val bytes = parseBytes(r, p.cmd)
                    if (bytes == null) {
                        log(p.name + " no data")
                        continue
                    }
                    val v = Calc(p.expr, varMap(bytes)).parse()
                    if (v != null) {
                        tlm[p.field] = v
                        _ui.update { it.copy(live = it.live + (p.field to v)) }
                    } else {
                        log(p.name + " bad formula")
                    }
                }
                val loc = lastLocation
                if (loc != null) {
                    tlm["lat"] = loc.latitude
                    tlm["lon"] = loc.longitude
                    if (loc.hasSpeed()) tlm["speed"] = loc.speed * 3.6
                    if (loc.hasAccuracy() && loc.accuracy < 50) {
                        val prev = tripPrev
                        if (prev != null) {
                            val d = loc.distanceTo(prev).toDouble()
                            if (d > 5 && d < 500) {
                                _ui.update { it.copy(tripMeters = it.tripMeters + d) }
                                tripPrev = loc
                            } else if (d >= 500) {
                                tripPrev = loc
                            }
                        } else {
                            tripPrev = loc
                        }
                    }
                }
                // The charge level comes straight from the battery module when it answers.
                // If it does not, the app falls back to counting energy below.
                var carSoc: Double? = null
                val rawSoc = tlm["soc"] as? Double
                if (rawSoc != null && rawSoc >= 0 && rawSoc <= 100) {
                    carSoc = rawSoc
                } else {
                    tlm.remove("soc")
                }
                if (carSoc != null) {
                    _ui.update { it.copy(estSoc = carSoc, socFromCar = true) }
                    saveD("estSOC", carSoc)
                } else {
                    _ui.update { it.copy(socFromCar = false) }
                    if (_ui.value.estSoc == null) status("Match to car screen to set charge")
                }
                val volts = tlm["voltage"] as? Double
                val amps = tlm["current"] as? Double
                if (volts != null && amps != null) {
                    val raw = volts * amps / 1000.0
                    val kw = smoothKW?.let { it * 0.8 + raw * 0.2 } ?: raw
                    smoothKW = kw
                    if (chargingNow) {
                        if (kw > -0.5) chargingNow = false
                    } else if (kw < -1.5) {
                        chargingNow = true
                    }
                    val shown = if (abs(kw) < 0.3) 0.0 else kw
                    tlm["power"] = shown
                    tlm["is_charging"] = if (chargingNow) 1 else 0
                    val now = System.currentTimeMillis()
                    val last = lastSample
                    val soc = _ui.value.estSoc
                    if (carSoc == null && last != null && soc != null) {
                        val dt = (now - last) / 1000.0
                        if (dt > 0 && dt < 30) {
                            val kwh = kw * dt / 3600.0
                            val next = min(100.0, max(0.0, soc - kwh / capacityKWh * 100.0))
                            energySinceSync += kwh
                            val countTrip = !chargingNow
                            _ui.update {
                                it.copy(estSoc = next, tripKWh = if (countTrip) it.tripKWh + kwh else it.tripKWh)
                            }
                        }
                    }
                    lastSample = now
                    _ui.update {
                        it.copy(live = it.live + ("power" to shown), charging = chargingNow)
                    }
                    if (abs(kw) < 0.3) {
                        if (restSince == null) restSince = now
                        restVoltage = volts
                        val rs = restSince
                        val e = _ui.value.estSoc
                        if (carSoc == null && rs != null && now - rs > 900_000 && e != null) {
                            val o = ocvEstimate(volts)
                            if (o != null) {
                                setSocInternal(e + min(2.0, max(-2.0, o - e)))
                                restSince = now
                            }
                        }
                    } else if (abs(kw) > 0.5) {
                        restSince = null
                    }
                }
                val est = _ui.value.estSoc
                if (est != null) tlm["soc"] = (est * 10).roundToLong() / 10.0
                if (tlm["is_charging"] == null) tlm["is_charging"] = 0
                if (System.currentTimeMillis() - lastUpload >= max(1.0, interval) * 1000) {
                    if (tlm["soc"] != null) {
                        if (apiKey.isNotBlank() && token.isNotBlank()) {
                            upload(tlm, apiKey, token)
                        } else {
                            _ui.update { it.copy(abrpStatus = "ABRP is off. Add the API key and token in Settings to send") }
                        }
                        _ui.value.estSoc?.let { saveD("estSOC", it) }
                        touchActive()
                    }
                    lastUpload = System.currentTimeMillis()
                }
                delay(300)
            }
        }
    }

    private suspend fun upload(tlm: Map<String, Any>, apiKey: String, token: String) {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "https://api.iternio.com/1/tlm/send?api_key=" + enc(apiKey.trim()) +
            "&token=" + enc(token.trim()) + "&tlm=" + enc(JSONObject(tlm).toString())
        try {
            val result = withContext(Dispatchers.IO) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val code = c.responseCode
                val stream = if (code < 400) c.inputStream else c.errorStream
                val body = stream?.bufferedReader()?.readText()?.take(80) ?: ""
                c.disconnect()
                Pair(code, body)
            }
            log("ABRP " + result.first + " " + result.second)
            if (result.first == 200) {
                _ui.update { it.copy(abrpStatus = "Sending to ABRP", lastSent = System.currentTimeMillis()) }
            } else {
                _ui.update { it.copy(abrpStatus = "ABRP error " + result.first) }
            }
        } catch (e: Exception) {
            _ui.update { it.copy(abrpStatus = "No internet") }
            log("ABRP failed " + (e.message ?: ""))
        }
    }
}
