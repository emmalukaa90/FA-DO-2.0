package com.fado.karaoke.car

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Punkt wejścia Android Auto. Kategoria NAVIGATION (manifest) sprawia, że host daje
 * aplikacji własną powierzchnię do rysowania w dużym kafelku Coolwalk — bez ograniczeń
 * szablonów list/siatek, więc animacja karaoke działa płynnie podczas jazdy.
 */
class FadoCarAppService : CarAppService() {

    @SuppressLint("DiscouragedApi")
    override fun createHostValidator(): HostValidator {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

        // Wersja release: lista zaufanych hostów (Android Auto, DHU) z biblioteki Car App.
        val allowList = resources.getIdentifier("hosts_allowlist_sample", "array", packageName)
        if (allowList == 0) {
            Log.w("FadoCar", "Brak listy hostów w bibliotece — dopuszczam wszystkie hosty")
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
        return HostValidator.Builder(applicationContext)
            .addAllowedHosts(allowList)
            .build()
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = FadoCarSession()
}
