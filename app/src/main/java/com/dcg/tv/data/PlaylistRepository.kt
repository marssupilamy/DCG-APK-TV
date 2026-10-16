package com.dcg.tv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** [stale] indica que a cópia salva é antiga e vale baixar de novo em segundo plano. */
    class LoadResult(val library: Library, val warning: String?, val stale: Boolean)

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

    fun cacheAgeMs(ctx: Context) = System.currentTimeMillis() - prefs(ctx).getLong(KEY_TIME, 0)

    private fun hasCache(ctx: Context, source: Source) = when (source) {
        is Source.M3u -> m3uFile(ctx).exists()
        is Source.Xtream -> XTREAM_FILES.all { xtFile(ctx, it).exists() } && prefs(ctx).contains(KEY_ACTIVE)
    }

    /**
     * Baixa o conteúdo quando não há cópia salva ou quando [force] é verdadeiro.
     * Com cópia salva, abre na hora e avisa em [LoadResult.stale] se ela tem mais de 12 horas.
     * [progress] recebe frases curtas sobre a etapa atual.
     */
    suspend fun load(ctx: Context, force: Boolean, progress: (String) -> Unit = {}): LoadResult =
        withContext(Dispatchers.IO) {
            val source = getSource(ctx) ?: throw IOException("Nenhuma lista configurada")
            val cached = hasCache(ctx, source)
            var warning: String? = null
            var downloaded = false

            if (force || !cached) {
                try {
                    downloadFiles(ctx, source, progress)
                    downloaded = true
                } catch (e: Exception) {
                    if (!cached) throw e
                    warning = "Não foi possível atualizar (${Net.describe(e)}). Exibindo a última versão salva."
                }
            }

            progress("Organizando o conteúdo…")
            val lib = when (source) {
                is Source.M3u -> m3uFile(ctx).bufferedReader().use { M3uParser.parse(it) }
                is Source.Xtream -> readXtream(ctx, source.account)
            }
            library = lib
            LoadResult(lib, warning, stale = !downloaded && cacheAgeMs(ctx) > MAX_AGE_MS)
        }

    /** Baixa só os arquivos, sem trocar o que está na tela. A novidade aparece no próximo carregamento. */
    suspend fun refreshFiles(ctx: Context) = withContext(Dispatchers.IO) {
        val source = getSource(ctx) ?: return@withContext
        downloadFiles(ctx, source) {}
    }

    private val downloadLock = Mutex()

    private suspend fun downloadFiles(ctx: Context, source: Source, progress: (String) -> Unit) = downloadLock.withLock {
        when (source) {
            is Source.M3u -> {
                progress("Baixando a lista…")
                Net.download(source.url, m3uFile(ctx), Net.PLAYER_USER_AGENT)
            }
            is Source.Xtream -> downloadXtream(ctx, source.account, progress)
        }
        prefs(ctx).edit().putLong(KEY_TIME, System.currentTimeMillis()).apply()
        episodeCache.clear()
    }

    private suspend fun downloadXtream(ctx: Context, account: XtreamAccount, progress: (String) -> Unit) {
        progress("Conectando ao servidor…")
        val login = Xtream.login(account, prefs(ctx).getString(KEY_ACTIVE, null))
        val steps = listOf(
            "live_cats" to "get_live_categories",
            "live" to "get_live_streams",
            "vod_cats" to "get_vod_categories",
            "vod" to "get_vod_streams",
            "series_cats" to "get_series_categories",
            "series" to "get_series",
        )
        progress("Baixando canais, filmes e séries…")
        // Os seis pedidos vão ao mesmo tempo.
        val tmp = coroutineScope {
            steps.map { (name, action) ->
                async(Dispatchers.IO) {
                    val file = File(ctx.filesDir, "xt_$name.new")
                    Net.download(account.apiUrl(login.server, action), file)
                    file
                }
            }.awaitAll()
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

    private suspend fun readXtream(ctx: Context, account: XtreamAccount): Library = coroutineScope {
        val p = prefs(ctx)
        val server = p.getString(KEY_ACTIVE, null) ?: account.servers.first()
        val liveExt = p.getString(KEY_LIVE_EXT, null) ?: "ts"
        fun open(name: String) = xtFile(ctx, name).bufferedReader()

        // As três partes são lidas ao mesmo tempo.
        val live = async {
            open("live_cats").use { cats ->
                open("live").use { Xtream.readStreams(cats, it, Kind.LIVE, { id, ext -> account.liveUrl(server, id, ext) }, liveExt) }
            }
        }
        val movies = async {
            open("vod_cats").use { cats ->
                open("vod").use { Xtream.readStreams(cats, it, Kind.MOVIE, { id, ext -> account.movieUrl(server, id, ext) }, "mp4") }
            }
        }
        val series = async {
            open("series_cats").use { cats ->
                open("series").use { Xtream.readSeries(cats, it, firstId = 0) }
            }
        }
        Library(live.await(), movies.await(), series.await())
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
