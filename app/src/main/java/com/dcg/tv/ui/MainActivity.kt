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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dcg.tv.R
import com.dcg.tv.data.Entry
import com.dcg.tv.data.Kind
import com.dcg.tv.data.Library
import com.dcg.tv.data.M3uParser
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tabs: Map<Kind, TextView>
    private lateinit var status: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var gridTitle: TextView
    private lateinit var empty: TextView
    private lateinit var categories: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var gridLayout: GridLayoutManager

    private val categoryAdapter = TextListAdapter(R.layout.item_text, ::onCategoryClick, ::onCategoryFocus)
    private val gridAdapter = GridAdapter(::onItemClick)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCategory: Runnable? = null

    private var section = Kind.LIVE
    private var categoryIndex = -1
    private var entries: List<Entry> = emptyList()
    private var series: List<Series> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlaylistRepository.getUrl(this) == null) {
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
        categories = findViewById(R.id.categories)
        grid = findViewById(R.id.grid)

        tabs.forEach { (kind, view) -> view.setOnClickListener { showSection(kind) } }
        findViewById<View>(R.id.btnSearch).setOnClickListener { askSearch() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener { load(force = true) }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SECTION, section.name)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun load(force: Boolean) {
        loadingText.text = if (force) "Atualizando a lista…" else "Carregando a lista…"
        loading.isVisible = true
        lifecycleScope.launch {
            try {
                val result = PlaylistRepository.load(this@MainActivity, force)
                status.text = result.warning.orEmpty()
                status.isVisible = result.warning != null
                render(result.library)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoadError(e)
            } finally {
                loading.isVisible = false
            }
        }
    }

    private fun showLoadError(e: Exception) {
        AlertDialog.Builder(this)
            .setTitle("Não foi possível carregar a lista")
            .setMessage("${e.message ?: e.javaClass.simpleName}\n\nConfira a internet e o link da lista.")
            .setPositiveButton("Tentar de novo") { _, _ -> load(force = true) }
            .setNegativeButton("Configurar") { _, _ -> startActivity(Intent(this, SetupActivity::class.java)) }
            .setCancelable(false)
            .show()
    }

    private fun render(lib: Library) {
        tabs[Kind.LIVE]?.text = "TV ao Vivo (${lib.liveCount})"
        tabs[Kind.MOVIE]?.text = "Filmes (${lib.movieCount})"
        tabs[Kind.SERIES]?.text = "Séries (${lib.seriesCount})"
        if (lib.isEmpty) {
            status.text = "A lista não tem itens. Confira o link em Configurar."
            status.isVisible = true
        }
        showSection(section)
        tabs[section]?.requestFocus()
    }

    private fun showSection(kind: Kind) {
        val lib = PlaylistRepository.library ?: return
        section = kind
        tabs.forEach { (k, v) -> v.isSelected = k == kind }
        val names = when (kind) {
            Kind.LIVE -> lib.live.map { "${it.name} (${it.items.size})" }
            Kind.MOVIE -> lib.movies.map { "${it.name} (${it.items.size})" }
            Kind.SERIES -> lib.series.map { "${it.name} (${it.items.size})" }
        }
        categoryAdapter.submit(names)
        categories.scrollToPosition(0)
        categoryIndex = -1
        showCategory(0)
    }

    private fun showCategory(index: Int) {
        val lib = PlaylistRepository.library ?: return
        categoryIndex = index
        categoryAdapter.select(index)
        when (section) {
            Kind.LIVE -> lib.live.getOrNull(index).let { showEntries(it?.name, it?.items.orEmpty()) }
            Kind.MOVIE -> lib.movies.getOrNull(index).let { showEntries(it?.name, it?.items.orEmpty()) }
            Kind.SERIES -> lib.series.getOrNull(index).let { showSeries(it?.name, it?.items.orEmpty()) }
        }
    }

    private fun showEntries(title: String?, list: List<Entry>) {
        entries = list
        series = emptyList()
        val poster = section == Kind.MOVIE
        gridLayout.spanCount = if (poster) POSTER_COLUMNS else CHANNEL_COLUMNS
        gridAdapter.submit(list.map { GridItem(it.name, it.logo) }, poster)
        afterGridChange(title, list.size)
    }

    private fun showSeries(title: String?, list: List<Series>) {
        series = list
        entries = emptyList()
        gridLayout.spanCount = POSTER_COLUMNS
        gridAdapter.submit(list.map { GridItem(it.name, it.logo) }, poster = true)
        afterGridChange(title, list.size)
    }

    private fun afterGridChange(title: String?, count: Int) {
        gridTitle.text = if (title == null) "" else "$title ($count)"
        empty.text = if (title == null) "Nenhum conteúdo nesta seção." else "Nenhum item encontrado."
        empty.isVisible = count == 0
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

    private fun onItemClick(position: Int) {
        when (section) {
            Kind.LIVE -> {
                PlayerQueue.set(entries, position, live = true)
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            Kind.MOVIE -> {
                PlayerQueue.set(listOf(entries[position]), 0, live = false)
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            Kind.SERIES -> startActivity(
                Intent(this, SeriesActivity::class.java)
                    .putExtra(SeriesActivity.EXTRA_ID, series[position].id)
            )
        }
    }

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
        val where = when (section) {
            Kind.LIVE -> "TV ao Vivo"
            Kind.MOVIE -> "Filmes"
            Kind.SERIES -> "Séries"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Buscar em $where")
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

    private fun search(text: String) {
        val lib = PlaylistRepository.library ?: return
        val query = M3uParser.normalize(text.trim())
        if (query.isEmpty()) return
        pendingCategory?.let(handler::removeCallbacks)
        categoryIndex = -1
        categoryAdapter.select(-1)
        val title = "Busca: \"${text.trim()}\""
        when (section) {
            Kind.LIVE -> showEntries(title, lib.live.flatMap { it.items }.filterByName(query) { it.name })
            Kind.MOVIE -> showEntries(title, lib.movies.flatMap { it.items }.filterByName(query) { it.name })
            Kind.SERIES -> showSeries(title, lib.series.flatMap { it.items }.filterByName(query) { it.name })
        }
        focusFirstGridItem()
    }

    private fun <T> List<T>.filterByName(query: String, name: (T) -> String): List<T> =
        asSequence().filter { M3uParser.normalize(name(it)).contains(query) }.take(MAX_RESULTS).toList()

    companion object {
        private const val KEY_SECTION = "section"
        private const val CHANNEL_COLUMNS = 3
        private const val POSTER_COLUMNS = 6
        private const val MAX_RESULTS = 500
    }
}
