package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil.load
import com.dcg.tv.R
import com.dcg.tv.data.Details
import com.dcg.tv.data.Entry
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.UserData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Ficha do filme: capa, ano, duração, nota, sinopse e elenco, com os botões para assistir. */
class MovieActivity : AppCompatActivity() {

    private lateinit var movie: Entry
    private lateinit var play: TextView
    private lateinit var restart: TextView
    private lateinit var favorite: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = intent.getStringExtra(EXTRA_KEY)?.let { PlaylistRepository.library?.entriesByKey?.get(it) }
        if (found == null) {
            finish()
            return
        }
        movie = found
        setContentView(R.layout.activity_movie)

        findViewById<TextView>(R.id.title).text = movie.name
        play = findViewById(R.id.play)
        restart = findViewById(R.id.restart)
        favorite = findViewById(R.id.favorite)
        showDetails(Details(poster = movie.logo))

        play.setOnClickListener { start(fromBeginning = false) }
        restart.setOnClickListener { start(fromBeginning = true) }
        favorite.setOnClickListener {
            val added = UserData.toggleFavorite(movie.key)
            Toast.makeText(this, if (added) "Adicionado aos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
            updateButtons()
        }
        play.requestFocus()

        lifecycleScope.launch {
            try {
                showDetails(PlaylistRepository.movieDetails(this@MovieActivity, movie))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sem ficha: fica só com o nome e a capa da lista.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::movie.isInitialized) updateButtons()
    }

    private fun showDetails(d: Details) {
        val poster = d.poster ?: movie.logo
        findViewById<ImageView>(R.id.poster).load(poster) {
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_placeholder)
            fallback(R.drawable.ic_placeholder)
        }
        findViewById<ImageView>(R.id.backdrop).load(d.backdrop ?: poster)
        findViewById<TextView>(R.id.summary).text = d.summary.ifEmpty { movie.group }
        findViewById<TextView>(R.id.plot).text = d.plot ?: ""
        val credits = listOfNotNull(d.director?.let { "Direção: $it" }, d.cast?.let { "Elenco: $it" })
        findViewById<TextView>(R.id.credits).text = credits.joinToString("\n")
    }

    private fun updateButtons() {
        val resume = UserData.resumePosition(movie.key)
        play.text = resume?.let { "Continuar de ${UserData.formatTime(it)}" } ?: "Assistir"
        restart.isVisible = resume != null
        favorite.text = if (UserData.isFavorite(movie.key)) "Remover dos favoritos" else "Favoritar"
    }

    private fun start(fromBeginning: Boolean) {
        if (fromBeginning) UserData.saveProgress(movie.key, 0, UserData.progress(movie.key)?.duration ?: 1)
        PlayerQueue.set(listOf(movie), 0, live = false)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    companion object {
        const val EXTRA_KEY = "movie_key"
    }
}
