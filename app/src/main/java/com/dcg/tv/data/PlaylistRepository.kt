package com.dcg.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object PlaylistRepository {

    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    private const val PREFS = "dcg_prefs"
    private const val KEY_URL = "playlist_url"
    private const val KEY_TIME = "playlist_time"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    @Volatile
    var library: Library? = null
        private set

    class LoadResult(val library: Library, val warning: String?)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "playlist.m3u")

    fun getUrl(ctx: Context): String? = prefs(ctx).getString(KEY_URL, null)

    fun setUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_URL, url).remove(KEY_TIME).apply()
        cacheFile(ctx).delete()
        library = null
    }

    /** Baixa a lista quando o arquivo salvo tem mais de 12 horas ou quando [force] é verdadeiro. */
    suspend fun load(ctx: Context, force: Boolean): LoadResult = withContext(Dispatchers.IO) {
        val url = getUrl(ctx) ?: throw IOException("Nenhuma lista configurada")
        val file = cacheFile(ctx)
        val age = System.currentTimeMillis() - prefs(ctx).getLong(KEY_TIME, 0)
        var warning: String? = null

        if (force || !file.exists() || age > MAX_AGE_MS) {
            try {
                download(url, file)
                prefs(ctx).edit().putLong(KEY_TIME, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                if (!file.exists()) throw e
                warning = "Não foi possível atualizar a lista (${e.message}). Exibindo a última versão salva."
            }
        }

        val lib = file.bufferedReader().use { M3uParser.parse(it) }
        library = lib
        LoadResult(lib, warning)
    }

    private fun download(url: String, dest: File) {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("redirecionamento sem destino")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("o servidor respondeu com código $code")

                val tmp = File(dest.parentFile, dest.name + ".tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("redirecionamentos demais")
    }
}
