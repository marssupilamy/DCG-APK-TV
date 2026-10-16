package com.dcg.tv.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dcg.tv.R
import com.dcg.tv.data.Entry
import com.dcg.tv.data.Net
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.UserData

/**
 * Controles pelo controle remoto:
 * - Filmes e episódios: OK pausa e retoma; esquerda e direita voltam e avançam
 *   (segurar acelera); cima e baixo mostram a barra de controles.
 * - Ao vivo: cima e baixo trocam de canal; OK mostra a barra de controles.
 * - Botões de mídia (play/pause, avançar, voltar) funcionam nos dois casos.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private lateinit var error: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.isVisible = false }
    private val tick = object : Runnable {
        override fun run() {
            trackProgress(save = true)
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private var items: List<Entry> = emptyList()
    private var live = false
    private var seriesKey: String? = null
    private var resumeIndex = 0
    private var resumePosition = C.TIME_UNSET

    /** Índice, posição e duração do item atual, guardados para salvar o progresso. */
    private var currentIndex = -1
    private var lastPosition = 0L
    private var lastDuration = 0L

    /** Tecla tratada aqui no ACTION_DOWN; o ACTION_UP dela também fica aqui. */
    private var consumedKey = KeyEvent.KEYCODE_UNKNOWN

    /** Cabeçalhos extras no formato "link|User-Agent=...&Referer=...". */
    private val headers = HashMap<String, Map<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = PlayerQueue.items
        live = PlayerQueue.live
        seriesKey = PlayerQueue.seriesKey
        if (items.isEmpty()) {
            finish()
            return
        }
        resumeIndex = (savedInstanceState?.getInt(KEY_INDEX) ?: PlayerQueue.start).coerceIn(items.indices)
        savedInstanceState?.getLong(KEY_POSITION, C.TIME_UNSET)?.let { resumePosition = it }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        info = findViewById(R.id.info)
        error = findViewById(R.id.error)

        playerView.controllerAutoShow = !live
        if (live) {
            playerView.setShowFastForwardButton(false)
            playerView.setShowRewindButton(false)
        }
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, player?.currentMediaItemIndex ?: resumeIndex)
        outState.putLong(KEY_POSITION, if (live) C.TIME_UNSET else player?.currentPosition ?: resumePosition)
    }

    private fun initPlayer() {
        if (player != null) return
        val http = OkHttpDataSource.Factory(Net.client).setUserAgent(Net.PLAYER_USER_AGENT)
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            headers[spec.uri.toString()]?.let { spec.withAdditionalHeaders(it) } ?: spec
        }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_BUTTON_MS)
            .build()
        // No ao vivo, se a conexão cair, o canal reconecta em vez de pular para o próximo.
        if (live) p.repeatMode = Player.REPEAT_MODE_ONE

        var start = if (live) C.TIME_UNSET else resumePosition
        if (!live && start == C.TIME_UNSET) {
            UserData.resumePosition(items[resumeIndex].key)?.let {
                start = it
                Toast.makeText(this, "Continuando de ${UserData.formatTime(it)}", Toast.LENGTH_LONG).show()
            }
        }

        currentIndex = resumeIndex
        lastPosition = 0
        lastDuration = 0
        p.addListener(listener)
        p.setMediaItems(items.map(::toMediaItem), resumeIndex, start)
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        playerView.requestFocus()
        player = p
        recordWatch(resumeIndex)
        showInfo(withHelp = true)
        if (!live) handler.postDelayed(tick, PROGRESS_INTERVAL_MS)
    }

    private fun releasePlayer() {
        val p = player ?: return
        handler.removeCallbacks(tick)
        trackProgress(save = true)
        resumeIndex = p.currentMediaItemIndex
        resumePosition = if (live) C.TIME_UNSET else p.currentPosition
        playerView.player = null
        p.release()
        player = null
    }

    // ---- progresso e histórico ----

    private fun trackProgress(save: Boolean) {
        val p = player ?: return
        if (live || p.currentMediaItemIndex != currentIndex) return
        val duration = p.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        lastPosition = p.currentPosition
        lastDuration = duration
        if (save) items.getOrNull(currentIndex)?.let { UserData.saveProgress(it.key, lastPosition, lastDuration) }
    }

    private fun recordWatch(index: Int) {
        val entry = items.getOrNull(index) ?: return
        val series = seriesKey
        if (series != null) {
            UserData.recordWatch(series)
            UserData.setLastEpisode(series, entry.key)
        } else {
            UserData.recordWatch(entry.key)
        }
    }

    private fun toMediaItem(entry: Entry): MediaItem {
        val parts = entry.url.split('|', limit = 2)
        val uri = parts[0].trim()
        if (parts.size == 2) {
            val extra = parts[1].split('&').mapNotNull { pair ->
                val i = pair.indexOf('=')
                if (i > 0) pair.substring(0, i).trim() to Uri.decode(pair.substring(i + 1).trim()) else null
            }.toMap()
            if (extra.isNotEmpty()) headers[uri] = extra
        }
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).build())
        if ("m3u8" in uri.lowercase()) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            val previous = items.getOrNull(currentIndex)
            // O episódio anterior acabou sozinho: conta como visto.
            if (!live && previous != null && lastDuration > 0) {
                val position = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastDuration else lastPosition
                UserData.saveProgress(previous.key, position, lastDuration)
            }
            currentIndex = p.currentMediaItemIndex
            lastPosition = 0
            lastDuration = 0
            if (!live) {
                items.getOrNull(currentIndex)?.let { entry ->
                    UserData.resumePosition(entry.key)?.let { p.seekTo(it) }
                }
            }
            recordWatch(currentIndex)
            error.isVisible = false
            showInfo(withHelp = false)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) error.isVisible = false
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            val tip = if (live) "Aperte OK para tentar de novo, ou cima/baixo para trocar de canal."
            else "Aperte OK para tentar de novo."
            error.text = "Não foi possível reproduzir este conteúdo.\n(${e.errorCodeName})\n\n$tip"
            error.isVisible = true
        }
    }

    private fun showInfo(withHelp: Boolean) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val entry = items.getOrNull(index) ?: return
        val title = if (live) "${index + 1}. ${entry.name}\n${entry.group}" else entry.name
        val help = when {
            !withHelp -> ""
            live -> "\n\nCima/baixo: trocar de canal"
            else -> "\n\nOK: pausar  ·  Esquerda/direita: voltar/avançar"
        }
        info.text = title + help
        info.isVisible = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, if (withHelp) 6000L else 4000L)
    }

    // ---- teclas ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (handleKey(p, code, event.repeatCount)) {
                consumedKey = code
                return true
            }
            KeyEvent.ACTION_UP -> if (code == consumedKey) {
                consumedKey = KeyEvent.KEYCODE_UNKNOWN
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleKey(p: ExoPlayer, code: Int, repeat: Int): Boolean {
        val controlsVisible = playerView.isControllerFullyVisible

        if (error.isVisible && code in OK_KEYS) {
            error.isVisible = false
            p.prepare()
            p.play()
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> return togglePause(p)
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.pause(); playerView.showController(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return live || seek(p, SEEK_FORWARD_BUTTON_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> return live || seek(p, -SEEK_STEP_MS)
            KeyEvent.KEYCODE_BACK -> if (controlsVisible) { playerView.hideController(); return true }
        }

        // Com a barra visível, o controle navega pelos botões dela.
        if (controlsVisible) return false

        if (live) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                in OK_KEYS -> { playerView.showController(); return true }
            }
            return false
        }

        return when (code) {
            in OK_KEYS -> togglePause(p)
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(p, -stepFor(repeat))
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(p, stepFor(repeat))
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { playerView.showController(); true }
            else -> false
        }
    }

    /** Segurar a seta acelera: 10 s, depois 30 s, depois 1 min por toque. */
    private fun stepFor(repeat: Int) = when {
        repeat < 5 -> SEEK_STEP_MS
        repeat < 15 -> 30_000L
        else -> 60_000L
    }

    private fun togglePause(p: ExoPlayer): Boolean {
        if (p.isPlaying) p.pause() else p.play()
        playerView.showController()
        return true
    }

    private fun seek(p: ExoPlayer, delta: Long): Boolean {
        if (!p.isCurrentMediaItemSeekable) return true
        val duration = p.duration
        var target = (p.currentPosition + delta).coerceAtLeast(0)
        if (duration != C.TIME_UNSET && duration > 0) target = target.coerceAtMost(duration - 1000)
        p.seekTo(target)
        playerView.showController()
        return true
    }

    private fun zap(delta: Int) {
        val p = player ?: return
        val count = p.mediaItemCount
        if (count <= 1) return
        val next = ((p.currentMediaItemIndex + delta) % count + count) % count
        error.isVisible = false
        p.seekToDefaultPosition(next)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    companion object {
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_FORWARD_BUTTON_MS = 30_000L
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
    }
}
