package com.justice.oceanlive

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

// Shows live battery information on the car's screen through Android Auto.
// The phone app does the Bluetooth work, so press Start in the app first.
class OceanCarService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = OceanCarScreen(carContext)
    }
}

class OceanCarScreen(carContext: CarContext) : Screen(carContext) {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 3000)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { handler.post(tick) }
            override fun onStop(owner: LifecycleOwner) { handler.removeCallbacks(tick) }
        })
    }

    private fun num(v: Double?, digits: Int, unit: String): String =
        if (v == null) "--" else String.format("%.${digits}f", v) + " " + unit

    override fun onGetTemplate(): Template {
        val ui = Engine.ui.value
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Charge")
                .addText(ui.estSoc?.let { String.format("%.1f%%", it) } ?: "--").build())
            .addRow(Row.Builder().setTitle("Power").addText(num(ui.live["power"], 1, "kW")).build())
            .addRow(Row.Builder().setTitle("Battery temp").addText(num(ui.live["batt_temp"], 0, "°C")).build())
            .addRow(Row.Builder().setTitle("Status")
                .addText(if (ui.connected) "Connected" else ui.status).build())
            .build()
        return PaneTemplate.Builder(pane).setTitle("Ocean Live").build()
    }
}
