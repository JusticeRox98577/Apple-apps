import SwiftUI
import CoreBluetooth
import CoreLocation
import UIKit

// MARK: - PID model

struct PIDItem: Codable, Identifiable {
    var id = UUID()
    var name: String
    var field: String      // ABRP field such as soc, speed, power, voltage, current, batt_temp
    var header: String     // CAN request header sent with ATSH
    var cmd: String        // request such as 222003
    var expr: String       // formula using response bytes A to H, and S T U for signed 16 bit pairs
    var rx: String? = nil  // optional response header filter sent with ATCRA
    var disabled: Bool? = nil

    enum CodingKeys: String, CodingKey { case name, field, header, cmd, expr, rx, disabled }
}

let defaultPIDs = """
[
  {"name":"Pack voltage","field":"voltage","header":"7E1","rx":"7E9","cmd":"222003","expr":"(A*256+B)/10"},
  {"name":"Pack current","field":"current","header":"7E1","rx":"7E9","cmd":"222016","expr":"T/10"},
  {"name":"Pack temperature","field":"batt_temp","header":"7E1","rx":"7E9","cmd":"222038","expr":"(A*256+B)/2-50"},
  {"name":"Pack charge","field":"soc","header":"7E1","rx":"7E9","cmd":"222050","expr":"(A*256+B)/10"}
]
"""

// MARK: - Tiny formula evaluator (+ - * / parentheses, numbers, A to H, S T U)

struct Calc {
    let chars: [Character]
    var i = 0
    let vars: [Character: Double]

    init(_ s: String, vars: [Character: Double]) {
        chars = Array(s.filter { !$0.isWhitespace })
        self.vars = vars
    }

    mutating func parse() -> Double? {
        guard let v = expr(), i == chars.count else { return nil }
        return v
    }

    mutating func expr() -> Double? {
        guard var v = term() else { return nil }
        while i < chars.count, chars[i] == "+" || chars[i] == "-" {
            let op = chars[i]
            i += 1
            guard let r = term() else { return nil }
            v = op == "+" ? v + r : v - r
        }
        return v
    }

    mutating func term() -> Double? {
        guard var v = factor() else { return nil }
        while i < chars.count, chars[i] == "*" || chars[i] == "/" {
            let op = chars[i]
            i += 1
            guard let r = factor() else { return nil }
            v = op == "*" ? v * r : (r == 0 ? 0 : v / r)
        }
        return v
    }

    mutating func factor() -> Double? {
        guard i < chars.count else { return nil }
        let c = chars[i]
        if c == "-" {
            i += 1
            return factor().map { -$0 }
        }
        if c == "(" {
            i += 1
            guard let v = expr(), i < chars.count, chars[i] == ")" else { return nil }
            i += 1
            return v
        }
        if let v = vars[c] {
            i += 1
            return v
        }
        var s = ""
        while i < chars.count, chars[i].isNumber || chars[i] == "." {
            s.append(chars[i])
            i += 1
        }
        return Double(s)
    }
}

// MARK: - Location

@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let mgr = CLLocationManager()
    @Published var last: CLLocation?

    override init() {
        super.init()
        mgr.delegate = self
        mgr.desiredAccuracy = kCLLocationAccuracyBest
    }

    func start() {
        mgr.requestWhenInUseAuthorization()
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        if modes.contains("location") {
            mgr.allowsBackgroundLocationUpdates = true
            mgr.pausesLocationUpdatesAutomatically = false
            mgr.showsBackgroundLocationIndicator = true
        }
        mgr.startUpdatingLocation()
    }

    func stop() {
        mgr.stopUpdatingLocation()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated {
            last = locations.last
        }
    }
}

// MARK: - Bluetooth LE OBD manager (ELM327 style) and ABRP uploader

@MainActor
final class OBDManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    @Published var found: [CBPeripheral] = []
    @Published var status = "Not connected"
    @Published var logLines: [String] = []
    @Published var live: [String: Double] = [:]
    @Published var running = false
    @Published var active = false
    @Published var connected = false
    @Published var estSOC: Double? = nil
    @Published var socFromCar = false
    @Published var holdLoop = false
    @Published var scanText = ""
    @Published var scanning = false
    private var scanCancel = false
    @Published var testResult = ""
    @Published var abrpStatus = "Waiting"
    @Published var lastSent: Date?

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var notifyChar: CBCharacteristic?
    private var ready = false
    private var servicesLeft = 0
    private var candidates: [(svc: CBService, notify: CBCharacteristic?, write: CBCharacteristic?)] = []
    @Published var foundNames: [UUID: String] = [:]
    private var buffer = ""
    private var pending: CheckedContinuation<String, Never>?
    private var pendingToken: UUID?
    private var pollTask: Task<Void, Never>?
    private var startAction: (() -> Void)?
    private var lastSample: Date?
    private var capacityKWh = 106.0
    private var smoothKW: Double?
    private var chargingNow = false
    private var energySinceSync = 0.0
    private var socAtSync: Double?
    private var continuous = false
    private var restSince: Date?
    private var restVoltage: Double?
    private var lastLoc: CLLocation?
    private var ocv: [[Double]] = []
    @Published var tripMeters = 0.0
    @Published var tripKWh = 0.0
    @Published var learnedCapacity: Double? = nil

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        if UserDefaults.standard.object(forKey: "estSOC") != nil {
            estSOC = UserDefaults.standard.double(forKey: "estSOC")
        }
        if UserDefaults.standard.object(forKey: "learnedCap") != nil {
            learnedCapacity = UserDefaults.standard.double(forKey: "learnedCap")
        }
        ocv = UserDefaults.standard.array(forKey: "ocv") as? [[Double]] ?? []
    }

    var hasSavedAdapter: Bool {
        UserDefaults.standard.string(forKey: "adapterID") != nil
    }

    func log(_ s: String) {
        logLines.append(s)
        if logLines.count > 100 { logLines.removeFirst(logLines.count - 100) }
    }

    func setSOC(_ value: Double) {
        let v = min(100, max(0, value))
        estSOC = v
        UserDefaults.standard.set(v, forKey: "estSOC")
    }

    var isStale: Bool {
        guard let t = UserDefaults.standard.object(forKey: "lastActive") as? Date else { return true }
        return Date().timeIntervalSince(t) > 20 * 60
    }

    func resetLearning() {
        learnedCapacity = nil
        ocv = []
        UserDefaults.standard.removeObject(forKey: "learnedCap")
        UserDefaults.standard.removeObject(forKey: "ocv")
    }

    // Called when the person enters the SOC shown on the car screen. Corrects the estimate and learns from it.
    func sync(to dash: Double) {
        let d = min(100, max(0, dash))
        // Learn the real battery size when the app ran without a gap since the last match
        if continuous, let s0 = socAtSync, abs(s0 - d) >= 8, energySinceSync != 0 {
            let dropped = s0 - d
            if (energySinceSync > 0) == (dropped > 0) {
                let implied = abs(energySinceSync / (dropped / 100))
                if implied > 40, implied < 160 {
                    let base = learnedCapacity ?? capacityKWh
                    let blended = base * 0.5 + implied * 0.5
                    learnedCapacity = blended
                    capacityKWh = blended
                    UserDefaults.standard.set(blended, forKey: "learnedCap")
                    log("Learned battery size \(String(format: "%.1f", blended)) kWh")
                }
            }
        }
        // Remember the resting voltage that went with this charge level
        if let rs = restSince, let v = restVoltage, Date().timeIntervalSince(rs) > 600 {
            ocv.append([v, d])
            if ocv.count > 40 { ocv.removeFirst() }
            UserDefaults.standard.set(ocv, forKey: "ocv")
        }
        setSOC(d)
        socAtSync = d
        energySinceSync = 0
        continuous = true
        UserDefaults.standard.set(Date(), forKey: "lastActive")
    }

    // Charge level from resting voltage, using the points learned from earlier matches
    private func ocvEstimate(_ v: Double) -> Double? {
        guard ocv.count >= 5 else { return nil }
        let pts = ocv.sorted { $0[0] < $1[0] }
        guard let lo = pts.last(where: { $0[0] <= v }), let hi = pts.first(where: { $0[0] >= v }) else { return nil }
        if hi[0] == lo[0] { return lo[1] }
        return lo[1] + (hi[1] - lo[1]) * (v - lo[0]) / (hi[0] - lo[0])
    }

    func forgetAdapter() {
        UserDefaults.standard.removeObject(forKey: "adapterID")
        halt()
        if let p = peripheral { central.cancelPeripheralConnection(p) }
        peripheral = nil
        connected = false
        status = "Not connected"
    }

    // MARK: Connecting

    func connectAdapter() {
        switch central.state {
        case .poweredOn: break
        case .unauthorized:
            status = "Allow Bluetooth for Ocean Live in iPhone Settings"
            return
        default:
            status = "Turn on Bluetooth"
            return
        }
        if let s = UserDefaults.standard.string(forKey: "adapterID"), let u = UUID(uuidString: s),
           let p = central.retrievePeripherals(withIdentifiers: [u]).first {
            connect(p)
            return
        }
        found.removeAll()
        foundNames.removeAll()
        // An adapter that another app or iOS already holds does not advertise, so ask for those too
        let svcs = ["FFF0", "FFE0", "18F0", "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"].map { CBUUID(string: $0) }
        for p in central.retrieveConnectedPeripherals(withServices: svcs) where !found.contains(where: { $0.identifier == p.identifier }) {
            foundNames[p.identifier] = p.name ?? "Connected adapter"
            found.append(p)
        }
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        status = "Looking for your adapter"
    }

    func connect(_ p: CBPeripheral) {
        central.stopScan()
        peripheral = p
        p.delegate = self
        writeChar = nil
        notifyChar = nil
        candidates = []
        servicesLeft = 0
        ready = false
        status = "Connecting"
        central.connect(p)
        // If the link does not finish setting up, drop the saved adapter and look again
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 12_000_000_000)
            guard let self, self.peripheral === p, !self.ready else { return }
            self.central.cancelPeripheralConnection(p)
            UserDefaults.standard.removeObject(forKey: "adapterID")
            self.peripheral = nil
            self.log("Connect timed out, scanning again")
            self.connectAdapter()
        }
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            switch central.state {
            case .poweredOn: status = "Ready to connect"
            case .unauthorized: status = "Allow Bluetooth for Ocean Live in iPhone Settings"
            default: status = "Turn on Bluetooth"
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            let n = p.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            if let n, !found.contains(where: { $0.identifier == p.identifier }) {
                foundNames[p.identifier] = n
                found.append(p)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        MainActor.assumeIsolated {
            UserDefaults.standard.set(p.identifier.uuidString, forKey: "adapterID")
            status = "Setting up"
            p.discoverServices(nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            status = "Could not connect"
            if active { central.connect(p) }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            ready = false
            connected = false
            stop()
            if active {
                status = "Reconnecting"
                central.connect(p)
            } else {
                status = "Disconnected"
            }
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            let skip = ["1800", "1801", "180A", "180F"]
            let list = (p.services ?? []).filter { !skip.contains($0.uuid.uuidString) }
            candidates = []
            servicesLeft = list.count
            if list.isEmpty {
                status = "Adapter has no usable service"
                return
            }
            for s in list {
                p.discoverCharacteristics(nil, for: s)
            }
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            let chars = service.characteristics ?? []
            let n = chars.first(where: { $0.properties.contains(.notify) || $0.properties.contains(.indicate) })
            let w = chars.first(where: { $0.properties.contains(.writeWithoutResponse) }) ?? chars.first(where: { $0.properties.contains(.write) })
            candidates.append((service, n, w))
            servicesLeft -= 1
            guard servicesLeft <= 0, !ready else { return }
            // Pick one service that can both listen and write, preferring the common adapter services
            let preferred = ["FFF0", "FFE0", "18F0", "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2"]
            let usable = candidates.filter { $0.notify != nil && $0.write != nil }
            let pick = preferred.compactMap { id in usable.first(where: { $0.svc.uuid.uuidString == id }) }.first ?? usable.first
            guard let pick, let nc = pick.notify, let wc = pick.write else {
                status = "Adapter is missing a data channel"
                log("No usable service")
                return
            }
            notifyChar = nc
            writeChar = wc
            log("Using service \(pick.svc.uuid.uuidString)")
            p.setNotifyValue(true, for: nc)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if let error {
                status = "Could not start notifications"
                log("Notify error \(error.localizedDescription)")
                return
            }
            guard c.isNotifying, c === notifyChar, !ready else { return }
            ready = true
            connected = true
            status = "Connected to \(p.name ?? "adapter")"
            if active, let go = startAction { go() }
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let d = c.value, let s = String(data: d, encoding: .utf8) else { return }
            buffer += s
            if buffer.contains(">"), let cont = pending {
                pending = nil
                pendingToken = nil
                let out = buffer
                buffer = ""
                cont.resume(returning: out)
            }
        }
    }

    // MARK: Sending commands

    func send(_ cmd: String, timeout: Double = 3) async -> String {
        guard let p = peripheral, let w = writeChar else { return "" }
        buffer = ""
        return await withCheckedContinuation { (cont: CheckedContinuation<String, Never>) in
            let token = UUID()
            pending = cont
            pendingToken = token
            let data = Data((cmd + "\r").utf8)
            p.writeValue(data, for: w, type: w.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse)
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                guard let self, self.pendingToken == token, let c = self.pending else { return }
                self.pending = nil
                self.pendingToken = nil
                let out = self.buffer
                self.buffer = ""
                c.resume(returning: out)
            }
        }
    }

    // MARK: Read only scan (service 22 only, never writes)

    func stopScan() { scanCancel = true }

    func startScan(header: String, rx: String, from: String, to: String) {
        let h = header.trimmingCharacters(in: .whitespaces).uppercased()
        let r = rx.trimmingCharacters(in: .whitespaces).uppercased()
        let a = from.trimmingCharacters(in: .whitespaces).uppercased()
        let b = to.trimmingCharacters(in: .whitespaces).uppercased()
        func hex(_ x: String, _ n: Int) -> Bool { x.count == n && x.range(of: "^[0-9A-F]+$", options: .regularExpression) != nil }
        guard hex(h, 3), hex(r, 3), hex(a, 4), hex(b, 4), let lo = Int(a, radix: 16), let hi = Int(b, radix: 16), hi >= lo else {
            scanText = "Check the entries. Header and reply are 3 hex characters and the range is 4 hex characters"
            return
        }
        guard hi - lo < 4096 else { scanText = "Keep the range to 4096 identifiers or fewer"; return }
        guard ready, active else { scanText = "Press Start first so the adapter is connected"; return }
        guard !holdLoop, !scanning else { return }
        holdLoop = true
        scanning = true
        scanCancel = false
        scanText = "Scan \(h) to \(r), \(a) to \(b)\n"
        Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            _ = await self.send("ATSH" + h)
            _ = await self.send("ATCRA" + r)
            var hits = 0
            for v in lo...hi {
                if self.scanCancel { break }
                let did = String(format: "%04X", v)
                let cmd = "22" + did
                let resp = await self.send(cmd, timeout: 1.5)
                if let bytes = Self.parseBytes(resp, cmd: cmd) {
                    let hexs = bytes.map { String(format: "%02X", Int($0)) }.joined()
                    self.scanText += "\(did) = \(hexs)\n"
                    hits += 1
                } else {
                    let flat = resp.uppercased().replacingOccurrences(of: " ", with: "")
                    if flat.contains("7F2233") { self.scanText += "\(did) locked\n"; hits += 1 }
                }
            }
            self.scanText += self.scanCancel ? "Stopped. \(hits) found\n" : "Done. \(hits) found\n"
            self.scanning = false
            self.holdLoop = false
        }
    }

    // MARK: Test command (for your own car, with safety checks)

    // Only these services can be sent. 2F is input output control and 31 is routine control.
    // Everything that writes data, changes security, resets, clears codes or reflashes is blocked.
    // 10 03 (extended session) is only ever sent by the app itself when the switch is on.
    static func checkTestCommand(header: String, rx: String, cmd: String) -> String? {
        let hex = "^[0-9A-Fa-f]+$"
        guard header.range(of: "^[0-9A-Fa-f]{3}$", options: .regularExpression) != nil else { return "Header must be 3 hex characters, like 7E1" }
        guard rx.isEmpty || rx.range(of: "^[0-9A-Fa-f]{3}$", options: .regularExpression) != nil else { return "Reply address must be 3 hex characters, like 7E9" }
        guard cmd.range(of: hex, options: .regularExpression) != nil, cmd.count % 2 == 0 else { return "Command must be hex bytes, like 31010203" }
        guard cmd.count >= 6, cmd.count <= 14 else { return "Command must be 3 to 7 bytes" }
        let svc = String(cmd.prefix(2)).uppercased()
        guard svc == "31" || svc == "2F" else { return "Only service 31 and 2F are allowed here" }
        return nil
    }

    func runTestCommand(header: String, rx: String, cmd: String, session: Bool, speedKmh: Double?) {
        let h = header.trimmingCharacters(in: .whitespaces).uppercased()
        let r = rx.trimmingCharacters(in: .whitespaces).uppercased()
        let c = cmd.replacingOccurrences(of: " ", with: "").uppercased()
        if let bad = Self.checkTestCommand(header: h, rx: r, cmd: c) { testResult = bad; return }
        guard ready, active else { testResult = "Press Start first so the adapter is connected"; return }
        guard let sp = speedKmh, sp >= 0 else { testResult = "Waiting for a GPS speed. The car must be parked"; return }
        guard sp < 1 else { testResult = "Blocked. The car is moving"; return }
        if (live["charging"] ?? 0) > 0.5 { testResult = "Blocked. Wait until the car is not charging, or change this rule on purpose"; return }
        guard !holdLoop else { return }
        holdLoop = true
        testResult = "Sending"
        Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            _ = await self.send("ATSH" + h)
            _ = await self.send(r.isEmpty ? "ATAR" : "ATCRA" + r)
            var out: [String] = []
            if session {
                let s = await self.send("1003", timeout: 3).trimmingCharacters(in: .whitespacesAndNewlines)
                out.append("1003 -> " + s)
                self.log("TEST 1003 " + s)
                if !s.uppercased().replacingOccurrences(of: " ", with: "").contains("5003") {
                    self.testResult = out.joined(separator: "\n") + "\nSession was refused. Command not sent"
                    self.holdLoop = false
                    return
                }
            }
            let resp = await self.send(c, timeout: 4).trimmingCharacters(in: .whitespacesAndNewlines)
            out.append(c + " -> " + resp)
            self.log("TEST " + c + " " + resp)
            let flat = resp.uppercased().replacingOccurrences(of: " ", with: "")
            let svc = Int(c.prefix(2), radix: 16) ?? 0
            let ok = String(format: "%02X", svc + 0x40)
            if flat.contains("7F" + String(c.prefix(2))) { out.append("The car refused it (negative response)") }
            else if flat.contains(ok) { out.append("The car accepted it") }
            else { out.append("No clear answer from the car") }
            self.testResult = out.joined(separator: "\n")
            self.holdLoop = false
        }
    }

    static func varMap(_ b: [Double]) -> [Character: Double] {
        var m: [Character: Double] = [:]
        for (idx, name) in Array("ABCDEFGH").enumerated() {
            m[name] = idx < b.count ? b[idx] : 0
        }
        // S, T, U are signed 16 bit numbers made from bytes A B, C D and E F
        func s16(_ hi: Double, _ lo: Double) -> Double {
            let v = Int(hi) * 256 + Int(lo)
            return Double(v >= 32768 ? v - 65536 : v)
        }
        m["S"] = b.count >= 2 ? s16(b[0], b[1]) : 0
        m["T"] = b.count >= 4 ? s16(b[2], b[3]) : 0
        m["U"] = b.count >= 6 ? s16(b[4], b[5]) : 0
        return m
    }

    static func parseBytes(_ resp: String, cmd: String) -> [Double]? {
        guard cmd.count >= 2, let svc = Int(cmd.prefix(2), radix: 16) else { return nil }
        let prefix = String(format: "%02X", svc + 0x40) + cmd.dropFirst(2).uppercased()
        let lines = resp.replacingOccurrences(of: ">", with: "")
            .components(separatedBy: CharacterSet(charactersIn: "\r\n"))
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .filter { $0.range(of: "^[0-9A-F]{3}$", options: .regularExpression) == nil }
            .map { $0.replacingOccurrences(of: "^[0-9A-F]:", with: "", options: .regularExpression) }
        let hex = lines.joined().replacingOccurrences(of: " ", with: "").uppercased()
        guard let r = hex.range(of: prefix) else { return nil }
        let rest = Array(hex[r.upperBound...])
        var out: [Double] = []
        var k = 0
        while k + 1 < rest.count {
            if let v = UInt8(String(rest[k...k + 1]), radix: 16) { out.append(Double(v)) }
            k += 2
        }
        return out
    }

    // MARK: Start and stop

    // Starts sending. Connects to the saved adapter first if needed, and reconnects by itself if the link drops.
    func begin(pids: [PIDItem], interval: Double, apiKey: String, token: String, location: LocationManager, capacity: Double) {
        active = true
        tripMeters = 0
        tripKWh = 0
        lastLoc = nil
        startAction = { [weak self] in
            self?.runLoop(pids: pids, interval: interval, apiKey: apiKey, token: token, location: location, capacity: capacity)
        }
        if ready {
            startAction?()
        } else {
            connectAdapter()
        }
    }

    func halt() {
        active = false
        startAction = nil
        stop()
        if let s = estSOC { UserDefaults.standard.set(s, forKey: "estSOC") }
        UserDefaults.standard.set(Date(), forKey: "lastActive")
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        running = false
        continuous = false
        restSince = nil
        UIApplication.shared.isIdleTimerDisabled = false
        if let c = pending {
            pending = nil
            pendingToken = nil
            c.resume(returning: "")
        }
    }

    private func runLoop(pids: [PIDItem], interval: Double, apiKey: String, token: String, location: LocationManager, capacity: Double) {
        guard ready, !running else { return }
        running = true
        capacityKWh = learnedCapacity ?? max(10, capacity)
        lastSample = nil
        smoothKW = nil
        UIApplication.shared.isIdleTimerDisabled = true
        location.start()
        pollTask = Task { [weak self] in
            guard let self else { return }
            for c in ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATST20"] {
                let r = await self.send(c, timeout: c == "ATZ" ? 4 : 2)
                self.log("\(c) \(r.trimmingCharacters(in: .whitespacesAndNewlines))")
            }
            self.status = "Sending data"
            var lastUpload = Date.distantPast
            while !Task.isCancelled {
                while self.holdLoop && !Task.isCancelled { try? await Task.sleep(nanoseconds: 200_000_000) }
                var tlm: [String: Any] = ["utc": Int(Date().timeIntervalSince1970)]
                var header = ""
                var rx = ""
                for p in pids where p.disabled != true {
                    if Task.isCancelled { break }
                    if p.header != header {
                        _ = await self.send("ATSH" + p.header)
                        header = p.header
                    }
                    let wantRx = p.rx ?? ""
                    if wantRx != rx {
                        _ = await self.send(wantRx.isEmpty ? "ATAR" : "ATCRA" + wantRx)
                        rx = wantRx
                    }
                    let r = await self.send(p.cmd)
                    guard let bytes = Self.parseBytes(r, cmd: p.cmd) else {
                        self.log("\(p.name) no data")
                        continue
                    }
                    var calc = Calc(p.expr, vars: Self.varMap(bytes))
                    if let v = calc.parse() {
                        tlm[p.field] = v
                        self.live[p.field] = v
                    } else {
                        self.log("\(p.name) bad formula")
                    }
                }
                if let loc = location.last {
                    tlm["lat"] = loc.coordinate.latitude
                    tlm["lon"] = loc.coordinate.longitude
                    if tlm["speed"] == nil, loc.speed >= 0 { tlm["speed"] = loc.speed * 3.6 }
                    // Trip distance from GPS, ignoring poor fixes and jumps
                    if loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 50 {
                        if let prev = self.lastLoc {
                            let d = loc.distance(from: prev)
                            if d > 5, d < 500 {
                                self.tripMeters += d
                                self.lastLoc = loc
                            } else if d >= 500 {
                                self.lastLoc = loc
                            }
                        } else {
                            self.lastLoc = loc
                        }
                    }
                }
                // The charge level comes straight from the battery module when it answers.
                // If it does not, the app falls back to counting energy below.
                var carSOC: Double? = nil
                if let s = tlm["soc"] as? Double, s >= 0, s <= 100 {
                    carSOC = s
                } else {
                    tlm["soc"] = nil
                }
                self.socFromCar = carSOC != nil
                if let s = carSOC {
                    self.estSOC = s
                } else if self.estSOC == nil {
                    self.status = "Match to car screen to set charge"
                }
                // Power from pack voltage and current, and a running SOC estimate by counting energy.
                // Positive power means the pack is discharging, negative means charging.
                if let v = tlm["voltage"] as? Double, let i = tlm["current"] as? Double {
                    let raw = v * i / 1000.0
                    // Smooth the power so small swings do not flip the charging state
                    let kw = (self.smoothKW.map { $0 * 0.8 + raw * 0.2 }) ?? raw
                    self.smoothKW = kw
                    // Charging switches on below minus 1.5 kW and only switches off again above minus 0.5 kW
                    if self.chargingNow {
                        if kw > -0.5 { self.chargingNow = false }
                    } else if kw < -1.5 {
                        self.chargingNow = true
                    }
                    let shown = abs(kw) < 0.3 ? 0 : kw
                    tlm["power"] = shown
                    self.live["power"] = shown
                    self.live["charging"] = self.chargingNow ? 1 : 0
                    tlm["is_charging"] = self.chargingNow ? 1 : 0
                    let now = Date()
                    if carSOC == nil, let last = self.lastSample, let e = self.estSOC {
                        let dt = now.timeIntervalSince(last)
                        if dt > 0, dt < 30 {
                            let kwh = kw * dt / 3600.0
                            self.estSOC = min(100, max(0, e - kwh / self.capacityKWh * 100.0))
                            self.energySinceSync += kwh
                            if !self.chargingNow { self.tripKWh += kwh }
                        }
                    }
                    self.lastSample = now
                    // While parked, nudge the estimate toward the charge level that matches the resting voltage
                    if abs(kw) < 0.3 {
                        if self.restSince == nil { self.restSince = now }
                        self.restVoltage = v
                        if carSOC == nil, let rs = self.restSince, now.timeIntervalSince(rs) > 900,
                           let e = self.estSOC, let o = self.ocvEstimate(v) {
                            self.setSOC(e + min(2, max(-2, o - e)))
                            self.restSince = now
                        }
                    } else if abs(kw) > 0.5 {
                        self.restSince = nil
                    }
                }
                if let e = self.estSOC {
                    tlm["soc"] = (e * 10).rounded() / 10
                }
                if tlm["is_charging"] == nil { tlm["is_charging"] = 0 }
                if Date().timeIntervalSince(lastUpload) >= max(1, interval) {
                    if tlm["soc"] != nil {
                        let hasABRP = !apiKey.trimmingCharacters(in: .whitespaces).isEmpty && !token.trimmingCharacters(in: .whitespaces).isEmpty
                        if hasABRP {
                            await self.upload(tlm, apiKey: apiKey, token: token)
                        } else {
                            self.abrpStatus = "ABRP is off. Add the API key and token in Settings to send"
                        }
                        if let s = self.estSOC { UserDefaults.standard.set(s, forKey: "estSOC") }
                        UserDefaults.standard.set(Date(), forKey: "lastActive")
                    }
                    lastUpload = Date()
                }
                try? await Task.sleep(nanoseconds: 300_000_000)
            }
        }
    }

    private func upload(_ tlm: [String: Any], apiKey: String, token: String) async {
        guard let json = try? JSONSerialization.data(withJSONObject: tlm),
              let s = String(data: json, encoding: .utf8),
              var comps = URLComponents(string: "https://api.iternio.com/1/tlm/send") else { return }
        comps.queryItems = [
            URLQueryItem(name: "api_key", value: apiKey),
            URLQueryItem(name: "token", value: token),
            URLQueryItem(name: "tlm", value: s)
        ]
        guard let url = comps.url else { return }
        do {
            let (data, resp) = try await URLSession.shared.data(from: url)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            log("ABRP \(code) \(String(decoding: data.prefix(80), as: UTF8.self))")
            if code == 200 {
                abrpStatus = "Sending to ABRP"
                lastSent = Date()
            } else {
                abrpStatus = "ABRP error \(code)"
            }
        } catch {
            abrpStatus = "No internet"
            log("ABRP failed \(error.localizedDescription)")
        }
    }
}

// MARK: - UI

struct StatTile: View {
    let title: String
    let value: String
    let unit: String
    let icon: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: icon).foregroundStyle(tint)
                Text(title).font(.footnote).foregroundStyle(.secondary)
            }
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value).font(.system(size: 30, weight: .semibold, design: .rounded))
                Text(unit).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct ContentView: View {
    @AppStorage("apiKey") private var apiKey = ""
    @AppStorage("token") private var token = ""
    @AppStorage("interval") private var interval = 5.0
    @AppStorage("capacityKWh") private var capacityText = "106.5"
    @AppStorage("model") private var model = "big"
    @AppStorage("testHeader") private var testHeader = ""
    @AppStorage("testRx") private var testRx = ""
    @AppStorage("testCmd") private var testCmd = ""
    @AppStorage("testSession") private var testSession = false
    @State private var confirmTest = false
    @State private var scanHeader = "7E1"
    @State private var scanRx = "7E9"
    @State private var scanFrom = "2000"
    @State private var scanTo = "21FF"

    @StateObject private var obd = OBDManager()
    @StateObject private var loc = LocationManager()

    @State private var showSettings = false
    @State private var showSync = false
    @State private var socText = ""
    @State private var startAfterSync = false

    private var pids: [PIDItem] {
        (try? JSONDecoder().decode([PIDItem].self, from: Data(defaultPIDs.utf8))) ?? []
    }

    private var abrpKeyMissing: Bool {
        apiKey.trimmingCharacters(in: .whitespaces).isEmpty && !token.trimmingCharacters(in: .whitespaces).isEmpty
    }
    private var abrpTokenMissing: Bool {
        token.trimmingCharacters(in: .whitespaces).isEmpty && !apiKey.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private var charging: Bool { (obd.live["charging"] ?? 0) > 0.5 }

    // Usable battery size in kWh for the chosen model
    private var capacity: Double {
        switch model {
        case "sport": return 75
        case "custom": return Double(capacityText) ?? 106.5
        default: return 106.5
        }
    }

    private func socColor(_ v: Double) -> Color {
        if v < 15 { return .red }
        if v < 30 { return .orange }
        return Color(red: 0.2, green: 0.85, blue: 0.6)
    }

    private func fmt(_ key: String, _ digits: Int) -> String {
        guard let v = obd.live[key] else { return "--" }
        return String(format: "%.\(digits)f", v)
    }

    private var usableKWh: Double { obd.learnedCapacity ?? capacity }

    // Wh per mile from this trip, once there is enough distance
    private var whPerMile: Double? {
        let miles = obd.tripMeters / 1609.34
        guard miles >= 0.5 else { return nil }
        return obd.tripKWh * 1000 / miles
    }

    private var rangeMiles: Double? {
        guard let w = whPerMile, w > 50, let soc = obd.estSOC else { return nil }
        return soc / 100 * usableKWh * 1000 / w
    }

    private func startNow() {
        obd.begin(pids: pids, interval: interval, apiKey: apiKey, token: token, location: loc, capacity: capacity)
    }

    private func toggle() {
        if obd.active {
            obd.halt()
            loc.stop()
            return
        }
        // ABRP is optional. With no keys the app just shows the car's charge and data.
        // The charge level is read from the car, so there is nothing to ask before starting.
        startNow()
    }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.03, green: 0.09, blue: 0.18), Color(red: 0.02, green: 0.04, blue: 0.09)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 20) {
                    header
                    ring
                    if !obd.found.isEmpty && !obd.connected {
                        adapterPicker
                    }
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                        StatTile(title: "Power", value: fmt("power", 1), unit: "kW", icon: "bolt.fill", tint: .yellow)
                        StatTile(title: "Battery temp", value: fmt("batt_temp", 0), unit: "°C", icon: "thermometer.medium", tint: .orange)
                        StatTile(title: "Voltage", value: fmt("voltage", 0), unit: "V", icon: "battery.100", tint: .cyan)
                        StatTile(title: "Current", value: fmt("current", 0), unit: "A", icon: "waveform.path.ecg", tint: .mint)
                        StatTile(title: "Efficiency", value: whPerMile.map { String(format: "%.0f", $0) } ?? "--", unit: "Wh/mi", icon: "speedometer", tint: .green)
                        StatTile(title: "Range left", value: rangeMiles.map { String(format: "%.0f", $0) } ?? "--", unit: "mi", icon: "mappin.and.ellipse", tint: .pink)
                    }
                    abrpCard
                    controls
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showSettings) { settings }
        .sheet(isPresented: $showSync) { sync }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text("Ocean Live").font(.system(size: 28, weight: .bold, design: .rounded))
                HStack(spacing: 6) {
                    Circle()
                        .fill(obd.running ? Color.green : (obd.connected ? Color.yellow : Color.gray))
                        .frame(width: 8, height: 8)
                    Text(obd.status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(Color.white.opacity(0.1), in: Circle())
            }
        }
    }

    private var ring: some View {
        let soc = obd.estSOC ?? 0
        return VStack(spacing: 14) {
            ZStack {
                Circle().stroke(Color.white.opacity(0.08), lineWidth: 20)
                Circle()
                    .trim(from: 0, to: CGFloat(soc / 100))
                    .stroke(socColor(soc), style: StrokeStyle(lineWidth: 20, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.6), value: soc)
                VStack(spacing: 2) {
                    Text(obd.estSOC == nil ? "--" : String(format: "%.0f%%", soc.rounded(.down)))
                        .font(.system(size: 68, weight: .bold, design: .rounded))
                    Text(obd.estSOC == nil ? "waiting for the car" : (charging ? "charging" : (obd.socFromCar ? String(format: "%.1f%% from your car", soc) : "estimated charge")))
                        .font(.footnote)
                        .foregroundStyle(charging ? Color.yellow : Color.secondary)
                }
            }
            .frame(width: 250, height: 250)
            .padding(.top, 8)

            if !obd.socFromCar {
                Button("Match to car screen") { showSync = true }
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.cyan)
            }
        }
    }

    private var adapterPicker: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Choose your adapter").font(.headline)
            ForEach(obd.found, id: \.identifier) { p in
                Button { obd.connect(p) } label: {
                    HStack {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                        Text(obd.foundNames[p.identifier] ?? p.name ?? "Unknown")
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote)
                    }
                    .padding(14)
                    .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var abrpCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "map.fill")
                .font(.title3)
                .foregroundStyle(.cyan)
                .frame(width: 40, height: 40)
                .background(Color.cyan.opacity(0.15), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text("A Better Route Planner").font(.subheadline.weight(.semibold))
                Text(obd.abrpStatus).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            if let t = obd.lastSent {
                Text(t, style: .time).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var controls: some View {
        Button(action: toggle) {
            Text(obd.active ? "Stop" : "Start")
                .font(.title3.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(obd.active ? Color.red.opacity(0.85) : Color(red: 0.1, green: 0.55, blue: 0.95),
                            in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .foregroundStyle(.white)
        }
        .padding(.bottom, 8)
    }

    private var sync: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Percent on the car screen", text: $socText)
                        .keyboardType(.decimalPad)
                } footer: {
                    Text("The app estimates your charge from battery power. Enter what the car shows now to start or correct it. Each match also teaches the app your real battery size.")
                }
                Section {
                    Button("Apply") {
                        if let v = Double(socText) {
                            obd.sync(to: v)
                            showSync = false
                            if startAfterSync {
                                startAfterSync = false
                                startNow()
                            }
                        }
                    }
                }
            }
            .navigationTitle("Match charge")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        startAfterSync = false
                        showSync = false
                    }
                }
            }
        }
        .presentationDetents([.medium])
        .preferredColorScheme(.dark)
    }

    private var settings: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("API key", text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .listRowBackground(abrpKeyMissing ? Color.red.opacity(0.3) : Color(UIColor.secondarySystemGroupedBackground))
                    TextField("Generic token", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .listRowBackground(abrpTokenMissing ? Color.red.opacity(0.3) : Color(UIColor.secondarySystemGroupedBackground))
                    Stepper("Send every \(Int(interval)) sec", value: $interval, in: 1...60)
                } header: {
                    Text("ABRP")
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        if abrpKeyMissing || abrpTokenMissing {
                            Text("Both the API key and the token are needed to send to ABRP. Fill in the red field, or clear both to just watch your car.")
                                .foregroundStyle(.red)
                        }
                        Text("Leave both empty if you only want to watch your charge and car data. Token comes from ABRP. Open your car, choose Live data, then Generic. The API key is a free Telemetry key from abetterrouteplanner.com/resources/api. This is an unofficial community app and only reads data from your car.")
                    }
                }
                Section {
                    Picker("Model", selection: $model) {
                        Text("Ocean One, Extreme or Ultra").tag("big")
                        Text("Ocean Sport").tag("sport")
                        Text("Other or custom").tag("custom")
                    }
                    if model == "custom" {
                        TextField("Usable battery in kWh", text: $capacityText)
                            .keyboardType(.decimalPad)
                    } else {
                        HStack {
                            Text("Usable battery")
                            Spacer()
                            Text(String(format: "%.1f kWh", capacity)).foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("Car")
                } footer: {
                    Text("The One, Extreme and Ultra use 106.5 kWh and the Sport uses 75 kWh. Published figures for the Sport vary between 71 and 75 kWh. The app adjusts this on its own as you match the car screen after drives.")
                }
                if let l = obd.learnedCapacity {
                    Section {
                        HStack {
                            Text("Learned battery size")
                            Spacer()
                            Text(String(format: "%.1f kWh", l)).foregroundStyle(.secondary)
                        }
                        Button("Reset learning", role: .destructive) { obd.resetLearning() }
                    }
                }
                Section("Adapter") {
                    Text(obd.status)
                    if obd.hasSavedAdapter {
                        Button("Forget saved adapter", role: .destructive) { obd.forgetAdapter() }
                    } else {
                        Button("Look for adapters") { obd.connectAdapter() }
                    }
                }
                Section {
                    Menu("Pick a module") {
                        Button("Battery 7E1 and 7E9") { scanHeader = "7E1"; scanRx = "7E9"; scanFrom = "2000"; scanTo = "21FF" }
                        Button("Climate 7F0 and 7F8") { scanHeader = "7F0"; scanRx = "7F8"; scanFrom = "0000"; scanTo = "0FFF" }
                        Button("Heater 752 and 75A") { scanHeader = "752"; scanRx = "75A"; scanFrom = "0000"; scanTo = "0FFF" }
                        Button("Heater 7D5 and 7DD") { scanHeader = "7D5"; scanRx = "7DD"; scanFrom = "0000"; scanTo = "0FFF" }
                        Button("Key module 7A2 and 7AA") { scanHeader = "7A2"; scanRx = "7AA"; scanFrom = "F100"; scanTo = "FFFF" }
                    }
                    TextField("Header", text: $scanHeader).textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .onChange(of: scanHeader) { _, v in
                            let d: (String, String, String)?
                            switch v.uppercased() {
                            case "7E1": d = ("7E9", "2000", "21FF")
                            case "7F0": d = ("7F8", "0000", "0FFF")
                            case "752": d = ("75A", "0000", "0FFF")
                            case "7D5": d = ("7DD", "0000", "0FFF")
                            case "7A2": d = ("7AA", "F100", "FFFF")
                            default: d = nil
                            }
                            if let d { scanRx = d.0; scanFrom = d.1; scanTo = d.2 }
                        }
                    TextField("Reply address", text: $scanRx).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    TextField("First identifier", text: $scanFrom).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    TextField("Last identifier", text: $scanTo).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    if obd.scanning {
                        Button("Stop scan", role: .destructive) { obd.stopScan() }
                    } else {
                        Button("Start scan") { obd.startScan(header: scanHeader, rx: scanRx, from: scanFrom, to: scanTo) }
                    }
                    if !obd.scanText.isEmpty {
                        Text(obd.scanText.split(separator: "\n").suffix(40).joined(separator: "\n"))
                            .font(.system(.caption2, design: .monospaced))
                        ShareLink(item: obd.scanText) { Text("Share results") }
                    }
                } header: {
                    Text("Read only scan")
                } footer: {
                    Text("Asks a module for each identifier in a range and lists the ones that answer. It only reads and never writes. Sending to ABRP pauses while it runs. Do it with the car parked and awake.")
                }
                Section {
                    TextField("Header, like 7E1", text: $testHeader)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("Reply address, like 7E9", text: $testRx)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    TextField("Command in hex", text: $testCmd)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    Toggle("Open extended session first", isOn: $testSession)
                    Button("Send test command") { confirmTest = true }
                    if !obd.testResult.isEmpty {
                        Text(obd.testResult).font(.system(.caption, design: .monospaced))
                    }
                } header: {
                    Text("Preheat test")
                } footer: {
                    Text("Experimental. Sends one command you type in to your own car. Only services 31 and 2F are allowed. It will not send while the car is moving or charging, and it needs the adapter connected. Use at your own risk, and only with a command you have a trusted source for.")
                }
                Section("Activity") {
                    ForEach(Array(obd.logLines.suffix(12).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(.caption2, design: .monospaced))
                    }
                }
            }
            .alert("Send this to your car?", isPresented: $confirmTest) {
                Button("Cancel", role: .cancel) {}
                Button("Send", role: .destructive) {
                    let kmh = loc.last.map { $0.speed >= 0 ? $0.speed * 3.6 : -1 }
                    obd.runTestCommand(header: testHeader, rx: testRx, cmd: testCmd, session: testSession, speedKmh: kmh)
                }
            } message: {
                Text("Header \(testHeader), command \(testCmd)\(testSession ? ", after opening an extended session" : ""). Make sure the car is parked and awake.")
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { showSettings = false } }
            }
        }
        .preferredColorScheme(.dark)
    }
}

@main
struct OceanABRPApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
