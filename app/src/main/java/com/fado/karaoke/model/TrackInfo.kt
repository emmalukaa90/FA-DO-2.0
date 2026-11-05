package com.fado.karaoke.model

import com.fado.karaoke.util.TextUtil
import kotlin.math.roundToLong

data class TrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val packageName: String
) {
    /** Tożsamość utworu niezależna od drobnych zmian metadanych (np. dociągnięty czas trwania). */
    val identity: String =
        packageName + "|" + TextUtil.normalize(artist) + "|" + TextUtil.normalize(title)

    /** Klucz cache tekstów: wykonawca + tytuł + czas trwania (s). */
    val lyricsKey: String =
        TextUtil.normalize(artist) + "|" + TextUtil.normalize(title) + "|" + (durationMs / 1000.0).roundToLong()

    fun sameIdentity(other: TrackInfo?): Boolean = other != null && other.identity == identity

    val isSpotify: Boolean get() = packageName == SPOTIFY_PACKAGE

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        fun create(
            mediaId: String?,
            title: String,
            artist: String,
            album: String,
            durationMs: Long,
            packageName: String
        ): TrackInfo {
            val id = mediaId?.takeIf { it.isNotBlank() } ?: "$packageName|$artist|$title"
            return TrackInfo(id, title.trim(), artist.trim(), album.trim(), durationMs.coerceAtLeast(0L), packageName)
        }
    }
}
