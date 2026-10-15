package com.dcg.tv.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.dcg.tv.R
import com.dcg.tv.data.Entry
import com.dcg.tv.data.PlayerQueue
import com.dcg.tv.data.PlaylistRepository

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private lateinit var error: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideInfo = Runnable { info.isVisible = false }

    private var items: List<Entry> = emptyList()
    private var live = false
    private var resumeIndex = 0
    private var resumePosition = C.TIME_UNSET

    /** Cabeçalhos extras no formato "link|User-Agent=...&Referer=...". */
    private val headers = HashMap<String, Map<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = PlayerQueue.items
        live = PlayerQueue.live
        if (items.isEmpty()) {
            finish()
            return
        }
        resumeIndex = (savedInstanceState?.getInt(KEY_INDEX) ?: PlayerQueue.start).coerceIn(items.indices)
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
    }

    private fun initPlayer() {
        if (player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(PlaylistRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            headers[spec.uri.toString()]?.let { spec.withAdditionalHeaders(it) } ?: spec
        }

        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        // No ao vivo, se a conexão cair, o canal reconecta em vez de pular para o próximo.
        if (live) p.repeatMode = Player.REPEAT_MODE_ONE
        p.addListener(listener)
        p.setMediaItems(items.map(::toMediaItem), resumeIndex, if (live) C.TIME_UNSET else resumePosition)
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
        showInfo()
    }

    private fun releasePlayer() {
        val p = player ?: return
        resumeIndex = p.currentMediaItemIndex
        resumePosition = if (live) C.TIME_UNSET else p.currentPosition
        playerView.player = null
        p.release()
        player = null
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
            error.isVisible = false
            showInfo()
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

    private fun showInfo() {
        val p = player ?: return
        val entry = items.getOrNull(p.currentMediaItemIndex) ?: return
        info.text = if (live) "${p.currentMediaItemIndex + 1}. ${entry.name}\n${entry.group}" else entry.name
        info.isVisible = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 4000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            if (error.isVisible && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
                error.isVisible = false
                p.prepare()
                p.play()
                return true
            }
            if (live && !playerView.isControllerFullyVisible) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        zap(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        zap(-1)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
    }
}
