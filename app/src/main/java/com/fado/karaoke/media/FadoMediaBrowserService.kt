package com.fado.karaoke.media

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.service.media.MediaBrowserService
import com.fado.karaoke.R
import com.fado.karaoke.core.FadoState

/**
 * Usługa multimediów dla Android Auto. Po wybraniu "FA-DO Tekst" jako źródła muzyki
 * w Android Auto kafelek multimediów (obok Map Google) pokazuje tekst piosenki z Spotify.
 * Sama nie odtwarza dźwięku — dźwięk dalej idzie ze Spotify.
 */
class FadoMediaBrowserService : MediaBrowserService() {

    override fun onCreate() {
        super.onCreate()
        sessionToken = FadoMediaRelay.attach(this)
    }

    override fun onDestroy() {
        FadoMediaRelay.detach()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        val track = FadoState.track
        val item = MediaDescription.Builder()
            .setMediaId(ITEM_CURRENT)
            .setTitle(getString(R.string.media_browse_current))
            .setSubtitle(track?.let { it.title + " — " + it.artist } ?: getString(R.string.media_no_track))
            .build()
        result.sendResult(mutableListOf(MediaBrowser.MediaItem(item, MediaBrowser.MediaItem.FLAG_PLAYABLE)))
    }

    companion object {
        private const val ROOT_ID = "fado-root"
        private const val ITEM_CURRENT = "fado-current"
    }
}
