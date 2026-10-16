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
    private const val KEY_AGENT = "xt_agent"
    private const val KEY_TIME = "playlist_time"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    private val XTREAM_FILES = listOf(
        "live_cats", "live", "vod_cats", "vod", "series_cats", "series",
    )

    @Volatile
    var library: Library? = null
        private set


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

    private fun clearCaches() {
        seriesCache.clear()
        movieCache.clear()
        epgCache.clear()
        agendaCache = null
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
        clearCaches()
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
        clearCaches()
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
        Net.apiUserAgent = login.userAgent
        prefs(ctx).edit()
            .putString(KEY_ACTIVE, login.server)
            .putString(KEY_LIVE_EXT, login.liveExt)
            .putString(KEY_AGENT, login.userAgent)
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

    /** Conta Xtream e servidor em uso, ou nulo quando a fonte é um link M3U. */
    private fun session(ctx: Context): Pair<XtreamAccount, String>? {
        val account = (getSource(ctx) as? Source.Xtream)?.account ?: return null
        prefs(ctx).getString(KEY_AGENT, null)?.let { Net.apiUserAgent = it }
        return account to (prefs(ctx).getString(KEY_ACTIVE, null) ?: account.servers.first())
    }

    /** Servidores da conta Xtream, com o que está em uso primeiro. Vazio para listas M3U. */
    fun servers(ctx: Context): List<String> {
        val (account, active) = session(ctx) ?: return emptyList()
        return listOf(active) + account.servers.filter { it != active }
    }

    fun setActiveServer(ctx: Context, server: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, server).apply()
    }

    /** Troca o servidor de um link de canal, filme ou episódio, mantendo o resto do endereço. */
    fun withServer(ctx: Context, url: String, server: String): String {
        val account = (getSource(ctx) as? Source.Xtream)?.account ?: return url
        val from = account.servers.firstOrNull { url.startsWith("$it/") } ?: return url
        return server + url.substring(from.length)
    }

    /**
     * Executa [block] no servidor em uso. Se ele falhar, tenta os outros servidores da conta.
     * Não troca o servidor principal: um servidor pode responder a consultas e não transmitir
     * os canais da conta. Quem troca o principal é o login e o player, quando o vídeo abre.
     */
    private suspend fun <T> onAnyServer(ctx: Context, block: suspend (XtreamAccount, String) -> T): T {
        val (account, _) = session(ctx) ?: throw IOException("Conta não configurada")
        var last: Exception? = null
        for (server in servers(ctx)) {
            try {
                return block(account, server)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Nenhum servidor respondeu")
    }

    /** Resultado de cada servidor, para a tela Configurar. */
    suspend fun testServers(account: XtreamAccount): List<String> = withContext(Dispatchers.IO) {
        Xtream.testServers(account)
    }

    fun hasXtream(ctx: Context) = getSource(ctx) is Source.Xtream

    class SeriesInfo(val details: Details, val episodes: List<Entry>)

    private val seriesCache = ConcurrentHashMap<String, SeriesInfo>()
    private val movieCache = ConcurrentHashMap<String, Details>()
    private val epgCache = ConcurrentHashMap<String, Pair<Long, List<Program>>>()

    /** Episódios e sinopse da série. Nas contas Xtream vêm do servidor na primeira vez que a série é aberta. */
    suspend fun seriesInfo(ctx: Context, series: Series): SeriesInfo {
        val xtreamId = series.xtreamId ?: return SeriesInfo(Details(poster = series.logo), series.episodes)
        seriesCache[xtreamId]?.let { return it }
        return withContext(Dispatchers.IO) {
            onAnyServer(ctx) { account, server ->
                val body = Net.fetchText(account.apiUrl(server, "get_series_info", "&series_id=$xtreamId"))
                val episodes = Xtream.readEpisodes(body.reader(), series) { id, ext -> account.episodeUrl(server, id, ext) }
                SeriesInfo(Details.read(body.reader()), episodes)
            }.also { seriesCache[xtreamId] = it }
        }
    }

    /** Sinopse e ficha do filme. Listas M3U não têm esses dados. */
    suspend fun movieDetails(ctx: Context, movie: Entry): Details {
        val id = movie.xtreamStreamId ?: return Details(poster = movie.logo)
        movieCache[id]?.let { return it }
        if (session(ctx) == null) return Details(poster = movie.logo)
        return withContext(Dispatchers.IO) {
            onAnyServer(ctx) { account, server ->
                Details.read(Net.fetchText(account.apiUrl(server, "get_vod_info", "&vod_id=$id")).reader())
            }.also { movieCache[id] = it }
        }
    }

    /** Próximos programas do canal. Guarda por 15 minutos. */
    suspend fun programs(ctx: Context, channel: Entry, limit: Int = 4): List<Program> {
        val id = channel.xtreamStreamId ?: return emptyList()
        val cacheKey = "$id/$limit"
        epgCache[cacheKey]?.let { (time, list) ->
            if (System.currentTimeMillis() - time < EPG_CACHE_MS) return list
        }
        // Só no servidor em uso: a programação é consultada muitas vezes e não vale multiplicar pedidos.
        val (account, server) = session(ctx) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            Epg.readListings(Net.fetchText(account.apiUrl(server, "get_short_epg", "&stream_id=$id&limit=$limit")).reader())
                .also { epgCache[cacheKey] = System.currentTimeMillis() to it }
        }
    }

    private var agendaCache: Pair<Long, Map<Sport, List<Game>>>? = null

    /** Atualizar a agenda pede a programação de vários canais. Há um intervalo mínimo entre atualizações. */
    fun agendaAgeMs(): Long = agendaCache?.let { System.currentTimeMillis() - it.first } ?: Long.MAX_VALUE

    /**
     * Agenda de jogos: lê a programação dos canais de esporte, poucos de cada vez e com pausa
     * entre os pedidos, para o fornecedor não confundir com abuso e bloquear a conta.
     * Para depois de várias falhas seguidas. [progress] recebe quantos canais já foram lidos e o total.
     */
    suspend fun agenda(ctx: Context, force: Boolean, progress: (Int, Int) -> Unit): Map<Sport, List<Game>> {
        if (!force || agendaAgeMs() < AGENDA_MIN_REFRESH_MS) {
            agendaCache?.let { (time, agenda) -> if (System.currentTimeMillis() - time < AGENDA_CACHE_MS) return agenda }
        }
        val lib = library ?: return emptyMap()
        val all = lib.live.asSequence().flatMap { it.items }.filter(Sports::isSportsChannel)
        // Canais das categorias de esporte primeiro; depois os que só têm o nome parecido.
        val (inSportsGroup, others) = all.toList().partition { Sports.isSportsGroup(it.group) }
        val channels = (inSportsGroup + others)
            .distinctBy(Sports::channelKey)
            .take(MAX_AGENDA_CHANNELS)
        val listings = java.util.Collections.synchronizedMap(LinkedHashMap<Entry, List<Program>>())
        val done = java.util.concurrent.atomic.AtomicInteger()
        val failuresInRow = java.util.concurrent.atomic.AtomicInteger()
        progress(0, channels.size)
        withContext(Dispatchers.IO) {
            for (chunk in channels.chunked(AGENDA_PARALLEL)) {
                if (failuresInRow.get() >= AGENDA_MAX_FAILURES) break
                chunk.map { channel ->
                    async {
                        try {
                            listings[channel] = programs(ctx, channel, limit = AGENDA_PROGRAMS)
                            failuresInRow.set(0)
                        } catch (e: Exception) {
                            failuresInRow.incrementAndGet()
                        }
                        progress(done.incrementAndGet(), channels.size)
                    }
                }.awaitAll()
                kotlinx.coroutines.delay(AGENDA_PAUSE_MS)
            }
        }
        if (listings.isEmpty() && failuresInRow.get() >= AGENDA_MAX_FAILURES) {
            throw IOException("o servidor parou de responder à programação. Espere alguns minutos e tente de novo")
        }
        val agenda = Sports.buildAgenda(LinkedHashMap(listings), System.currentTimeMillis())
        agendaCache = System.currentTimeMillis() to agenda
        return agenda
    }

    private const val EPG_CACHE_MS = 15 * 60 * 1000L
    private const val AGENDA_CACHE_MS = 30 * 60 * 1000L
    private const val MAX_AGENDA_CHANNELS = 60
    private const val AGENDA_PARALLEL = 2
    private const val AGENDA_PAUSE_MS = 250L
    private const val AGENDA_MAX_FAILURES = 6
    private const val AGENDA_PROGRAMS = 20
    private const val AGENDA_MIN_REFRESH_MS = 10 * 60 * 1000L
}
