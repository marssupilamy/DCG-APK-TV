package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dcg.tv.R
import com.dcg.tv.data.Entry
import com.dcg.tv.data.Net
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private lateinit var series: Series
    private lateinit var info: TextView
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
                show(PlaylistRepository.episodes(this@SeriesActivity, series))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                info.text = "Não foi possível carregar os episódios (${Net.describe(e)}).\n${series.group}"
            }
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
        selectSeason(0)
        episodesView.post { episodesView.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun selectSeason(index: Int) {
        if (index == currentSeason || index !in seasons.indices) return
        currentSeason = index
        seasonAdapter.select(index)
        episodes = allEpisodes.filter { it.season == seasons[index] }
        episodeAdapter.submit(episodes.map { if (it.episode > 0) "Episódio ${it.episode}  ·  ${it.name}" else it.name })
        episodesView.scrollToPosition(0)
    }

    private fun play(index: Int) {
        PlayerQueue.set(episodes, index, live = false)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    companion object {
        const val EXTRA_ID = "series_id"
    }
}
