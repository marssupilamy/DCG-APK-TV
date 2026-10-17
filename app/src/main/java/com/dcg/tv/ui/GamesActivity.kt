package com.dcg.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dcg.tv.R
import com.dcg.tv.data.Game
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository
import com.dcg.tv.data.Sport
import com.dcg.tv.data.Sports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Agenda de jogos montada com o guia de programação de todos os canais, separada por esporte. */
class GamesActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var gamesView: RecyclerView
    private val sportAdapter = TextListAdapter(R.layout.item_text, ::onSportClick, ::selectSport)
    private val gameAdapter = GameAdapter(::play)
    private var agenda: Map<Sport, List<Game>> = emptyMap()
    private var sports: List<Sport> = emptyList()
    private var current = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlaylistRepository.library == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_games)
        status = findViewById(R.id.status)
        message = findViewById(R.id.message)
        findViewById<RecyclerView>(R.id.sports).apply {
            layoutManager = LinearLayoutManager(this@GamesActivity)
            adapter = sportAdapter
            itemAnimator = null
        }
        gamesView = findViewById<RecyclerView>(R.id.games).apply {
            layoutManager = LinearLayoutManager(this@GamesActivity)
            adapter = gameAdapter
            itemAnimator = null
        }
        findViewById<View>(R.id.refresh).setOnClickListener {
            val minutes = PlaylistRepository.agendaAgeMs() / 60_000
            if (minutes < 10) {
                android.widget.Toast.makeText(
                    this,
                    "A agenda foi atualizada há $minutes min. Para não sobrecarregar o servidor, espere 10 min entre atualizações.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else {
                load(force = true)
            }
        }

        if (!PlaylistRepository.hasXtream(this)) {
            showMessage("A agenda de jogos usa a programação dos canais, disponível só em contas com servidor, usuário e senha.")
            return
        }
        load(force = false)
    }

    private fun showMessage(text: String?) {
        message.text = text.orEmpty()
        message.isVisible = text != null
    }

    private fun load(force: Boolean) {
        if (!PlaylistRepository.hasXtream(this)) return
        showMessage("Carregando a agenda…")
        lifecycleScope.launch {
            try {
                agenda = PlaylistRepository.agenda(this@GamesActivity, force) { text ->
                    runOnUiThread { showMessage(text) }
                }
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Não foi possível montar a agenda.\n${com.dcg.tv.data.Net.describe(e).replaceFirstChar { it.uppercase() }}.")
            }
        }
    }

    private fun render() {
        sports = agenda.keys.toList()
        if (sports.isEmpty()) {
            showMessage(
                "Nenhum jogo encontrado nas próximas 36 horas.\n\n" +
                    "Isso acontece quando o guia de programação do fornecedor não traz os jogos ou os canais não têm guia."
            )
            status.text = ""
            return
        }
        showMessage(null)
        val total = agenda.values.sumOf { it.size }
        val live = agenda.values.sumOf { list -> list.count { it.program.isLive(System.currentTimeMillis()) } }
        status.text = "$total jogos  ·  $live ao vivo agora"
        sportAdapter.submit(sports.map { "${it.label} (${agenda.getValue(it).size})" })
        current = -1
        selectSport(0)
        gamesView.post { gamesView.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    private fun selectSport(index: Int) {
        if (index == current || index !in sports.indices) return
        current = index
        sportAdapter.select(index)
        gameAdapter.submit(agenda.getValue(sports[index]))
        gamesView.scrollToPosition(0)
    }

    private fun onSportClick(index: Int) {
        selectSport(index)
        gamesView.post { gamesView.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }

    /** Abre o canal do jogo. Com cima/baixo o player alterna entre os canais que transmitem. */
    private fun play(game: Game) {
        PlayerQueue.set(game.channels, 0, live = true)
        startActivity(Intent(this, PlayerActivity::class.java))
    }
}

class GameAdapter(private val onClick: (Game) -> Unit) : RecyclerView.Adapter<GameAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.gameTime)
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
    }

    private var items: List<Game> = emptyList()

    fun submit(list: List<Game>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_game, parent, false)
        val holder = Holder(view)
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val game = items[position]
        val now = System.currentTimeMillis()
        val ctx = holder.itemView.context
        val live = game.program.isLive(now)
        holder.time.text = Sports.whenLabel(game.program, now)
        holder.time.setTextColor(ContextCompat.getColor(ctx, if (live) R.color.accent else R.color.text))
        holder.title.text = game.program.title
        val channels = game.channels.joinToString(", ") { it.name }
        holder.subtitle.text = if (live) "Começou ${com.dcg.tv.data.Epg.time(game.program.start)}  ·  $channels" else channels
        holder.image.load(game.channels.firstNotNullOfOrNull { it.logo }) {
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_placeholder)
            fallback(R.drawable.ic_placeholder)
        }
    }
}
