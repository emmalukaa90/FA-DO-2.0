package com.fado.karaoke.sync

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log
import com.fado.karaoke.core.FadoCore

/**
 * NotificationListenerService — sam w sobie nie czyta powiadomień. Jego włączenie
 * daje aplikacji prawo do MediaSessionManager.getActiveSessions(), czyli do
 * dokładnej pozycji odtwarzania Spotify. System utrzymuje go przy życiu,
 * więc synchronizacja działa też wtedy, gdy ekran telefonu jest wyłączony (w aucie).
 */
class SpotifySyncService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        FadoCore.init(applicationContext)
        FadoCore.syncEngine.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        FadoCore.init(applicationContext)
        FadoCore.syncEngine.onListenerDisconnected()
        try {
            requestRebind(ComponentName(this, SpotifySyncService::class.java))
        } catch (e: Exception) {
            Log.w("FadoSync", "requestRebind nie powiódł się", e)
        }
    }
}
