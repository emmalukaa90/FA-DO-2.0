package com.fado.karaoke

import android.app.Application
import com.fado.karaoke.core.FadoCore

class FadoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FadoCore.init(this)
        FadoCore.ensureSyncStarted()
    }
}
