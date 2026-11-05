package com.fado.karaoke.car

import android.graphics.Rect
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.fado.karaoke.render.KaraokeSurfaceRenderer

/**
 * Przekazuje powierzchnię Android Auto do renderera.
 * Układ tekstu opiera się na "stable area" (obszar zawsze widoczny), więc pojawianie się
 * i chowanie paska przycisków nie powoduje przeskakiwania linii.
 */
class FadoSurfaceCallback(private val renderer: KaraokeSurfaceRenderer) : SurfaceCallback {

    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        renderer.setSurface(
            surfaceContainer.surface,
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi
        )
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        if (stableArea == null) renderer.setContentArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        renderer.setContentArea(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        renderer.setSurface(null, 0, 0, 0)
    }
}
