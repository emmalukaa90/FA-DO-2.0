package com.fado.karaoke.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimalny klient LRCLIB (https://lrclib.net) na HttpURLConnection + org.json.
 * Bez Retrofit/OkHttp: mniej zależności, mniejszy APK, brak problemów z wersjami.
 * Wywoływać wyłącznie z wątku w tle.
 */
class LrclibClient(private val userAgent: String) {

    data class Record(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSec: Double,
        val instrumental: Boolean,
        val plainLyrics: String?,
        val syncedLyrics: String?
    )

    /** Dokładne dopasowanie po sygnaturze utworu. `null` = brak (HTTP 404). */
    @Throws(IOException::class)
    fun get(artist: String, track: String, album: String?, durationSec: Long?): Record? {
        val params = LinkedHashMap<String, String>()
        params["artist_name"] = artist
        params["track_name"] = track
        if (!album.isNullOrBlank()) params["album_name"] = album
        if (durationSec != null && durationSec > 0) params["duration"] = durationSec.toString()
        val body = request("/api/get", params) ?: return null
        return parseRecord(JSONObject(body))
    }

    @Throws(IOException::class)
    fun search(track: String, artist: String?): List<Record> {
        val params = LinkedHashMap<String, String>()
        params["track_name"] = track
        if (!artist.isNullOrBlank()) params["artist_name"] = artist
        return parseArray(request("/api/search", params))
    }

    @Throws(IOException::class)
    fun searchQuery(query: String): List<Record> =
        parseArray(request("/api/search", mapOf("q" to query)))

    private fun parseArray(body: String?): List<Record> {
        if (body == null) return emptyList()
        val array = JSONArray(body)
        val out = ArrayList<Record>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out += parseRecord(obj)
        }
        return out
    }

    private fun parseRecord(o: JSONObject): Record = Record(
        id = o.optLong("id"),
        trackName = o.stringOrNull("trackName") ?: o.stringOrNull("name") ?: "",
        artistName = o.stringOrNull("artistName") ?: "",
        albumName = o.stringOrNull("albumName") ?: "",
        durationSec = o.optDouble("duration", 0.0),
        instrumental = o.optBoolean("instrumental", false),
        plainLyrics = o.stringOrNull("plainLyrics"),
        syncedLyrics = o.stringOrNull("syncedLyrics")
    )

    /** org.json zwraca dla null tekst "null" — tu traktujemy to jako brak wartości. */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    @Throws(IOException::class)
    private fun request(path: String, params: Map<String, String>): String? {
        val query = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val connection = URL("$BASE_URL$path?$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE_URL = "https://lrclib.net"
    }
}
