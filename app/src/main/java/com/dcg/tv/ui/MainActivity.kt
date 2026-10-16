package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dcg.tv.R
import com.dcg.tv.data.Entry
import com.dcg.tv.data.Epg
import com.dcg.tv.data.Kind
import com.dcg.tv.data.Library
import com.dcg.tv.data.M3uParser
import com.dcg.tv.data.Net
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Series
import com.dcg.tv.data.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: Map<Kind, TextView>
    private lateinit var status: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var gridTitle: TextView
    private lateinit var empty: TextView
    private lateinit var epg: TextView
    private lateinit var categories: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var gridLayout: GridLayoutManager

    private val categoryAdapter = TextListAdapter(R.layout.item_text, ::onCategoryClick, ::onCategoryFocus)
    private val gridAdapter = GridAdapter(::onItemClick, ::onItemLongClick, ::onItemFocus)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCategory: Runnable? = null

    private var section = Kind.LIVE
    private var categoryIndex = NONE
    private var entries: List<Entry> = emptyList()
    private var series: List<Series> = emptyList()
    private var shownKeys: List<String> = emptyList()

    /** Tipo do que está na grade. Na busca geral pode ser diferente da aba. */
    private var gridKind = Kind.LIVE

    /** Resultados da busca geral. Enquanto existem, a coluna mostra TV, Filmes e Séries. */
    private class Results(val query: String, val live: List<Entry>, val movies: List<Entry>, val series: List<Series>)

    private var results: Results? = null
    private var epgJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlaylistRepository.getSource(this) == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        tabs = mapOf(
            Kind.LIVE to findViewById(R.id.tabLive),
            Kind.MOVIE to findViewById(R.id.tabMovies),
            Kind.SERIES to findViewById(R.id.tabSeries),
        )
        status = findViewById(R.id.status)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        gridTitle = findViewById(R.id.gridTitle)
        empty = findViewById(R.id.empty)
        epg = findViewById(R.id.epg)
        categories = findViewById(R.id.categories)
        grid = findViewById(R.id.grid)

        tabs.forEach { (kind, view) -> view.setOnClickListener { showSection(kind) } }
        findViewById<View>(R.id.tabGames).setOnClickListener {
            if (PlaylistRepository.library != null) startActivity(Intent(this, GamesActivity::class.java))
        }
        findViewById<View>(R.id.btnSearch).setOnClickListener { askSearch() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        categories.layoutManager = LinearLayoutManager(this)
        categories.adapter = categoryAdapter
        categories.itemAnimator = null

        gridLayout = GridLayoutManager(this, 3)
        grid.layoutManager = gridLayout
        grid.adapter = gridAdapter
        grid.itemAnimator = null

        section = savedInstanceState?.getString(KEY_SECTION)?.let { Kind.valueOf(it) } ?: Kind.LIVE

        val lib = PlaylistRepository.library
        if (lib != null) render(lib) else load(force = false)
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar do player, os vistos recentemente podem ter mudado.
        if (results == null && (categoryIndex == FAVORITES || categoryIndex == RECENT)) {
            val keys = virtualKeys(categoryIndex)
            if (keys != shownKeys) showCategory(categoryIndex)
        }
        if (PlaylistRepository.library != null && results == null) updateCategoryNames()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section.name)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- carregamento ----

    private fun load(force: Boolean) {
        loadingText.text = if (force) "Atualizando…" else "Carregando…"
        loading.isVisible = true
        lifecycleScope.launch {
            try {
                val result = PlaylistRepository.load(this@MainActivity, force) { message ->
                    runOnUiThread { loadingText.text = message }
                }
                setStatus(result.warning)
                render(result.library)
                if (result.stale) refreshInBackground()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoadError(e)
            } finally {
                loading.isVisible = false
            }
        }
    }

    /** Baixa a versão nova sem tirar o que está na tela. */
    private fun refreshInBackground() {
        lifecycleScope.launch {
            try {
                PlaylistRepository.refreshFiles(this@MainActivity)
                setStatus("Há uma versão nova da lista. Aperte Atualizar para ver.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Continua com a cópia salva. Na próxima abertura tenta de novo.
            }
        }
    }

    /** Se a lista foi baixada há pouco, só reorganiza. Senão, baixa de novo. */
    private fun refresh() {
        load(force = PlaylistRepository.cacheAgeMs(this) > RECENT_DOWNLOAD_MS)
    }

    private fun setStatus(text: String?) {
        status.text = text.orEmpty()
        status.isVisible = text != null
    }

    private fun showLoadError(e: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Não foi possível carregar a lista")
            .setMessage("${Net.describe(e)}\n\nConfira a internet, os servidores, o usuário e a senha em Configurar.")
            .setPositiveButton("Tentar de novo") { _, _ -> load(force = true) }
            .setNegativeButton("Configurar") { _, _ -> startActivity(Intent(this, SetupActivity::class.java)) }
            .setCancelable(false)
            .show()
    }

    private fun render(lib: Library) {
        // As quantidades ficam nas categorias da coluna da esquerda, não nas abas, para a barra caber na tela.
        if (lib.isEmpty) setStatus("A lista não tem itens. Confira os dados em Configurar.")
        showSection(section)
        tabs[section]?.requestFocus()
    }

    // ---- seções e categorias ----

    /** As duas primeiras linhas da coluna são Favoritos e Vistos recentemente. */
    private fun categoryNames(lib: Library): List<String> {
        val real = when (section) {
            Kind.LIVE -> lib.live.map { "${it.name} (${it.items.size})" }
            Kind.MOVIE -> lib.movies.map { "${it.name} (${it.items.size})" }
            Kind.SERIES -> lib.series.map { "${it.name} (${it.items.size})" }
        }
        return listOf(
            "Favoritos (${virtualKeys(FAVORITES).size})",
            "Vistos recentemente (${virtualKeys(RECENT).size})",
        ) + real
    }

    private fun updateCategoryNames() {
        val lib = PlaylistRepository.library ?: return
        categoryAdapter.refresh(categoryNames(lib))
    }

    private fun showSection(kind: Kind) {
        val lib = PlaylistRepository.library ?: return
        section = kind
        results = null
        tabs.forEach { (k, v) -> v.isSelected = k == kind }
        categoryAdapter.submit(categoryNames(lib))
        categories.scrollToPosition(0)
        categoryIndex = NONE
        showCategory(if (virtualKeys(RECENT).isNotEmpty()) RECENT else FIRST_REAL)
    }

    /** Chaves dos favoritos ou do histórico que pertencem à seção aberta e existem na lista atual. */
    private fun virtualKeys(index: Int): List<String> {
        val lib = PlaylistRepository.library ?: return emptyList()
        val keys = if (index == FAVORITES) UserData.favoriteKeys() else UserData.historyKeys()
        return keys.filter { key ->
            when (section) {
                Kind.SERIES -> key in lib.seriesByKey
                else -> lib.entriesByKey[key]?.kind == section
            }
        }
    }

    private fun showCategory(index: Int) {
        val lib = PlaylistRepository.library ?: return
        categoryIndex = index
        categoryAdapter.select(index)
        results?.let { r ->
            val title = "Busca: \"${r.query}\""
            when (index) {
                0 -> showEntries(Kind.LIVE, "$title em TV ao Vivo", r.live)
                1 -> showEntries(Kind.MOVIE, "$title em Filmes", r.movies)
                else -> showSeries("$title em Séries", r.series)
            }
            return
        }
        if (index == FAVORITES || index == RECENT) {
            val title = if (index == FAVORITES) "Favoritos" else "Vistos recentemente"
            val keys = virtualKeys(index)
            shownKeys = keys
            if (section == Kind.SERIES) {
                showSeries(title, keys.mapNotNull { lib.seriesByKey[it] })
            } else {
                showEntries(section, title, keys.mapNotNull { lib.entriesByKey[it] })
            }
            if (keys.isEmpty()) {
                empty.text = if (index == FAVORITES) {
                    "Nenhum favorito ainda.\nSegure o OK sobre um item para favoritar."
                } else {
                    "Nada assistido nesta seção ainda."
                }
            }
            return
        }
        shownKeys = emptyList()
        val real = index - FIRST_REAL
        when (section) {
            Kind.LIVE -> lib.live.getOrNull(real).let { showEntries(Kind.LIVE, it?.name, it?.items.orEmpty()) }
            Kind.MOVIE -> lib.movies.getOrNull(real).let { showEntries(Kind.MOVIE, it?.name, it?.items.orEmpty()) }
            Kind.SERIES -> lib.series.getOrNull(real).let { showSeries(it?.name, it?.items.orEmpty()) }
        }
    }

    private fun showEntries(kind: Kind, title: String?, list: List<Entry>) {
        entries = list
        series = emptyList()
        gridKind = kind
        val poster = kind == Kind.MOVIE
        gridLayout.spanCount = if (poster) POSTER_COLUMNS else CHANNEL_COLUMNS
        gridAdapter.submit(list.map { GridItem(it.name, it.logo) }, poster)
        afterGridChange(title, list.size)
    }

    private fun showSeries(title: String?, list: List<Series>) {
        series = list
        entries = emptyList()
        gridKind = Kind.SERIES
        gridLayout.spanCount = POSTER_COLUMNS
        gridAdapter.submit(list.map { GridItem(it.name, it.logo) }, poster = true)
        afterGridChange(title, list.size)
    }

    private fun afterGridChange(title: String?, count: Int) {
        gridTitle.text = if (title == null) "" else "$title ($count)"
        empty.text = if (title == null) "Nenhum conteúdo nesta seção." else "Nenhum item encontrado."
        empty.isVisible = count == 0
        epgJob?.cancel()
        epg.isVisible = false
        grid.scrollToPosition(0)
    }

    private fun focusFirstGridItem() {
        grid.post { gridLayout.findViewByPosition(0)?.requestFocus() }
    }

    /** Ao passar pela categoria com o controle, a grade muda depois de uma pequena pausa. */
    private fun onCategoryFocus(index: Int) {
        if (index == categoryIndex) return
        pendingCategory?.let(handler::removeCallbacks)
        pendingCategory = Runnable { showCategory(index) }.also { handler.postDelayed(it, 250) }
    }

    private fun onCategoryClick(index: Int) {
        pendingCategory?.let(handler::removeCallbacks)
        if (index != categoryIndex) showCategory(index)
        focusFirstGridItem()
    }

    // ---- itens ----

    private fun onItemClick(position: Int) {
        when (gridKind) {
            Kind.LIVE -> {
                PlayerQueue.set(entries, position, live = true)
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            Kind.MOVIE -> startActivity(
                Intent(this, MovieActivity::class.java)
                    .putExtra(MovieActivity.EXTRA_KEY, entries[position].key)
            )
            Kind.SERIES -> startActivity(
                Intent(this, SeriesActivity::class.java)
                    .putExtra(SeriesActivity.EXTRA_ID, series[position].id)
            )
        }
    }

    private fun onItemLongClick(position: Int) {
        val (key, name) = when (gridKind) {
            Kind.SERIES -> series.getOrNull(position)?.let { it.key to it.name }
            else -> entries.getOrNull(position)?.let { it.key to it.name }
        } ?: return
        val added = UserData.toggleFavorite(key)
        val msg = if (added) "\"$name\" adicionado aos favoritos" else "\"$name\" removido dos favoritos"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        if (results != null) return
        updateCategoryNames()
        if (categoryIndex == FAVORITES) showCategory(FAVORITES)
    }

    /** Na TV ao vivo, mostra o que passa agora no canal em foco. */
    private fun onItemFocus(position: Int) {
        epgJob?.cancel()
        if (gridKind != Kind.LIVE || !PlaylistRepository.hasXtream(this)) return
        val channel = entries.getOrNull(position) ?: return
        epgJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(400)
            val text = try {
                Epg.describeNowNext(PlaylistRepository.programs(this@MainActivity, channel), System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            epg.text = "${channel.name}\n${text ?: "Sem programação disponível."}"
            epg.isVisible = true
        }
    }

    // ---- busca ----

    private fun askSearch() {
        if (PlaylistRepository.library == null) return
        val input = EditText(this).apply {
            hint = "Digite parte do nome"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscar em TV, filmes e séries")
            .setView(box)
            .setPositiveButton("Buscar") { _, _ -> search(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                search(input.text.toString())
                true
            } else {
                false
            }
        }
        dialog.show()
        input.requestFocus()
    }

    /** Procura nas três seções. A coluna da esquerda passa a mostrar os resultados de cada uma. */
    private fun search(text: String) {
        val lib = PlaylistRepository.library ?: return
        val shown = text.trim()
        val query = M3uParser.normalize(shown)
        if (query.isEmpty()) return
        pendingCategory?.let(handler::removeCallbacks)
        shownKeys = emptyList()
        val r = Results(
            shown,
            lib.live.flatMap { it.items }.filterByName(query) { it.name },
            lib.movies.flatMap { it.items }.filterByName(query) { it.name },
            lib.series.flatMap { it.items }.filterByName(query) { it.name },
        )
        results = r
        tabs.values.forEach { it.isSelected = false }
        categoryAdapter.submit(
            listOf("TV ao Vivo (${r.live.size})", "Filmes (${r.movies.size})", "Séries (${r.series.size})")
        )
        categoryIndex = NONE
        // Abre o primeiro grupo que tiver resultado.
        val first = listOf(r.live, r.movies, r.series).indexOfFirst { it.isNotEmpty() }.coerceAtLeast(0)
        showCategory(first)
        focusFirstGridItem()
    }

    private fun <T> List<T>.filterByName(query: String, name: (T) -> String): List<T> =
        asSequence().filter { M3uParser.normalize(name(it)).contains(query) }.take(MAX_RESULTS).toList()

    companion object {
        private const val KEY_SECTION = "section"
        private const val CHANNEL_COLUMNS = 3
        private const val POSTER_COLUMNS = 6
        private const val MAX_RESULTS = 500
        private const val RECENT_DOWNLOAD_MS = 5 * 60 * 1000L

        private const val NONE = -1
        private const val FAVORITES = 0
        private const val RECENT = 1
        private const val FIRST_REAL = 2
    }
}
