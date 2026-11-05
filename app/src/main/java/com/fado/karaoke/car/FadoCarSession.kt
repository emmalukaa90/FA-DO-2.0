package com.fado.karaoke.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fado.karaoke.core.FadoCore
import com.fado.karaoke.render.KaraokeSurfaceRenderer

class FadoCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        FadoCore.init(carContext)
        FadoCore.ensureSyncStarted()

        // Ten sam silnik co na telefonie, limit 60 FPS (strumień wideo Android Auto).
        val renderer = KaraokeSurfaceRenderer(carContext, "car", maxFps = 60)
        val surfaceCallback = FadoSurfaceCallback(renderer)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) renderer.release()
        })
        return FadoCarScreen(carContext, renderer)
    }
}
