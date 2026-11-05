package com.fado.karaoke.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fado.karaoke.R
import com.fado.karaoke.core.BounceMode
import com.fado.karaoke.core.FadoCore
import com.fado.karaoke.core.FadoSettings
import com.fado.karaoke.core.FadoState
import com.fado.karaoke.render.KaraokeSurfaceRenderer
import com.fado.karaoke.util.TextUtil

/**
 * Ekran Android Auto: NavigationTemplate bez trasy = cała powierzchnia dla karaoke,
 * plus pasek akcji: [−] [+] (przesunięcie o 100 ms), [Słowa/Sylaby], [▶ demo].
 *
 * Wytyczne Android Auto dopuszczają jeden przycisk z napisem na szablon,
 * dlatego pozostałe akcje mają ikony; bieżące przesunięcie widać na ekranie i w komunikacie.
 */
class FadoCarScreen(
    carContext: CarContext,
    private val renderer: KaraokeSurfaceRenderer
) : Screen(carContext) {

    private val settingsListener: () -> Unit = { invalidate() }
    private val stateListener: () -> Unit = { onStateChanged() }
    private var lastDemo = FadoState.isDemo

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    FadoSettings.addListener(settingsListener)
                    FadoState.addListener(stateListener)
                    renderer.start()
                }
                Lifecycle.Event.ON_STOP -> {
                    FadoSettings.removeListener(settingsListener)
                    FadoState.removeListener(stateListener)
                    renderer.stop()
                }
                else -> Unit
            }
        })
    }

    private fun onStateChanged() {
        val demo = FadoState.isDemo
        if (demo != lastDemo) {
            lastDemo = demo
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        return try {
            NavigationTemplate.Builder().setActionStrip(buildActionStrip(withLabel = true)).build()
        } catch (e: IllegalArgumentException) {
            // Host o ostrzejszych ograniczeniach: same ikony.
            Log.w("FadoCar", "Pasek akcji odrzucony, wersja z ikonami", e)
            NavigationTemplate.Builder().setActionStrip(buildActionStrip(withLabel = false)).build()
        }
    }

    private fun buildActionStrip(withLabel: Boolean): ActionStrip {
        val modeAction = Action.Builder()
            .setOnClickListener { FadoSettings.toggleBounceMode() }
        if (withLabel) {
            val label = if (FadoSettings.bounceMode == BounceMode.WORD) R.string.car_mode_word else R.string.car_mode_syllable
            modeAction.setTitle(carContext.getString(label))
        } else {
            val icon = if (FadoSettings.bounceMode == BounceMode.WORD) R.drawable.ic_car_ball else R.drawable.ic_car_ball_syllables
            modeAction.setIcon(icon(icon))
        }

        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_minus))
                    .setOnClickListener { changeOffset(-100) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_plus))
                    .setOnClickListener { changeOffset(100) }
                    .build()
            )
            .addAction(modeAction.build())
            .addAction(
                Action.Builder()
                    .setIcon(icon(if (FadoState.isDemo) R.drawable.ic_car_stop else R.drawable.ic_car_play))
                    .setOnClickListener { FadoCore.toggleDemo() }
                    .build()
            )
            .build()
    }

    private fun changeOffset(delta: Int) {
        FadoSettings.adjustOffset(delta)
        val text = carContext.getString(R.string.offset_format, TextUtil.formatOffset(FadoSettings.offsetMs))
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()
    }

    private fun icon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
}
