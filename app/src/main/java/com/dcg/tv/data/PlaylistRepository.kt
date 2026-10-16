package com.dcg.tv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** De onde vem o conteúdo: um link M3U ou uma conta Xtream. */
sealed class Source {
    data class M3u(val url: String) : Source()
    data class Xtream(val account: XtreamAccount) : Source()
}

object PlaylistRepository {

    private const val PREFS = "dcg_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_URL = "playlist_url"
    private const val KEY_SERVERS = "xt_servers"
    private const val KEY_USER = "xt_user"
    private const val KEY_PASS = "xt_pass"
    private const val KEY_ACTIVE = "xt_active"
    private const val KEY_LIVE_EXT = "xt_live_ext"
    private const val KEY_TIME = "playlist_time"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private val XTREAM_FILES = listOf(
        "live_cats", "live", "vod_cats", "vod", "series_cats", "series",
    )

    @Volatile
    var library: Library? = null
        private set

    private val episodeCache = ConcurrentHashMap<String, List<Entry>>()

    class LoadResult(val library: Library, val warning: String?)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun m3uFile(ctx: Context) = File(ctx.filesDir, "playlist.m3u")
    private fun xtFile(ctx: Context, name: String) = File(ctx.filesDir, "xt_$name.json")

    fun getSource(ctx: Context): Source? {
        val p = prefs(ctx)
        val url = p.getString(KEY_URL, null)
        when (p.getString(KEY_MODE, null)) {
            "xtream" -> {
                val servers = p.getString(KEY_SERVERS, null)?.let(Xtream::parseServers).orEmpty()
                val user = p.getString(KEY_USER, null)
                val pass = p.getString(KEY_PASS, null)
                if (servers.isNotEmpty() && user != null && pass != null) {
                    return Source.Xtream(XtreamAccount(servers, user, pass))
                }
            }
            "m3u" -> if (url != null) return Source.M3u(url)
        }
        // Versão 1.0.1 guardava só o link. Links get.php viram conta Xtream.
        if (url == null) return null
        return asXtream(url) ?: Source.M3u(url)
    }

    /** Reconhece links do tipo http://servidor/get.php?username=...&password=... */
    fun asXtream(url: String): Source.Xtream? {
        val uri = Uri.parse(url)
        val user = uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("password")
        if (uri.path?.endsWith("get.php") != true || user.isNullOrEmpty() || pass.isNullOrEmpty()) return null
        return Source.Xtream(XtreamAccount(listOf(Xtream.normalizeServer(url)), user, pass))
    }

    fun setSource(ctx: Context, source: Source) {
        val edit = prefs(ctx).edit().clear()
        when (source) {
            is Source.M3u -> edit.putString(KEY_MODE, "m3u").putString(KEY_URL, source.url)
            is Source.Xtream -> edit.putString(KEY_MODE, "xtream")
                .putString(KEY_SERVERS, source.account.servers.joinToString("\n"))
                .putString(KEY_USER, source.account.user)
                .putString(KEY_PASS, source.account.pass)
        }
        edit.apply()
        m3uFile(ctx).delete()
        XTREAM_FILES.forEach { xtFile(ctx, it).delete() }
        episodeCache.clear()
        library = null
    }

    /**
     * Baixa o conteúdo quando a cópia salva tem mais de 12 horas ou quando [force] é verdadeiro.
     * [progress] recebe frases curtas sobre a etapa atual.
     */
    suspend fun load(ctx: Context, force: Boolean, progress: (String) -> Unit = {}): LoadResult =
        withContext(Dispatchers.IO) {
            val source = getSource(ctx) ?: throw IOException("Nenhuma lista configurada")
            val age = System.currentTimeMillis() - prefs(ctx).getLong(KEY_TIME, 0)
            val hasCache = when (source) {
                is Source.M3u -> m3uFile(ctx).exists()
                is Source.Xtream -> XTREAM_FILES.all { xtFile(ctx, it).exists() } && prefs(ctx).contains(KEY_ACTIVE)
            }
            var warning: String? = null

            if (force || !hasCache || age > MAX_AGE_MS) {
                try {
                    when (source) {
                        is Source.M3u -> {
                            progress("Baixando a lista…")
                            Net.download(source.url, m3uFile(ctx), Net.PLAYER_USER_AGENT)
                        }
                        is Source.Xtream -> downloadXtream(ctx, source.account, progress)
                    }
                    prefs(ctx).edit().putLong(KEY_TIME, System.currentTimeMillis()).apply()
                    episodeCache.clear()
                } catch (e: Exception) {
                    if (!hasCache) throw e
                    warning = "Não foi possível atualizar (${Net.describe(e)}). Exibindo a última versão salva."
                }
            }

            progress("Organizando o conteúdo…")
            val lib = when (source) {
                is Source.M3u -> m3uFile(ctx).bufferedReader().use { M3uParser.parse(it) }
                is Source.Xtream -> readXtream(ctx, source.account)
            }
            library = lib
            LoadResult(lib, warning)
        }

    private fun downloadXtream(ctx: Context, account: XtreamAccount, progress: (String) -> Unit) {
        progress("Conectando ao servidor…")
        val login = Xtream.login(account, prefs(ctx).getString(KEY_ACTIVE, null))
        val steps = listOf(
            Triple("live_cats", "get_live_categories", "Baixando categorias de TV…"),
            Triple("live", "get_live_streams", "Baixando canais…"),
            Triple("vod_cats", "get_vod_categories", "Baixando categorias de filmes…"),
            Triple("vod", "get_vod_streams", "Baixando filmes…"),
            Triple("series_cats", "get_series_categories", "Baixando categorias de séries…"),
            Triple("series", "get_series", "Baixando séries…"),
        )
        val tmp = steps.map { (name, action, message) ->
            progress(message)
            val file = File(ctx.filesDir, "xt_$name.new")
            Net.download(account.apiUrl(login.server, action), file)
            file
        }
        // Só troca os arquivos depois que todos chegaram.
        steps.zip(tmp).forEach { (step, file) ->
            val dest = xtFile(ctx, step.first)
            if (!file.renameTo(dest)) {
                file.copyTo(dest, overwrite = true)
                file.delete()
            }
        }
        prefs(ctx).edit()
            .putString(KEY_ACTIVE, login.server)
            .putString(KEY_LIVE_EXT, login.liveExt)
            .apply()
    }

    private fun readXtream(ctx: Context, account: XtreamAccount): Library {
        val p = prefs(ctx)
        val server = p.getString(KEY_ACTIVE, null) ?: account.servers.first()
        val liveExt = p.getString(KEY_LIVE_EXT, null) ?: "ts"
        fun open(name: String) = xtFile(ctx, name).bufferedReader()

        val live = open("live_cats").use { cats ->
            open("live").use { Xtream.readStreams(cats, it, Kind.LIVE, { id, ext -> account.liveUrl(server, id, ext) }, liveExt) }
        }
        val movies = open("vod_cats").use { cats ->
            open("vod").use { Xtream.readStreams(cats, it, Kind.MOVIE, { id, ext -> account.movieUrl(server, id, ext) }, "mp4") }
        }
        val series = open("series_cats").use { cats ->
            open("series").use { Xtream.readSeries(cats, it, firstId = 0) }
        }
        return Library(live, movies, series)
    }

    /** Episódios da série. Nas contas Xtream vêm do servidor na primeira vez que a série é aberta. */
    suspend fun episodes(ctx: Context, series: Series): List<Entry> {
        val xtreamId = series.xtreamId ?: return series.episodes
        episodeCache[xtreamId]?.let { return it }
        return withContext(Dispatchers.IO) {
            val account = (getSource(ctx) as? Source.Xtream)?.account
                ?: throw IOException("Conta não configurada")
            val server = prefs(ctx).getString(KEY_ACTIVE, null) ?: account.servers.first()
            val body = Net.fetchText(account.apiUrl(server, "get_series_info", "&series_id=$xtreamId"))
            val list = Xtream.readEpisodes(body.reader(), series) { id, ext -> account.episodeUrl(server, id, ext) }
            episodeCache[xtreamId] = list
            list
        }
    }
}
