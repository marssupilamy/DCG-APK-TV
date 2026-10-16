package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dcg.tv.R
import com.dcg.tv.data.Details
import com.dcg.tv.data.Entry
import com.dcg.tv.data.Net
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Series
import com.dcg.tv.data.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private lateinit var series: Series
    private lateinit var info: TextView
    private lateinit var favorite: TextView
    private lateinit var episodesView: RecyclerView
    private val seasonAdapter = TextListAdapter(R.layout.item_chip, ::selectSeason, ::selectSeason)
    private val episodeAdapter = TextListAdapter(R.layout.item_text, ::play)
    private var allEpisodes: List<Entry> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var episodes: List<Entry> = emptyList()
    private var currentSeason = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = PlaylistRepository.library?.seriesById?.get(intent.getIntExtra(EXTRA_ID, -1))
        if (found == null) {
            finish()
            return
        }
        series = found
        setContentView(R.layout.activity_series)

        findViewById<ImageView>(R.id.poster).load(series.logo) {
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_placeholder)
            fallback(R.drawable.ic_placeholder)
        }
        findViewById<TextView>(R.id.title).text = series.name
        info = findViewById(R.id.info)
        info.text = "Carregando episódios…"
        favorite = findViewById(R.id.favorite)
        updateFavorite()
        favorite.setOnClickListener {
            val added = UserData.toggleFavorite(series.key)
            Toast.makeText(this, if (added) "Adicionada aos favoritos" else "Removida dos favoritos", Toast.LENGTH_SHORT).show()
            updateFavorite()
        }

        findViewById<RecyclerView>(R.id.seasons).apply {
            layoutManager = LinearLayoutManager(this@SeriesActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seasonAdapter
            itemAnimator = null
        }
        episodesView = findViewById<RecyclerView>(R.id.episodes).apply {
            layoutManager = LinearLayoutManager(this@SeriesActivity)
            adapter = episodeAdapter
            itemAnimator = null
        }

        lifecycleScope.launch {
            try {
                val info = PlaylistRepository.seriesInfo(this@SeriesActivity, series)
                showDetails(info.details)
                show(info.episodes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                info.text = "Não foi possível carregar os episódios (${Net.describe(e)}).\n${series.group}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ao voltar do player, atualiza o "parou em".
        if (currentSeason >= 0) episodeAdapter.refresh(episodes.map(::label))
    }

    private fun showDetails(d: Details) {
        val plot = findViewById<TextView>(R.id.plot)
        val text = listOfNotNull(d.summary.ifEmpty { null }, d.plot).joinToString("\n")
        plot.text = text
        plot.isVisible = text.isNotEmpty()
        if (series.logo == null && d.poster != null) findViewById<ImageView>(R.id.poster).load(d.poster)
    }

    private fun updateFavorite() {
        favorite.text = if (UserData.isFavorite(series.key)) "Remover dos favoritos" else "Favoritar"
    }

    private fun label(e: Entry): String {
        val base = if (e.episode > 0) "Episódio ${e.episode}  ·  ${e.name}" else e.name
        val p = UserData.progress(e.key) ?: return base
        return when {
            p.finished -> "$base  ·  visto"
            p.position > 0 -> "$base  ·  parou em ${UserData.formatTime(p.position)}"
            else -> base
        }
    }

    private fun show(list: List<Entry>) {
        allEpisodes = list
        seasons = list.map { it.season }.distinct().sorted()
        info.text = if (list.isEmpty()) {
            "Nenhum episódio disponível.\n${series.group}"
        } else {
            "${seasons.size} temporada(s) · ${list.size} episódio(s)\n${series.group}"
        }
        seasonAdapter.submit(seasons.map { "Temporada $it" })
        // Abre na temporada e no episódio vistos por último.
        val last = UserData.lastEpisode(series.key)?.let { key -> list.firstOrNull { it.key == key } }
        selectSeason(last?.let { seasons.indexOf(it.season) }?.takeIf { it >= 0 } ?: 0)
        val target = last?.let { episodes.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        episodesView.scrollToPosition(target)
        episodesView.post { episodesView.layoutManager?.findViewByPosition(target)?.requestFocus() }
    }

    private fun selectSeason(index: Int) {
        if (index == currentSeason || index !in seasons.indices) return
        currentSeason = index
        seasonAdapter.select(index)
        episodes = allEpisodes.filter { it.season == seasons[index] }
        episodeAdapter.submit(episodes.map(::label))
        episodesView.scrollToPosition(0)
    }

    private fun play(index: Int) {
        PlayerQueue.set(episodes, index, live = false, seriesKey = series.key)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    companion object {
        const val EXTRA_ID = "series_id"
    }
}
