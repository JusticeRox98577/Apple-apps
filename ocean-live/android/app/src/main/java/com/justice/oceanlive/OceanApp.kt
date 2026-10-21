package com.justice.oceanlive

import android.app.Application

class OceanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}
