package com.fado.karaoke.sync

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Sprawdzanie i nadawanie dostępu do powiadomień (wymagany przez MediaSessionManager). */
object NotificationAccess {

    fun component(context: Context): ComponentName =
        ComponentName(context, SpotifySyncService::class.java)

    fun isGranted(context: Context): Boolean {
        val cn = component(context)
        if (Build.VERSION.SDK_INT >= 27) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            return try {
                nm.isNotificationListenerAccessGranted(cn)
            } catch (e: Exception) {
                false
            }
        }
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == cn }
    }

    /** Ekran z przełącznikiem FA-DO (Android 11+) albo ogólna lista. */
    fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 30) {
            return Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component(context).flattenToString()
                )
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun genericSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** Informacje o aplikacji — tu jest "Zezwól na ustawienia ograniczone" (Android 13+). */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
