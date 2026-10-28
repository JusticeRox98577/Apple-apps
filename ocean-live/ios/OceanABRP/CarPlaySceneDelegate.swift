import CarPlay
import UIKit

// Shows live battery information on the car's screen. The phone app does the Bluetooth
// work, so press Start in the app on the phone first.
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var controller: CPInterfaceController?
    private var timer: Timer?

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController) {
        controller = interfaceController
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            self?.refresh()
        }
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        timer?.invalidate()
        timer = nil
        controller = nil
    }

    private func refresh() {
        MainActor.assumeIsolated {
            let obd = OBDManager.shared
            func num(_ key: String, _ digits: Int, _ unit: String) -> String {
                guard let v = obd.live[key] else { return "--" }
                return String(format: "%.\(digits)f", v) + " " + unit
            }
            let charge = obd.estSOC.map { String(format: "%.1f%%", $0) } ?? "--"
            let items = [
                CPInformationItem(title: "Charge", detail: charge),
                CPInformationItem(title: "Power", detail: num("power", 1, "kW")),
                CPInformationItem(title: "Battery temp", detail: num("batt_temp", 0, "°C")),
                CPInformationItem(title: "Status", detail: obd.connected ? "Connected" : obd.status)
            ]
            let template = CPInformationTemplate(title: "Ocean Live", layout: .leading, items: items, actions: [])
            controller?.setRootTemplate(template, animated: false, completion: nil)
        }
    }
}
