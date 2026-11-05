package com.fado.karaoke.lyrics

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Trwały cache tekstów w plikach JSON (bez Room/kapt).
 * Klucz: wykonawca + tytuł + czas trwania. Działa offline przy kolejnych odtworzeniach.
 */
class LyricsCache(private val dir: File) {

    enum class Status { FOUND, NOT_FOUND, INSTRUMENTAL }

    data class Entry(
        val status: Status,
        val synced: String?,
        val plain: String?,
        val source: String,
        val savedAtMs: Long
    )

    fun read(key: String): Entry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            Entry(
                status = Status.valueOf(o.getString("status")),
                synced = if (o.isNull("synced")) null else o.optString("synced"),
                plain = if (o.isNull("plain")) null else o.optString("plain"),
                source = o.optString("source", "LRCLIB"),
                savedAtMs = o.optLong("savedAt", 0L)
            )
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    fun write(key: String, entry: Entry) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val o = JSONObject()
            o.put("status", entry.status.name)
            o.put("synced", entry.synced ?: JSONObject.NULL)
            o.put("plain", entry.plain ?: JSONObject.NULL)
            o.put("source", entry.source)
            o.put("savedAt", entry.savedAtMs)
            o.put("key", key)
            val target = fileFor(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            trim()
        } catch (_: Exception) {
            // Cache jest opcjonalny — błąd zapisu nie może zatrzymać aplikacji.
        }
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private fun fileFor(key: String): File = File(dir, sha1(key) + ".json")

    private fun sha1(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_FILES = 600
        private const val HEX = "0123456789abcdef"
    }
}
