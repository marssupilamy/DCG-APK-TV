package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dcg.tv.R
import com.dcg.tv.data.Entry
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Series

class SeriesActivity : AppCompatActivity() {

    private lateinit var series: Series
    private lateinit var episodesView: RecyclerView
    private val seasonAdapter = TextListAdapter(R.layout.item_chip, ::selectSeason, ::selectSeason)
    private val episodeAdapter = TextListAdapter(R.layout.item_text, ::play)
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
        seasons = series.seasons
        findViewById<TextView>(R.id.info).text =
            "${seasons.size} temporada(s) · ${series.episodes.size} episódio(s)\n${series.group}"

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

        seasonAdapter.submit(seasons.map { "Temporada $it" })
        selectSeason(0)
        episodesView.post { episodesView.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun selectSeason(index: Int) {
        if (index == currentSeason || index !in seasons.indices) return
        currentSeason = index
        seasonAdapter.select(index)
        episodes = series.episodes.filter { it.season == seasons[index] }
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
